package com.delta.aeria_nexus_prototype.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.delta.aeria_nexus_prototype.BuildConfig
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceCrypto
import com.delta.aeria_nexus_prototype.data.model.EvidenceType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Almacen local de la evidencia capturada con el propio telefono cuando la
 * bodycam no esta conectada.
 *
 * Nada de esto pasa por la galeria: la captura se escribe en la carpeta privada
 * de la app, se cifra al cerrarla y el original en claro se borra. Lo unico que
 * queda en el dispositivo es el .fev, que solo se puede volver a abrir con la
 * contrasena de la boveda (ver EvidenceVault) o desde el servidor.
 *
 * Nombres descriptivos: tipo y fecha. Sin el agente (EVD-002): el nombre viaja como
 * metadato y no prueba quien grabo; eso lo dice la sesion con la que se sube.
 */
class LocalEvidenceRepository(private val context: Context) {

    /**
     * Destino de una captura: el archivo privado donde escribe la camara o el
     * grabador y el uri de FileProvider con el que se le entrega. [name] da
     * nombre tambien al .fev.
     */
    data class MediaTarget(val uri: Uri, val file: File, val name: String)

    /** Captura ya cerrada, con el resultado del cifrado (null si no se pudo cifrar). */
    data class SealedCapture(val target: MediaTarget, val sealed: EvidenceCrypto.Sealed?)

    /** A que incidente pertenece una captura. Es lo que la subida manda con ella. */
    data class Procedencia(
        val evidenceId: String,
        val incidentId: String?,
        val label: String,
        val type: EvidenceType,
    )

    /**
     * A quien se entrega una captura que se cifro al reintentar, cuando ya nadie la
     * esperaba. Lo pone AppContainer: la subida se crea despues que este repositorio.
     */
    var alCifrarTarde: ((EvidenceCrypto.Sealed, Procedencia) -> Unit)? = null

    fun createPhotoTarget(): MediaTarget? = createTarget("photo", "jpg")

    fun createVideoTarget(): MediaTarget? = createTarget("video", "mp4")

    /**
     * Destino para un fichero que llega de las gafas (ver GafasMediaRepository).
     *
     * Se separa del de la camara del telefono solo en el nombre, y a proposito: la
     * procedencia de una evidencia es parte de la evidencia, y el nombre del .fev
     * es lo unico que la lleva hasta que exista un campo propio en el manifest.
     * Todo lo demas —carpeta privada, cifrado al cerrar, borrado del claro— es
     * identico, que es justo lo que hace que traerse el video de las gafas no
     * necesite una via de custodia aparte.
     */
    fun createGafasVideoTarget(extension: String = "mp4"): MediaTarget? =
        createTarget("gafas", extension)

    private fun createAudioTarget(): MediaTarget? = createTarget("audio", "m4a")

    /**
     * Nombre unico para una captura nueva.
     *
     * Lleva milisegundos y, aun asi, comprueba que no exista: la descarga de las
     * gafas baja varios ficheros seguidos, y con resolucion de segundos dos videos
     * pequenos salian con el MISMO nombre. El segundo pisaba al primero y la
     * boveda acababa con dos filas apuntando al mismo fichero, lo que reventaba la
     * pantalla al pintar dos elementos con la misma clave. Hasta que hubo descargas
     * en lote esto no podia pasar: las capturas del telefono las hace una persona,
     * de una en una.
     */
    private fun createTarget(type: String, extension: String): MediaTarget? {
        val carpeta = capturesDir() ?: return null
        val fecha = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
        var nombre = "${type}_$fecha.$extension"
        var archivo = File(carpeta, nombre)
        var repeticion = 1
        while (archivo.exists()) {
            nombre = "${type}_${fecha}_$repeticion.$extension"
            archivo = File(carpeta, nombre)
            repeticion++
        }
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", archivo)
        return MediaTarget(uri, archivo, nombre)
    }

    // ── Cifrado de la evidencia (formato FEVD v1) ────────────────────────────
    // Punto de enganche del cifrado, igual que en la app de la bodycam: SIEMPRE
    // al cerrar el destino, nunca durante la captura. El contrato del formato es
    // docs/CRYPTO-FORMAT.md del repo BodyCamServer; las dos apps producen el
    // mismo fichero con el mismo codigo (data/crypto).

    /**
     * Cierra la captura: la cifra y borra el original en claro. Suspende porque el
     * cifrado hace disco y CPU y nunca puede correr en el hilo principal (una
     * grabacion de 20 min tarda unos 5 s).
     *
     * Si el cifrado falla el original se queda donde esta, sin cifrar: perder la
     * evidencia seria peor que tenerla en claro dentro de la carpeta privada de la
     * app. Pero no se queda asi (CRY-001): se reintenta en segundo plano unas veces y,
     * si la app muere antes, al arrancar ([sellarLasQueFallaron]).
     */
    suspend fun seal(target: MediaTarget, conservarClaro: Boolean = false): EvidenceCrypto.Sealed? {
        val sellada = sellar(target.file, conservarClaro)
        if (sellada == null) reintentarMasTarde(target.file, conservarClaro)
        return sellada
    }

    private suspend fun sellar(claro: File, conservarClaro: Boolean): EvidenceCrypto.Sealed? =
        withContext(Dispatchers.IO) {
            val carpeta = evidenceDir() ?: run {
                Log.w(TAG, "Sin carpeta privada para el .fev: la evidencia queda sin cifrar")
                return@withContext null
            }
            EvidenceCrypto.seal(
                plain = claro,
                dest = File(carpeta, claro.name + EvidenceCrypto.EXTENSION),
                borrarClaro = EvidenceCrypto.DELETE_PLAINTEXT && !conservarClaro,
            )
        }

    /**
     * Lo que suele hacer fallar el cifrado (disco lleno, el Keystore que tarda en
     * responder) se arregla solo en minutos. Se deja de intentar si el claro ya no
     * esta o si alguien ya lo cifro.
     */
    private fun reintentarMasTarde(claro: File, conservarClaro: Boolean) {
        scope.launch {
            for (espera in ESPERAS_REINTENTO_MILLIS) {
                delay(espera)
                if (!claro.isFile || tieneFev(claro)) return@launch
                val sellada = sellar(claro, conservarClaro)
                if (sellada != null) {
                    Log.i(TAG, "${claro.name} cifrada al reintentar")
                    entregarTarde(claro, sellada)
                    return@launch
                }
            }
            Log.e(TAG, "${claro.name} sigue sin cifrar: se reintenta al arrancar")
        }
    }

    /**
     * Cifra las capturas que se quedaron en claro sin su .fev porque el cifrado fallo
     * y la app murio antes de reintentarlo. Solo al arrancar, antes de que haya nada
     * capturando: con la app en marcha, una grabacion en curso tambien esta en
     * captures/ sin .fev, y cifrarla a medias la cortaria.
     *
     * Las fotos se cifran y se borran. Audio y video conservan el claro, igual que en
     * la captura normal, para que la recuperacion de la transcripcion y del proxy
     * ([cifradasEnClaro], [videosSinProxy]) los recoja despues y los borre.
     */
    suspend fun sellarLasQueFallaron() {
        val capturas = capturesDir() ?: return
        val sinCifrar = capturas.listFiles { f ->
            f.isFile && !f.name.endsWith(PROXY_SUFFIX) && !f.name.endsWith(SUFIJO_PROCEDENCIA) && !tieneFev(f)
        }.orEmpty()
        sinCifrar.forEach { claro ->
            val esFoto = claro.name.startsWith("photo_")
            val sellada = sellar(claro, conservarClaro = !esFoto) ?: return@forEach
            Log.i(TAG, "${claro.name} cifrada al arrancar")
            entregarTarde(claro, sellada)
        }
    }

    /**
     * Apunta junto al claro a que incidente pertenece una captura que no se pudo
     * cifrar. Se hace en el momento del fallo, cuando el incidente aun se conoce: el
     * reintento puede llegar tras un reinicio, y sin esto la captura llegaria al
     * backend sin enlace.
     */
    fun anotarParaElReintento(target: MediaTarget, procedencia: Procedencia) {
        val datos = JSONObject()
            .put("evidence_id", procedencia.evidenceId)
            .put("label", procedencia.label)
            .put("type", procedencia.type.name)
        procedencia.incidentId?.let { datos.put("incident_id", it) }
        runCatching { procedenciaDe(target.file).writeText(datos.toString()) }
            .onFailure { Log.e(TAG, "no se pudo apuntar el incidente de ${target.name}: ${it.message}") }
    }

    /**
     * Sin procedencia apuntada (una captura de antes de este cambio, o las gafas, que
     * van sin incidente) no se encola aqui: la subida de lo pendiente la manda igual,
     * sin incident_id.
     */
    private fun entregarTarde(claro: File, sellada: EvidenceCrypto.Sealed) {
        val fichero = procedenciaDe(claro)
        val datos = runCatching { JSONObject(fichero.readText()) }.getOrNull() ?: return
        fichero.delete()
        val tipo = EvidenceType.entries.find { it.name == datos.optString("type") } ?: return
        val procedencia = Procedencia(
            evidenceId = datos.optString("evidence_id"),
            incidentId = datos.optString("incident_id").ifBlank { null },
            label = datos.optString("label"),
            type = tipo,
        )
        alCifrarTarde?.invoke(sellada, procedencia)
    }

    private fun procedenciaDe(claro: File) = File(claro.path + SUFIJO_PROCEDENCIA)

    private fun tieneFev(claro: File): Boolean =
        evidenceDir()?.let { File(it, claro.name + EvidenceCrypto.EXTENSION).isFile } == true

    /**
     * Donde se escribe en claro el proxy de [original]: junto a el, en captures/, y
     * con un sufijo propio para que nunca se confunda con una captura.
     */
    fun createProxyFile(original: File): File? {
        val carpeta = capturesDir() ?: return null
        return File(carpeta, original.name.removeSuffix(".mp4") + PROXY_SUFFIX)
    }

    /**
     * Cifra un proxy y borra su claro. Va a proxies/ y no a evidence/ porque todo lo
     * que hay en evidence/ lo lista la boveda y lo sube como evidencia: un proxy
     * ahi apareceria duplicado. El proxy no es evidencia, es una copia para el
     * backend que desaparece en cuanto se entrega.
     */
    suspend fun sealProxy(plain: File): EvidenceCrypto.Sealed? = withContext(Dispatchers.IO) {
        val carpeta = privateDir(PROXIES_FOLDER) ?: return@withContext null
        EvidenceCrypto.seal(plain, File(carpeta, plain.name + EvidenceCrypto.EXTENSION), borrarClaro = true)
    }

    /**
     * Videos que ya estan cifrados pero siguen en claro en captures/: la app murio
     * mientras se hacia su proxy. El claro solo sobrevive al cifrado por eso, asi
     * que su .fev es la prueba de que falta el proxy y no la captura entera.
     */
    fun videosSinProxy(): List<File> {
        val capturas = capturesDir() ?: return emptyList()
        val evidencia = evidenceDir() ?: return emptyList()
        val ficheros = capturas.listFiles { f ->
            f.isFile && f.name.startsWith("video_") && !f.name.endsWith(PROXY_SUFFIX) &&
                File(evidencia, f.name + EvidenceCrypto.EXTENSION).isFile
        }
        return ficheros?.toList().orEmpty()
    }

    /**
     * Cifra una transcripcion y borra su claro. Va a transcripts/ por lo mismo que el
     * proxy va a proxies/: lo que hay en evidence/ la boveda lo lista y lo sube como
     * evidencia, y la transcripcion es una pieza aparte (kind=transcript).
     */
    suspend fun sealTranscript(plain: File): EvidenceCrypto.Sealed? = withContext(Dispatchers.IO) {
        val carpeta = privateDir(TRANSCRIPTS_FOLDER) ?: return@withContext null
        EvidenceCrypto.seal(plain, File(carpeta, plain.name + EvidenceCrypto.EXTENSION), borrarClaro = true)
    }

    /**
     * Guarda cifrada una transcripcion que llega de la bodycam en claro por el canal
     * Bluetooth. Va a transcripts/bodycam/, que la subida no mira: esas las sube la
     * propia bodycam, y aqui solo se guardan para leerlas en la boveda.
     */
    suspend fun sealBodycamTranscript(json: String, nombre: String): EvidenceCrypto.Sealed? =
        withContext(Dispatchers.IO) {
            val carpeta = bodycamTranscriptsDir() ?: return@withContext null
            val claro = File(carpeta, "$nombre.part")
            claro.writeText(json, Charsets.UTF_8)
            val sellada = EvidenceCrypto.seal(claro, File(carpeta, nombre + EvidenceCrypto.EXTENSION), borrarClaro = true)
            // Si el cifrado falla el claro no se queda: se vuelve a pedir a la camara.
            if (claro.exists()) claro.delete()
            sellada
        }

    fun bodycamTranscriptsDir(): File? = privateDir(BODYCAM_TRANSCRIPTS_FOLDER)

    /** Trabajos de transcripcion sin terminar: el audio ya decodificado y sus datos. */
    fun transcriptsPendingDir(): File? = privateDir("$TRANSCRIPTS_FOLDER/pendientes")

    fun transcriptsDir(): File? = privateDir(TRANSCRIPTS_FOLDER)

    /**
     * Capturas con [prefijo] que siguen en claro aunque su .fev ya existe: la app
     * murio antes de sacar de ellas el audio para transcribir. Los videos del
     * telefono no pasan por aqui, los recoge [videosSinProxy].
     */
    fun cifradasEnClaro(prefijo: String): List<File> {
        val capturas = capturesDir() ?: return emptyList()
        val evidencia = evidenceDir() ?: return emptyList()
        val ficheros = capturas.listFiles { f ->
            f.isFile && f.name.startsWith(prefijo) &&
                File(evidencia, f.name + EvidenceCrypto.EXTENSION).isFile
        }
        return ficheros?.toList().orEmpty()
    }

    /** Proxies en claro que se quedaron a medio escribir. No valen: se rehacen. */
    fun proxiesSinTerminar(): List<File> {
        val ficheros = capturesDir()?.listFiles { f -> f.name.endsWith(PROXY_SUFFIX) }
        return ficheros?.toList().orEmpty()
    }

    /**
     * Carpeta donde la camara y el grabador escriben la captura en claro. Es
     * privada de la app y su contenido solo vive los segundos que van del cierre
     * de la captura al cifrado.
     */
    private fun capturesDir(): File? = privateDir(CAPTURES_FOLDER)

    /** Carpeta de los .fev. Privada de la app, nunca la galeria. */
    private fun evidenceDir(): File? = privateDir(EVIDENCE_FOLDER)

    private fun privateDir(nombre: String): File? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val carpeta = File(base, nombre)
        return if (carpeta.isDirectory || carpeta.mkdirs()) carpeta else null
    }

    /** Descarta una captura cancelada o fallida. */
    fun discard(target: MediaTarget) {
        if (target.file.exists() && !target.file.delete()) {
            Log.w(TAG, "No se pudo descartar la captura")
        }
    }

    /**
     * Duracion legible de un video o audio recien capturado, o null si no se
     * puede leer. Hay que llamarla antes de [seal]: despues del cifrado el
     * original ya no existe.
     */
    fun mediaDuration(target: MediaTarget): String? = try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, target.uri)
        val millis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
        retriever.release()
        millis?.let {
            val segundos = (it / 1000).toInt()
            "${segundos / 60}m ${segundos % 60}s"
        }
    } catch (e: Exception) {
        null
    }

    // ── Nota de audio con el microfono del telefono ──────────────────────────

    private var recorder: MediaRecorder? = null
    private var audioTarget: MediaTarget? = null

    private val _grabandoAudio = MutableStateFlow(false)

    /** True mientras hay una nota de audio en curso. La mira el vigilante de caducidad. */
    val grabandoAudio: StateFlow<Boolean> = _grabandoAudio.asStateFlow()

    // Scope de aplicacion, igual que en EvidenceUploader: cerrar una nota de audio
    // no puede depender de que siga viva la pantalla que la empezo.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Empieza a grabar audio. Requiere el permiso RECORD_AUDIO ya concedido. */
    fun startAudioRecording(): Boolean {
        if (recorder != null) return true
        val destino = createAudioTarget() ?: return false
        return try {
            // El constructor sin context quedo obsoleto en Android 12.
            val nuevo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            nuevo.setAudioSource(MediaRecorder.AudioSource.MIC)
            nuevo.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            nuevo.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            nuevo.setOutputFile(destino.file.absolutePath)
            nuevo.prepare()
            nuevo.start()
            recorder = nuevo
            audioTarget = destino
            _grabandoAudio.value = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo iniciar la grabacion de audio", e)
            recorder?.release()
            recorder = null
            discard(destino)
            false
        }
    }

    /**
     * Detiene la grabacion y la cifra. Es el camino normal de una nota de audio. El
     * claro se conserva para transcribirlo: lo borra quien lo transcribe.
     */
    suspend fun stopAudioRecordingSealed(): SealedCapture? {
        val destino = stopAudioRecorder() ?: return null
        return SealedCapture(destino, seal(destino, conservarClaro = true))
    }

    /**
     * Detiene la grabacion y la cifra en segundo plano. Es el camino de cuando la
     * pantalla se destruye grabando: ahi ya no hay ViewModel donde suspender, pero
     * la nota tampoco puede quedarse en claro.
     */
    fun stopAudioRecording() {
        val destino = stopAudioRecorder() ?: return
        scope.launch { seal(destino) }
    }

    /** Detiene y libera el grabador. Devuelve el destino aun sin cifrar. */
    private fun stopAudioRecorder(): MediaTarget? {
        val activo = recorder ?: return null
        val destino = audioTarget
        recorder = null
        audioTarget = null
        _grabandoAudio.value = false
        return try {
            activo.stop()
            activo.release()
            destino
        } catch (e: Exception) {
            // stop() lanza si la grabacion fue demasiado corta: se descarta.
            Log.w(TAG, "Grabacion de audio descartada: ${e.message}")
            activo.release()
            destino?.let(::discard)
            null
        }
    }

    companion object {
        private const val TAG = "LocalEvidence"

        // Carpetas dentro del almacenamiento privado de la app
        // (Android/data/<paquete>/files). Fuera de la galeria y, desde Android 11,
        // fuera del alcance del explorador de archivos y de otras apps.
        private const val CAPTURES_FOLDER = "captures"
        private const val EVIDENCE_FOLDER = "evidence"

        /** Proxies cifrados a la espera de subirse. La lee tambien EvidenceUploader. */
        const val PROXIES_FOLDER = "proxies"

        /** Transcripciones cifradas. La lee tambien EvidenceUploader. */
        const val TRANSCRIPTS_FOLDER = "transcripts"

        /** Transcripciones de la bodycam, solo para leer, dentro de transcripts/. La lee tambien VaultRepository. */
        const val BODYCAM_TRANSCRIPTS_SUBFOLDER = "bodycam"
        private const val BODYCAM_TRANSCRIPTS_FOLDER = "$TRANSCRIPTS_FOLDER/$BODYCAM_TRANSCRIPTS_SUBFOLDER"
        private const val PROXY_SUFFIX = "_proxy.mp4"
        private const val SUFIJO_PROCEDENCIA = ".procedencia.json"

        /** Reintentos del cifrado que fallo: al minuto, a los 5 y a los 15. */
        private val ESPERAS_REINTENTO_MILLIS = listOf(60_000L, 4 * 60_000L, 10 * 60_000L)
    }
}
