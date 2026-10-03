package com.delta.aeria_nexus_prototype.data.transcript

import android.util.Log
import com.delta.aeria_nexus_prototype.data.BodycamRepository
import com.delta.aeria_nexus_prototype.data.BodycamState
import com.delta.aeria_nexus_prototype.data.LocalEvidenceRepository
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceCrypto
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Trae al telefono las transcripciones que hace la bodycam, para que el agente
 * las lea en la boveda: la pantalla de la unidad es de 3 cm. Protocolo:
 * TRANSCRIPT-FORMAT.md §7 de BodyCamServer.
 *
 * Solo se guardan para leerlas, cifradas en cuanto llegan. NO se suben: esas las
 * sube la propia bodycam con su incident_id `<unidad>/<secuencia>`.
 */
class TranscripcionesDeBodycam(
    private val bodycam: BodycamRepository,
    private val evidencia: LocalEvidenceRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Pedidas en esta conexion: UPLOAD_LIST se repite y no hay que pedir dos veces la misma.
    private val pedidas = ConcurrentHashMap.newKeySet<String>()

    fun arrancar() {
        scope.launch { bodycam.lineasDeTranscripcion.collect(::atender) }
        // Lo que se hizo con el telefono lejos solo se sabe por la lista de subidas.
        scope.launch {
            bodycam.subidas.collect { lista ->
                lista.filter { it.transcripcion == "ready" }.forEach { pedirSiFalta(it.id) }
            }
        }
        scope.launch {
            bodycam.state.collect { estado ->
                if (estado != BodycamState.CONNECTED) return@collect
                pedidas.clear()
                bodycam.pedirSubidas()
            }
        }
    }

    private suspend fun atender(linea: String) {
        when {
            linea.startsWith("TRANSCRIPT_READY:") -> pedirSiFalta(linea.removePrefix("TRANSCRIPT_READY:"))
            linea.startsWith("TRANSCRIPT_ERROR:") ->
                Log.w(TAG, "la camara no tiene la transcripcion: ${linea.removePrefix("TRANSCRIPT_ERROR:")}")
            linea.startsWith("TRANSCRIPT:") -> guardar(linea.removePrefix("TRANSCRIPT:"))
        }
    }

    private fun pedirSiFalta(id: String) {
        if (yaGuardada(id) || !pedidas.add(id)) return
        bodycam.sendCommand("TRANSCRIPT:$id")
    }

    /**
     * Solo se sabe si se sabe la unidad: un INC_000032 lo tienen todas las camaras.
     * Sin ella se pide una vez por conexion y la copia nueva sustituye a la anterior.
     */
    private fun yaGuardada(id: String): Boolean {
        val unidad = bodycam.unidadConectada ?: return false
        val carpeta = evidencia.bodycamTranscriptsDir() ?: return false
        return File(carpeta, nombreDeFichero("$unidad/$id") + EvidenceCrypto.EXTENSION).isFile
    }

    /** `INC_000032:{json}`. El JSON va en una linea y puede llevar ':' dentro. */
    private suspend fun guardar(resto: String) {
        val id = resto.substringBefore(':')
        val json = resto.substringAfter(':')
        val leida = TranscripcionJson.leer(json)
        if (leida == null) {
            Log.e(TAG, "transcripcion ilegible de la camara para $id")
            return
        }
        val incidente = leida.incidentId ?: "${bodycam.unidadConectada ?: "BWC"}/$id"
        val sellada = evidencia.sealBodycamTranscript(json, nombreDeFichero(incidente))
        if (sellada == null) {
            // Sin cifrar no se guarda: la siguiente conexion la vuelve a pedir.
            pedidas.remove(id)
            Log.e(TAG, "no se pudo cifrar la transcripcion de $incidente")
        } else {
            Log.d(TAG, "transcripcion de $incidente guardada en la boveda")
        }
    }

    companion object {
        private const val TAG = "TranscripcionBodycam"
        const val PREFIJO = "bodycam_"

        /** `BWC-896E/INC_000032` → `bodycam_BWC-896E_INC_000032.transcript.json`. */
        fun nombreDeFichero(incidentId: String): String =
            PREFIJO + incidentId.replace('/', '_') + ".transcript.json"

        /** El inverso, para listarlas sin descifrar: la unidad no lleva '_', la secuencia si. */
        fun incidenteDeFichero(nombre: String): String =
            nombre.removePrefix(PREFIJO).substringBefore(".transcript.json").replaceFirst('_', '/')
    }
}
