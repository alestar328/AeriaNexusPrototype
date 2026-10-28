package com.delta.aeria_nexus_prototype.data.identity

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONObject

/** Los datos del agente tal como los tiene AeriaOne. Lo que no aplica llega vacio, nunca null. */
data class PerfilRecibido(
    val nombre: String,
    val rango: String,
    val placa: String,
    val unidad: String,
)

/**
 * Nombre, rango y placa del agente, entregados por AeriaOne (iam-session.md §5.3).
 *
 * Llegan en el alta del agente y en cada apertura de sesion; se guarda siempre el
 * ultimo, asi una correccion hecha en el panel llega en el siguiente desbloqueo. Se
 * guardan en disco porque el agente puede desbloquear sin cobertura y la camara tiene
 * que rotular igualmente quien la lleva.
 *
 * En noBackupFilesDir por lo mismo que la instancia de la app: la copia de seguridad
 * no debe llevar los datos de un agente a otro telefono.
 */
class PerfilDelAgente(context: Context) {

    private val fichero = File(context.noBackupFilesDir, "officer_profile.json")

    @Volatile
    private var enMemoria: PerfilRecibido? = leerDeDisco()

    /** El ultimo perfil recibido, o null si AeriaOne todavia no ha entregado ninguno. */
    fun actual(): PerfilRecibido? = enMemoria

    fun guardar(perfil: PerfilRecibido) {
        enMemoria = perfil
        val json = JSONObject()
            .put(CLAVE_NOMBRE, perfil.nombre)
            .put(CLAVE_RANGO, perfil.rango)
            .put(CLAVE_PLACA, perfil.placa)
            .put(CLAVE_UNIDAD, perfil.unidad)
        runCatching { fichero.writeText(json.toString()) }
            .onFailure { Log.e(TAG, "no se pudo guardar el perfil del agente: ${it.message}") }
    }

    /** Al deshacer el alta: el siguiente agente no debe heredar el nombre del anterior. */
    fun borrar() {
        enMemoria = null
        fichero.delete()
    }

    private fun leerDeDisco(): PerfilRecibido? = runCatching {
        if (!fichero.isFile) return null
        desdeJson(JSONObject(fichero.readText()))
    }.getOrNull()

    companion object {
        private const val TAG = "PerfilDelAgente"

        // Los mismos nombres que usa el backend, para leer con una sola funcion la
        // respuesta de AeriaOne y el fichero guardado.
        private const val CLAVE_NOMBRE = "display_name"
        private const val CLAVE_RANGO = "rank"
        private const val CLAVE_PLACA = "badge"
        private const val CLAVE_UNIDAD = "unit"

        fun desdeJson(perfil: JSONObject) = PerfilRecibido(
            nombre = perfil.optString(CLAVE_NOMBRE),
            rango = perfil.optString(CLAVE_RANGO),
            placa = perfil.optString(CLAVE_PLACA),
            unidad = perfil.optString(CLAVE_UNIDAD),
        )
    }
}
