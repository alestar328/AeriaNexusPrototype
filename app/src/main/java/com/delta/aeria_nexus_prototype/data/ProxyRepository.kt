package com.delta.aeria_nexus_prototype.data

import android.util.Log
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceCrypto
import com.delta.aeria_nexus_prototype.data.upload.EvidenceUploader
import com.delta.aeria_nexus_prototype.data.video.ProxyEncoder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hace, cifra y entrega el proxy de cada video del telefono, y rehace al arrancar
 * los que se quedaron a medias. Ver docs/BACKEND-PROXY-AND-SOS.md §1.
 *
 * Corre en un scope de aplicacion: transcodificar un video largo lleva minutos y no
 * puede depender de que siga abierta la pantalla que lo grabo.
 */
class ProxyRepository(
    private val evidencia: LocalEvidenceRepository,
    private val encoder: ProxyEncoder,
    private val uploader: EvidenceUploader,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // De uno en uno: dos transcodificaciones a la vez compiten por el codificador de
    // hardware con la camara, y calientan el telefono sin terminar antes.
    private val turno = Mutex()

    /**
     * [original] sigue en claro (se cifro conservandolo) y [sellado] es su .fev. Al
     * terminar, el claro se borra salga o no el proxy: sin proxy, el original se
     * entrega igual y el backend puede sacar su copia de el.
     */
    fun procesar(
        original: LocalEvidenceRepository.MediaTarget,
        sellado: EvidenceCrypto.Sealed,
        evidenceId: String,
        incidentId: String?,
        label: String,
    ) {
        scope.launch {
            val proxy = hacerProxy(original.file, proxyOf = sellado.plainSha256)
            evidencia.discard(original)
            uploader.enqueueVideo(sellado, proxy, evidenceId, incidentId, label)
        }
    }

    /**
     * Rehace los proxies que no se terminaron porque la app murio a mitad. El .fev
     * del original ya existe y se entrega por su lado (EvidenceUploader.resumePending);
     * aqui solo falta la copia, y el enlace con su original es el hash del claro.
     */
    fun reanudar() {
        scope.launch {
            evidencia.proxiesSinTerminar().forEach { it.delete() }
            evidencia.videosSinProxy().forEach { claro ->
                Log.d(TAG, "rehaciendo el proxy de un video que se quedo a medias")
                val proxy = hacerProxy(claro, proxyOf = EvidenceCrypto.sha256(claro))
                if (!claro.delete()) Log.w(TAG, "no se pudo borrar el claro de un video ya cifrado")
                proxy?.let(uploader::enqueueProxy)
            }
        }
    }

    private suspend fun hacerProxy(original: File, proxyOf: String): EvidenceUploader.ProxySellado? =
        turno.withLock {
            val claro = evidencia.createProxyFile(original) ?: return null
            val copia = encoder.crear(original, claro)
            val sellado = copia?.let { evidencia.sealProxy(claro) }
            // sealProxy borra el claro al cifrar; si no se llego a cifrar, se borra aqui.
            if (claro.exists()) claro.delete()
            if (copia == null || sellado == null) return null
            EvidenceUploader.ProxySellado(sellado, proxyOf, copia.ladoCorto, copia.fps)
        }

    private companion object {
        const val TAG = "ProxyRepository"
    }
}
