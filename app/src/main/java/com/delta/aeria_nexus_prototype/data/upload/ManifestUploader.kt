package com.delta.aeria_nexus_prototype.data.upload

import android.content.Context
import android.os.Build
import android.util.Log
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceCrypto
import com.delta.aeria_nexus_prototype.data.model.OfficerIncident
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "FalconManifest"

/**
 * Sube el manifiesto de cada incidente (`manifest-schema.md` §5).
 *
 * Va aparte de [EvidenceUploader] porque su vida es otra: no es evidencia, no va
 * cifrado y se **reemplaza**. Cada vez que el incidente cambia —al cerrarlo, al
 * anadirle una pieza despues— se escribe entero otra vez y se vuelve a subir; el
 * backend aplica siempre el ultimo.
 *
 * Se guarda en disco antes de subirlo por lo mismo que la evidencia: si no hay
 * sesion o no hay red, sale en el siguiente [resumePending].
 */
class ManifestUploader(
    private val context: Context,
    private val config: UploadConfig,
    sessions: UploadSessions,
) {

    private val uploader = ChunkedUploader(config, sessions)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Un mismo manifiesto no puede ir en dos subidas a la vez: coinciden el cierre
    // del incidente y la reanudacion al acreditarse la sesion.
    private val enVuelo = ConcurrentHashMap.newKeySet<String>()

    /** Escribe el manifiesto del incidente y lo sube en segundo plano. */
    fun enqueue(incidente: OfficerIncident) {
        val fichero = ficheroDe(incidente.id) ?: return
        runCatching {
            fichero.writeText(ManifiestoDelTelefono.json(incidente, Build.MODEL))
            // El recibo era del manifiesto anterior; este todavia no ha salido.
            marcaDeEntregado(fichero).delete()
        }.onFailure {
            Log.e(TAG, "no se pudo escribir el manifiesto de ${incidente.id}: ${it.message}")
            return
        }
        scope.launch { deliver(fichero) }
    }

    /** Lo que quedo sin subir: sin sesion, sin red o con el proceso muerto a medias. */
    fun resumePending() {
        if (!config.enabled() || config.token() == null) return
        scope.launch {
            carpeta()?.listFiles { f -> f.name.endsWith(SUFIJO) && !marcaDeEntregado(f).exists() }
                ?.forEach { deliver(it) }
        }
    }

    private fun deliver(fichero: File) {
        if (!enVuelo.add(fichero.name)) return
        try {
            val incidentId = fichero.name.removeSuffix(SUFIJO)
            val sha = runCatching { EvidenceCrypto.sha256(fichero) }.getOrNull() ?: return
            val metadata = mapOf(
                "kind" to "manifest",
                "filename" to fichero.name,
                "incident_id" to incidentId,
                "sha256_cipher" to sha,
                "encrypted" to "false",
                "crypto_format" to "none",
                "source" to "phone",
                "device_model" to Build.MODEL,
            )
            val resultado = uploader.upload(fichero, sha, metadata)
            if (resultado.delivered && resultado.verified != false) {
                marcaDeEntregado(fichero).createNewFile()
                Log.d(TAG, "manifiesto de $incidentId entregado")
            } else {
                Log.w(TAG, "manifiesto de $incidentId sin entregar: ${resultado.error}")
            }
        } finally {
            enVuelo.remove(fichero.name)
        }
    }

    private fun carpeta(): File? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, CARPETA).apply { mkdirs() }.takeIf { it.isDirectory }
    }

    private fun ficheroDe(incidentId: String): File? = carpeta()?.let { File(it, incidentId + SUFIJO) }

    private fun marcaDeEntregado(fichero: File) = File(fichero.parentFile, fichero.name + ".delivered")

    private companion object {
        const val CARPETA = "manifests"
        const val SUFIJO = ".manifest.json"
    }
}
