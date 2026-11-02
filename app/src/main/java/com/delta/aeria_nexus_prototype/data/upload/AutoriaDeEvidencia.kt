package com.delta.aeria_nexus_prototype.data.upload

import org.json.JSONObject

/**
 * Quien grabo una pieza, sellado al guardarla en el telefono y no al subirla
 * (upload-protocol.md §5.1, iam-session.md §9.6).
 *
 * La subida puede salir horas despues y con otro agente en el telefono: si la autoria
 * se leyera al subir, lo que grabo un agente dado de baja se atribuiria al siguiente.
 * El backend acepta el `officer_user_id` declarado aunque ese agente ya este revocado.
 *
 * Se guarda dentro de los datos que ya acompanan a cada pieza (`.evidence.json`,
 * `.proxy.json`, `.data.json` y el de los manifiestos), con las mismas claves que
 * llevan en Upload-Metadata.
 */
data class AutoriaDeEvidencia(val userId: String, val placa: String?) {

    fun sellarEn(datos: JSONObject): JSONObject {
        datos.put(CLAVE_USER_ID, userId)
        placa?.let { datos.put(CLAVE_PLACA, it) }
        return datos
    }

    companion object {
        private const val CLAVE_USER_ID = "officer_user_id"
        private const val CLAVE_PLACA = "officer_code"

        /** Null en lo guardado antes del 2026-11-02, que no llevaba autoria. */
        fun leerDe(datos: JSONObject?): AutoriaDeEvidencia? {
            val userId = datos?.optString(CLAVE_USER_ID).orEmpty()
            if (userId.isBlank()) return null
            return AutoriaDeEvidencia(userId, datos?.optString(CLAVE_PLACA)?.ifBlank { null })
        }

        /**
         * Lo que va en Upload-Metadata. Sin autoria sellada (piezas anteriores a este
         * cambio) se manda como antes: solo la placa del agente de ahora, si se conoce,
         * y el backend atribuye por la sesion.
         */
        fun metadata(sellada: AutoriaDeEvidencia?, placaActual: String?): Map<String, String> = buildMap {
            sellada?.let { put(CLAVE_USER_ID, it.userId) }
            val placa = if (sellada != null) sellada.placa else placaActual
            placa?.takeIf { it.isNotBlank() }?.let { put(CLAVE_PLACA, it) }
        }
    }
}
