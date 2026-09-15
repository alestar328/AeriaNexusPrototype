package com.delta.aeria_nexus_prototype.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.delta.aeria_nexus_prototype.BuildConfig
import com.delta.aeria_nexus_prototype.data.audit.AuditoriaLocal
import com.delta.aeria_nexus_prototype.data.audit.TipoEvento
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceCrypto
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceVault
import com.delta.aeria_nexus_prototype.data.model.EvidenceType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Acceso de lectura a la evidencia cifrada del dispositivo: lista los .fev y los
 * descifra bajo demanda cuando la boveda esta desbloqueada.
 *
 * Los ficheros descifrados van a una carpeta de cache aparte y se borran al
 * bloquear la boveda: fuera de ese rato no hay ninguna copia en claro en el
 * telefono. El descifrado es a fichero y no en memoria porque un video de 20 min
 * no cabe en el heap de la app.
 */
class VaultRepository(
    private val context: Context,
    private val auditoria: AuditoriaLocal,
) {

    /** Un .fev de la carpeta de evidencia, tal como se lista en la boveda. */
    data class VaultItem(
        val name: String,
        val type: EvidenceType,
        val bytes: Long,
        val capturedAt: String,
        /**
         * Si este fichero se cifro para esta boveda. En false para lo capturado
         * antes de crear la contrasena: eso solo lo abre Nexus, y conviene decirlo
         * en la lista en vez de dejar una vista previa que nunca carga.
         */
        val openable: Boolean,
    )

    /** Evidencia cifrada del dispositivo, de la mas reciente a la mas antigua. */
    fun list(): List<VaultItem> {
        val carpeta = evidenceDir() ?: return emptyList()
        val ficheros = carpeta.listFiles { f -> f.isFile && f.name.endsWith(EvidenceCrypto.EXTENSION) }
            ?: return emptyList()
        return ficheros
            .sortedByDescending { it.lastModified() }
            .map { fichero ->
                VaultItem(
                    name = fichero.name,
                    type = typeOf(fichero.name),
                    bytes = fichero.length(),
                    capturedAt = FORMATO_FECHA.format(Date(fichero.lastModified())),
                    openable = isForThisVault(fichero),
                )
            }
    }

    /**
     * Descifra [name] a la cache y devuelve el uri con el que verlo. Null si la
     * boveda esta bloqueada, si el fichero no es de esta boveda o si el contenido
     * no supera la verificacion de integridad.
     *
     * Si ya estaba descifrado se reutiliza: abrir dos veces la misma foto no
     * vuelve a descifrar el fichero entero.
     */
    suspend fun open(name: String): Uri? = withContext(Dispatchers.IO) {
        val uri = descifrar(name)
        // Workflow 46: quien mira que evidencia, cada vez, este donde este la vista
        // previa (boveda, incidente o informe): todas pasan por aqui. Tambien los
        // intentos que no llegan a mostrar nada, que tambien son un acceso.
        auditoria.registrar(
            TipoEvento.EVIDENCIA_VISUALIZADA,
            listOf("evidence" to name, "result" to if (uri != null) "SHOWN" else "NOT_OPENED"),
        )
        uri
    }

    private fun descifrar(name: String): Uri? {
        if (!EvidenceVault.desbloqueada.value) return null
        val cifrado = evidenceDir()?.let { File(it, name) }?.takeIf { it.isFile } ?: return null
        val carpeta = decryptedDir() ?: return null

        // El .fev es "video_agente_fecha.mp4.fev": quitarle el sufijo devuelve el
        // nombre original, y con el la extension que necesita el reproductor.
        val claro = File(carpeta, name.removeSuffix(EvidenceCrypto.EXTENSION))
        if (!claro.isFile && !EvidenceCrypto.open(cifrado, claro)) return null
        return try {
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", claro)
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo servir ${claro.name}: ${e.message}")
            null
        }
    }

    /** Borra las copias descifradas. Se llama siempre al bloquear la boveda. */
    fun clearDecrypted() {
        decryptedDir()?.listFiles()?.forEach { fichero ->
            if (!fichero.delete()) Log.w(TAG, "no se pudo borrar la copia descifrada ${fichero.name}")
        }
    }

    /**
     * Si la cabecera del .fev lleva un envoltorio para esta boveda. Solo lee los
     * primeros cientos de bytes: no descifra nada ni necesita la contrasena.
     */
    private fun isForThisVault(fichero: File): Boolean =
        EvidenceCrypto.readHeader(fichero)?.wraps?.any { it.first == EvidenceVault.WRAPPER_ID } == true

    /** Tipo de evidencia segun el prefijo con el que la nombro la captura. */
    private fun typeOf(name: String): EvidenceType = when (name.substringBefore('_')) {
        "photo" -> EvidenceType.PHOTO
        "audio" -> EvidenceType.AUDIO
        else -> EvidenceType.VIDEO
    }

    /** Misma carpeta que usa LocalEvidenceRepository para los .fev. */
    private fun evidenceDir(): File? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, EVIDENCE_FOLDER).takeIf { it.isDirectory }
    }

    private fun decryptedDir(): File? {
        val carpeta = File(context.cacheDir, DECRYPTED_FOLDER)
        return if (carpeta.isDirectory || carpeta.mkdirs()) carpeta else null
    }

    private companion object {
        const val TAG = "VaultRepository"
        const val EVIDENCE_FOLDER = "evidence"
        const val DECRYPTED_FOLDER = "vault"

        val FORMATO_FECHA = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.US)
    }
}
