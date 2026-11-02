// Puente JNI con whisper.cpp. Lo minimo: abrir el modelo, transcribir un trozo
// de audio y cerrarlo. Todo lo demas (trocear, unir segmentos, el JSON del
// contrato) vive en Kotlin, que es donde lo puede leer cualquiera del equipo.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstdio>

#include "whisper.h"

#define TAG "WhisperNativo"

namespace {

// whisper.cpp escribe su log en stderr, que en Android no llega a ninguna parte.
// Solo pasan avisos y errores: el resto son cientos de lineas por grabacion.
void alLogcat(ggml_log_level nivel, const char *texto, void *) {
    if (nivel == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", texto);
    } else if (nivel == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "%s", texto);
    }
}

// El texto de Whisper es UTF-8, pero puede traer un caracter partido entre dos
// tokens. Por eso se devuelve en bytes y no con NewStringUTF, que aborta la app
// con UTF-8 invalido: Kotlin lo decodifica sustituyendo lo que no sea valido.
void anadirTextoJson(std::string &out, const char *texto) {
    out += '"';
    for (const char *p = texto; *p; ++p) {
        const auto c = static_cast<unsigned char>(*p);
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (c < 0x20) {
                    char escape[8];
                    snprintf(escape, sizeof(escape), "\\u%04x", c);
                    out += escape;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    out += '"';
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_delta_aeria_1nexus_1prototype_data_transcript_WhisperNativo_abrir(
        JNIEnv *env, jobject, jstring rutaModelo) {
    whisper_log_set(alLogcat, nullptr);
    const char *ruta = env->GetStringUTFChars(rutaModelo, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(ruta, params);
    env->ReleaseStringUTFChars(rutaModelo, ruta);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_delta_aeria_1nexus_1prototype_data_transcript_WhisperNativo_cerrar(
        JNIEnv *, jobject, jlong ctx) {
    if (ctx != 0) whisper_free(reinterpret_cast<whisper_context *>(ctx));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_delta_aeria_1nexus_1prototype_data_transcript_WhisperNativo_version(
        JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_version());
}

/**
 * Transcribe [pcm] (16 kHz, mono, float) y devuelve JSON en UTF-8:
 *
 *   {"language":"es","probability":0.91,"segments":[[t0,t1,"texto"],...]}
 *
 * con t0/t1 en centesimas de segundo desde el inicio del trozo. Devuelve null si
 * whisper falla.
 *
 * El idioma se detecta aparte, antes de transcribir, porque whisper_full solo
 * devuelve el idioma ganador y el contrato pide tambien su probabilidad. Despues
 * se transcribe con ese idioma fijado, para no detectarlo dos veces.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_delta_aeria_1nexus_1prototype_data_transcript_WhisperNativo_transcribir(
        JNIEnv *env, jobject, jlong ctxLong, jfloatArray pcm, jint hilos) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxLong);
    if (ctx == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(pcm);
    std::vector<float> muestras(n);
    env->GetFloatArrayRegion(pcm, 0, n, muestras.data());

    std::vector<float> probabilidades(whisper_lang_max_id() + 1, 0.0f);
    int idioma = -1;
    if (whisper_pcm_to_mel(ctx, muestras.data(), n, hilos) == 0) {
        idioma = whisper_lang_auto_detect(ctx, 0, hilos, probabilidades.data());
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = hilos;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.translate = false;
    params.language = idioma >= 0 ? whisper_lang_str(idioma) : "auto";
    params.detect_language = false;
    // Sin esto Whisper rellena el ruido con "[Musica]", "(aplausos)"...
    params.suppress_nst = true;

    if (whisper_full(ctx, params, muestras.data(), n) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full ha fallado");
        return nullptr;
    }

    std::string json = "{\"language\":";
    if (idioma >= 0) {
        anadirTextoJson(json, whisper_lang_str(idioma));
        json += ",\"probability\":" + std::to_string(probabilidades[idioma]);
    } else {
        json += "null,\"probability\":null";
    }
    json += ",\"segments\":[";
    const int segmentos = whisper_full_n_segments(ctx);
    for (int i = 0; i < segmentos; ++i) {
        if (i > 0) json += ',';
        json += '[' + std::to_string(whisper_full_get_segment_t0(ctx, i)) + ','
                    + std::to_string(whisper_full_get_segment_t1(ctx, i)) + ',';
        anadirTextoJson(json, whisper_full_get_segment_text(ctx, i));
        json += ']';
    }
    json += "]}";

    jbyteArray resultado = env->NewByteArray(static_cast<jsize>(json.size()));
    env->SetByteArrayRegion(resultado, 0, static_cast<jsize>(json.size()),
                            reinterpret_cast<const jbyte *>(json.data()));
    return resultado;
}
