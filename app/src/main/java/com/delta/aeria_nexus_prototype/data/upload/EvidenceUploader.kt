package com.delta.aeria_nexus_prototype.data.upload

import android.content.Context
import android.os.Build
import android.util.Log
import com.delta.aeria_nexus_prototype.data.LocalEvidenceRepository
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceCrypto
import com.delta.aeria_nexus_prototype.data.local.IncidentDao
import com.delta.aeria_nexus_prototype.data.model.EvidenceType
import com.delta.aeria_nexus_prototype.data.model.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "FalconUpload"

/**
 * Entrega de la evidencia del teléfono a Nexus, por bloques y reanudable.
 *
 * Se sube **el `.fev` cifrado**, nunca el original de la galería: desde que el
 * destinatario `srv:` está activo el servidor puede abrirlo, y no hay razón para que la
 * evidencia viaje en claro. El transporte lo pone [ChunkedUploader], que es el mismo que
 * usa la bodycam.
 *
 * ── Qué es "entregado" ────────────────────────────────────────────────────────
 *
 * Solo cuando el servidor **no contradice** el hash. `verified == false` es un incidente
 * de integridad, no un fallo de red: se deja constancia y no se borra nada.
 *
 * ── Dónde vive la verdad ──────────────────────────────────────────────────────
 *
 * En un **recibo junto al `.fev`** (`<nombre>.upload.json`), no en Room. El registro de
 * Room se crea cuando el agente clasifica la evidencia, que puede ser después de que la
 * subida termine —o no llegar a pasar—, así que no sirve como fuente de verdad para saber
 * qué falta por subir. El estado en Room se actualiza igualmente cuando la fila existe,
 * porque es lo que ve el agente en la pantalla del incidente.
 *
 * ── Alcance del proceso ───────────────────────────────────────────────────────
 *
 * La subida corre en un scope de aplicación, no en el del ViewModel: salir de la pantalla
 * no debe cancelar una transferencia de 40 MB. Lo que **sí** la corta es que el sistema
 * mate el proceso; para eso está [resumePending], que se llama al arrancar la app y
 * retoma por el offset guardado. Si en el futuro hace falta sobrevivir al proceso en
 * background, el sitio es un servicio en primer plano con `foregroundServiceType=dataSync`.
 */
class EvidenceUploader(
    private val context: Context,
    private val config: UploadConfig,
    private val sessions: UploadSessions,
    private val dao: IncidentDao,
) {

    private val uploader = ChunkedUploader(config, sessions)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Proxies subiendo ahora mismo. Al arrancar coinciden la reanudacion y los
    // proxies que se rehacen, y un mismo fichero no puede ir en dos subidas a la vez.
    private val proxiesEnVuelo = ConcurrentHashMap.newKeySet<String>()

    /** Proxy de un video, ya cifrado, con lo que lo enlaza a su original (ver ProxyRepository). */
    data class ProxySellado(
        val sealed: EvidenceCrypto.Sealed,
        /** `sha256_plain` del ORIGINAL: es lo que une las dos piezas en el backend. */
        val proxyOf: String,
        val ladoCorto: Int,
        val fps: Int,
    )

    /**
     * Entrega un video del telefono: primero su proxy, si lo hay, y despues el original.
     * El proxy va antes porque es lo que el backend procesa y pesa varias veces menos:
     * con mala cobertura llega en segundos, y el original puede tardar.
     */
    fun enqueueVideo(
        original: EvidenceCrypto.Sealed,
        proxy: ProxySellado?,
        evidenceId: String?,
        incidentId: String?,
        label: String?,
    ) {
        // Los datos del proxy se guardan aunque la subida este apagada: son lo unico que
        // permitira enlazarlo con su original el dia que se configure.
        proxy?.let { guardarDatosDeProxy(it, evidenceId, incidentId) }
        if (!config.enabled()) {
            Log.d(TAG, "subida no configurada — ${original.file.name} se queda en el teléfono")
            return
        }
        scope.launch {
            proxy?.let { deliverProxy(it.sealed.file, it.sealed.cipherSha256) }
            deliver(original.file, original.cipherSha256, original.plainSha256, evidenceId, incidentId, label, EvidenceType.VIDEO)
        }
    }

    /** Proxy suelto, sin original detras: el que se rehace al arrancar la app. */
    fun enqueueProxy(proxy: ProxySellado) {
        guardarDatosDeProxy(proxy, evidenceId = null, incidentId = null)
        if (!config.enabled()) return
        scope.launch { deliverProxy(proxy.sealed.file, proxy.sealed.cipherSha256) }
    }

    /**
     * Encola la entrega de una captura recién cifrada. Vuelve inmediatamente: la subida
     * sigue en el scope de aplicación.
     */
    fun enqueue(
        sealed: EvidenceCrypto.Sealed,
        evidenceId: String?,
        incidentId: String?,
        label: String?,
        type: EvidenceType,
    ) {
        if (!config.enabled()) {
            Log.d(TAG, "subida no configurada — ${sealed.file.name} se queda en el teléfono")
            return
        }
        scope.launch { deliver(sealed.file, sealed.cipherSha256, sealed.plainSha256, evidenceId, incidentId, label, type) }
    }

    /**
     * Reintenta lo que quedó sin entregar. Se llama al arrancar la app, que es cuando se
     * recupera de un proceso muerto a mitad de subida, y cada vez que se abre sesión con
     * AeriaOne, que es cuando hay token con el que subir.
     */
    fun resumePending() {
        if (!config.enabled()) return
        if (config.token() == null) {
            Log.d(TAG, "sin sesión con AeriaOne — lo pendiente sale al abrirla")
            return
        }
        scope.launch {
            // Los proxies primero, por lo mismo que en enqueueVideo.
            proxiesDir()?.listFiles { f -> f.isFile && f.name.endsWith(EvidenceCrypto.EXTENSION) }
                ?.forEach { fev ->
                    val sha = runCatching { EvidenceCrypto.sha256(fev) }.getOrNull() ?: return@forEach
                    deliverProxy(fev, sha)
                }
            val pendientes = evidenceDir()?.listFiles { f ->
                f.isFile && f.name.endsWith(EvidenceCrypto.EXTENSION) && !isDelivered(f)
            }?.sortedBy { it.lastModified() }.orEmpty()
            if (pendientes.isEmpty()) return@launch
            Log.d(TAG, "reanudando ${pendientes.size} evidencia(s) sin entregar")
            pendientes.forEach { fev ->
                // El hash del ciphertext es la huella de la sesión. No está en el recibo
                // —si el fichero nunca llegó a subirse no hay recibo—, así que se recalcula.
                // Cuesta una lectura y solo pasa al reanudar.
                val sha = runCatching { EvidenceCrypto.sha256(fev) }.getOrNull() ?: return@forEach
                // Sin tipo: el backend lo deduce de la extension que hay bajo el .fev.
                deliver(fev, sha, null, null, null, null, type = null)
            }
        }
    }

    private suspend fun deliver(
        fev: File,
        cipherSha256: String,
        plainSha256: String?,
        evidenceId: String?,
        incidentId: String?,
        label: String?,
        type: EvidenceType?,
    ) = withContext(Dispatchers.IO) {
        if (isDelivered(fev)) return@withContext

        evidenceId?.let { setSync(it, SyncState.SYNCING) }

        val metadata = metadataComun(fev, cipherSha256, plainSha256, evidenceId, incidentId) + buildMap {
            put("kind", "evidence")
            label?.let { put("label", it) }
            // Un incidente del telefono mezcla fotos, videos y audios, y el backend
            // solo manda al analisis de video lo que es video (upload-protocol.md §5).
            type?.let { mediaTypeDe(it) }?.let { put("media_type", it) }
        }

        val outcome = uploader.upload(fev, cipherSha256, metadata)
        writeReceipt(fev, cipherSha256, outcome, evidenceId)

        when {
            outcome.delivered && outcome.verified == false -> {
                Log.e(TAG, "${fev.name}: el servidor NO confirma el hash — revisar")
                evidenceId?.let { setSync(it, SyncState.FAILED) }
            }
            outcome.delivered -> {
                Log.d(TAG, "${fev.name} entregado (${outcome.bytesSent / 1024} KB enviados)")
                evidenceId?.let { setSync(it, SyncState.VERIFIED) }
            }
            else -> {
                Log.e(TAG, "${fev.name} sin entregar: ${outcome.error}")
                evidenceId?.let { setSync(it, SyncState.PENDING_SYNC) }
            }
        }
        reconcile()
    }

    /**
     * Sube un proxy y, en cuanto el servidor no contradice su hash, lo borra: el proxy
     * no es evidencia, es una copia para el backend, y a partir de ahi la unica copia
     * es la suya. El recibo se queda como constancia de la entrega.
     *
     * No toca Room: el proxy comparte evidence_id con su original, y marcar esa fila
     * como entregada por el proxy diria que ha llegado el video cuando no es asi.
     */
    private suspend fun deliverProxy(fev: File, cipherSha256: String) = withContext(Dispatchers.IO) {
        if (!proxiesEnVuelo.add(fev.name)) return@withContext
        try {
            if (isDelivered(fev)) {
                borrarProxy(fev)
                return@withContext
            }
            val datos = runCatching { JSONObject(datosDeProxy(fev).readText()) }.getOrNull()
            if (datos == null) {
                Log.w(TAG, "${fev.name}: sin datos de su original, no se puede enlazar — se deja")
                return@withContext
            }
            val metadata = metadataComun(
                fev = fev,
                cipherSha256 = cipherSha256,
                plainSha256 = datos.optString("sha256_plain").ifBlank { null },
                evidenceId = datos.optString("evidence_id").ifBlank { null },
                incidentId = datos.optString("incident_id").ifBlank { null },
            ) + mapOf(
                "kind" to "proxy",
                "proxy_of" to datos.optString("proxy_of"),
                "proxy_short_side" to datos.optInt("proxy_short_side").toString(),
                "proxy_fps" to datos.optInt("proxy_fps").toString(),
            )

            val outcome = uploader.upload(fev, cipherSha256, metadata)
            writeReceipt(fev, cipherSha256, outcome, evidenceId = null)
            when {
                outcome.delivered && outcome.verified == false ->
                    Log.e(TAG, "${fev.name}: el servidor NO confirma el hash del proxy — se conserva")
                outcome.delivered -> borrarProxy(fev)
                else -> Log.e(TAG, "${fev.name} sin entregar: ${outcome.error}")
            }
        } finally {
            proxiesEnVuelo.remove(fev.name)
        }
    }

    private fun borrarProxy(fev: File) {
        if (fev.exists() && !fev.delete()) Log.w(TAG, "no se pudo borrar el proxy entregado ${fev.name}")
        datosDeProxy(fev).delete()
    }

    /**
     * Lo que enlaza un proxy con su original, junto al .fev. Hace falta en disco y no
     * solo en memoria porque si la app muere antes de subirlo, al arrancar solo queda
     * el fichero, y sin esto nadie sabria de que video es copia.
     */
    private fun guardarDatosDeProxy(proxy: ProxySellado, evidenceId: String?, incidentId: String?) {
        val datos = JSONObject()
            .put("proxy_of", proxy.proxyOf)
            .put("proxy_short_side", proxy.ladoCorto)
            .put("proxy_fps", proxy.fps)
            .put("sha256_plain", proxy.sealed.plainSha256)
        evidenceId?.let { datos.put("evidence_id", it) }
        incidentId?.let { datos.put("incident_id", it) }
        runCatching { datosDeProxy(proxy.sealed.file).writeText(datos.toString(2)) }
            .onFailure { Log.e(TAG, "no se pudieron guardar los datos del proxy: ${it.message}") }
    }

    private fun datosDeProxy(fev: File) = File(fev.parentFile, fev.name + PROXY_DATA_SUFFIX)

    /** Valor de `media_type`. Lo que subio un testigo no es de este camino. */
    private fun mediaTypeDe(type: EvidenceType): String? = when (type) {
        EvidenceType.PHOTO -> "photo"
        EvidenceType.VIDEO -> "video"
        EvidenceType.AUDIO -> "audio"
        EvidenceType.WITNESS_UPLOAD -> null
    }

    /** Metadata que llevan igual la evidencia y el proxy (UPLOAD-PROTOCOL.md §5). */
    private fun metadataComun(
        fev: File,
        cipherSha256: String,
        plainSha256: String?,
        evidenceId: String?,
        incidentId: String?,
    ): Map<String, String> = buildMap {
        put("filename", fev.name)
        put("sha256_cipher", cipherSha256)
        put("encrypted", "true")
        put("crypto_format", "FEVD1")
        put("source", "phone")
        put("device_model", Build.MODEL)
        put("officer_code", AGENT_ID)
        put("uploaded_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
        plainSha256?.let { put("sha256_plain", it) }
        incidentId?.let { put("incident_id", it) }
        evidenceId?.let { put("evidence_id", it) }
    }

    /**
     * Vuelca en Room el estado que dicen los recibos.
     *
     * Hace falta porque los tiempos no encajan: la subida termina en segundos, pero la
     * fila de la evidencia no existe hasta que el agente cierra el incidente. Un UPDATE
     * lanzado al terminar la subida no afecta a ninguna fila, y el INSERT posterior la
     * crea con el estado inicial — con lo que la evidencia aparece como "Local Only"
     * aunque el servidor ya la haya confirmado. Esto lo corrige en cuanto la fila existe.
     *
     * Es idempotente y barato: se llama al terminar cada subida, al arrancar la app y
     * justo despues de guardar un incidente.
     */
    fun reconcile() {
        scope.launch {
            val recibos = evidenceDir()?.listFiles { f -> f.name.endsWith(RECEIPT_SUFFIX) }.orEmpty()
            for (recibo in recibos) {
                val json = runCatching { JSONObject(recibo.readText()) }.getOrNull() ?: continue
                val id = json.optString("evidence_id").takeIf { it.isNotBlank() } ?: continue
                val entregado = json.optBoolean("delivered")
                val verificado = if (json.isNull("verified_by_server")) null
                                 else json.optBoolean("verified_by_server")
                val estado = when {
                    entregado && verificado == false -> SyncState.FAILED
                    entregado -> SyncState.VERIFIED
                    else -> SyncState.PENDING_SYNC
                }
                setSync(id, estado)
            }
        }
    }

    /**
     * Estado visible en la pantalla del incidente. Si la fila todavía no existe —el
     * incidente aún no se ha cerrado— la actualización no afecta a ninguna fila, y de eso
     * se encarga después [reconcile]. El recibo sigue siendo la verdad.
     */
    private suspend fun setSync(evidenceId: String, state: SyncState) {
        runCatching { dao.updateEvidenceSync(evidenceId, state) }
            .onFailure { Log.w(TAG, "no se pudo marcar $evidenceId como $state: ${it.message}") }
    }

    // ── Recibo ────────────────────────────────────────────────────────────────

    private fun receiptOf(fev: File) = File(fev.parentFile, fev.name + RECEIPT_SUFFIX)

    private fun isDelivered(fev: File): Boolean = try {
        receiptOf(fev).takeIf { it.isFile }
            ?.let { JSONObject(it.readText()).optBoolean("delivered") } ?: false
    } catch (_: Exception) { false }

    private fun writeReceipt(
        fev: File,
        cipherSha256: String,
        outcome: ChunkedUploader.Outcome,
        evidenceId: String?,
    ) {
        val receipt = JSONObject().apply {
            // Sin esto el recibo no se puede enlazar con su fila de Room, y la
            // pantalla del incidente muestra "Local Only" para algo ya entregado.
            put("evidence_id", evidenceId ?: JSONObject.NULL)
            put("delivered", outcome.delivered)
            put("verified_by_server", outcome.verified ?: JSONObject.NULL)
            put("filename", fev.name)
            put("sha256_uploaded", cipherSha256)
            put("bytes", fev.length())
            put("session_url", outcome.sessionUrl ?: JSONObject.NULL)
            put("endpoint", config.baseUrl())
            put("at_epoch_ms", System.currentTimeMillis())
            outcome.error?.let { put("error", it) }
        }
        runCatching { receiptOf(fev).writeText(receipt.toString(2)) }
            .onFailure { Log.e(TAG, "no se pudo escribir el recibo de ${fev.name}: ${it.message}") }
    }

    /** Misma carpeta que usa LocalEvidenceRepository para los .fev. */
    private fun evidenceDir(): File? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, EVIDENCE_FOLDER).takeIf { it.isDirectory }
    }

    private fun proxiesDir(): File? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, LocalEvidenceRepository.PROXIES_FOLDER).takeIf { it.isDirectory }
    }

    private companion object {
        const val RECEIPT_SUFFIX = ".upload.json"
        const val PROXY_DATA_SUFFIX = ".proxy.json"
        const val EVIDENCE_FOLDER = "evidence"

        /** TODO: sale de la sesión autenticada cuando exista login real (AUTH-001). */
        const val AGENT_ID = "agent_007"
    }
}
