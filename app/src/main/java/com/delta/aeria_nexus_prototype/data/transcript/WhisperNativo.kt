package com.delta.aeria_nexus_prototype.data.transcript

import android.os.Build
import android.util.Log
import java.io.File

/**
 * Puente con whisper.cpp (app/src/main/cpp). Solo carga la libreria si el
 * telefono puede ejecutarla: esta compilada para armv8.2 con fp16 y dotprod
 * (ver CMakeLists.txt), y en una CPU mas vieja la primera instruccion de esas
 * mataria la app entera, no solo la transcripcion.
 */
object WhisperNativo {

    private const val TAG = "WhisperNativo"

    /** Null si se puede usar; si no, el motivo, que acaba en el campo `error`. */
    val motivoNoDisponible: String? by lazy { cargar() }

    private fun cargar(): String? {
        if ("arm64-v8a" !in Build.SUPPORTED_64_BIT_ABIS) return "unsupported ABI"
        if (!cpuCompatible()) return "CPU without fp16/dotprod"
        return try {
            System.loadLibrary("transcripcion")
            null
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "no se pudo cargar libtranscripcion: ${e.message}")
            "native library missing"
        }
    }

    // asimddp = dotprod y fphp = fp16. Las dos las exige la compilacion.
    private fun cpuCompatible(): Boolean {
        val caracteristicas = runCatching { File("/proc/cpuinfo").readText() }.getOrNull() ?: return false
        return "asimddp" in caracteristicas && "fphp" in caracteristicas
    }

    /** Devuelve el contexto del modelo, o 0 si no se pudo abrir. */
    external fun abrir(rutaModelo: String): Long

    external fun cerrar(contexto: Long)

    external fun version(): String

    /**
     * JSON en UTF-8 con idioma, probabilidad y segmentos en centesimas de segundo
     * desde el inicio de [pcm] (16 kHz, mono). Null si whisper falla.
     */
    external fun transcribir(contexto: Long, pcm: FloatArray, hilos: Int): ByteArray?
}
