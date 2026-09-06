package com.delta.aeria_nexus_prototype.data.identity

import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

/**
 * Atadura temporal entre el agente y un periferico (workflows 33 y 34).
 *
 * QUE RESUELVE, que es lo menos evidente de todo el modelo IAM. La camara graba,
 * pero la evidencia tiene que poder decir **quien** la grabo, no solo con que
 * aparato. La forma facil de conseguirlo seria darle a la camara la identidad del
 * agente, y el documento la prohibe explicitamente: "a peripheral binding does not
 * copy the officer identity to the peripheral", y en la capa de evidencia "the
 * capture device signs as itself; user attribution comes through the validated
 * session and binding, not device impersonation of the officer".
 *
 * Asi que la atadura es una **declaracion firmada por el agente** que dice: yo,
 * cmendez.aeriaone.com, autorizo a BWC-896E a operar en mi nombre desde este
 * telefono hasta tal hora. La camara la guarda y la presenta; NO puede firmar como
 * el agente, porque nunca ha tenido su clave. Si alguien pregunta el dia 15 por que
 * no se le da la identidad a la camara, la respuesta esta aqui: perder la camara
 * obligaria a revocar al agente.
 *
 * POR QUE CADUCA. El catalogo lo pide en el workflow 34: la atadura termina al fin
 * de turno, al cerrar sesion, por tiempo, por reasignacion o por revocacion. Una
 * atadura sin caducidad convierte un descuido —dejar la camara en el coche— en un
 * permiso indefinido.
 *
 * QUE ES PROPUESTA NUESTRA: el formato de esta declaracion y las 12 horas por
 * defecto. El modelo de datos del registro de relaciones es uno de los artefactos
 * de diseno que el propio documento reconoce pendientes.
 */
object BindingPeriferico {

    const val VERSION = "AERIA-BIND-1"

    /**
     * Lo que dura una atadura si nadie la corta antes.
     *
     * Doce horas cubren un turno largo con su relevo sin que el agente tenga que
     * volver a emparejar a mitad. Es un numero nuestro: la politica de duracion
     * por rol la tiene que fijar ciberseguridad.
     */
    const val VALIDEZ_POR_DEFECTO_MILLIS = 12L * 60 * 60 * 1000

    /** Por que se deshizo una atadura. Son los cinco motivos del workflow 34. */
    enum class MotivoDeFin {
        FIN_DE_TURNO,
        CIERRE_DE_SESION,
        CADUCIDAD,
        REASIGNACION,
        REVOCACION,
    }

    fun nuevoId(): String {
        val aleatorio = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return "BIND-" + aleatorio.joinToString("") { "%02X".format(it) }
    }

    /**
     * La declaracion que firma el agente, en JSON canonico.
     *
     * Lleva dentro **el nonce de la sesion de emparejamiento** ([nonceDeLaSesion]),
     * y eso no es decoracion: sin el, una atadura capturada de ayer se podria
     * presentar hoy en una sesion nueva. Atarla a la sesion la hace tan efimera
     * como el emparejamiento que la respalda.
     *
     * El orden de las claves importa: se firma el texto tal cual, asi que los dos
     * extremos tienen que construirlo igual byte a byte. Por eso se compone a mano
     * y no con un serializador que pueda reordenar.
     */
    fun declaracion(
        bindingId: String,
        userId: String,
        deviceId: String,
        bwcId: String,
        tenant: String,
        nonceDeLaSesion: ByteArray,
        emitidoEn: Long,
        caducaEn: Long,
    ): String = buildString {
        append("{")
        append("\"v\":\"").append(VERSION).append("\",")
        append("\"binding_id\":\"").append(bindingId).append("\",")
        append("\"user_id\":\"").append(userId).append("\",")
        append("\"device_id\":\"").append(deviceId).append("\",")
        append("\"peripheral_id\":\"").append(bwcId).append("\",")
        append("\"tenant\":\"").append(tenant).append("\",")
        append("\"session_nonce\":\"").append(Base64.getEncoder().encodeToString(nonceDeLaSesion)).append("\",")
        append("\"issued_at\":").append(emitidoEn).append(",")
        append("\"expires_at\":").append(caducaEn)
        append("}")
    }

    /**
     * Lo que firma el agente para deshacer la atadura (workflow 34).
     *
     * Va firmado igual que la creacion: si cualquiera pudiera deshacerla, bastaria
     * con acercarse a la camara para dejar al agente sin atribucion en mitad de un
     * incidente.
     */
    fun declaracionDeFin(bindingId: String, motivo: MotivoDeFin, momento: Long): String =
        "{\"v\":\"$VERSION\",\"binding_id\":\"$bindingId\",\"reason\":\"${motivo.name}\",\"at\":$momento}"

    /** Lee una declaracion para inspeccionarla. No valida nada: eso lo hace quien la recibe. */
    fun leer(json: String): Declaracion? = runCatching {
        val objeto = JSONObject(json)
        Declaracion(
            bindingId = objeto.getString("binding_id"),
            userId = objeto.getString("user_id"),
            deviceId = objeto.getString("device_id"),
            bwcId = objeto.getString("peripheral_id"),
            tenant = objeto.getString("tenant"),
            nonceDeLaSesion = Base64.getDecoder().decode(objeto.getString("session_nonce")),
            emitidoEn = objeto.getLong("issued_at"),
            caducaEn = objeto.getLong("expires_at"),
        )
    }.getOrNull()

    data class Declaracion(
        val bindingId: String,
        val userId: String,
        val deviceId: String,
        val bwcId: String,
        val tenant: String,
        val nonceDeLaSesion: ByteArray,
        val emitidoEn: Long,
        val caducaEn: Long,
    ) {
        fun caducada(ahora: Long = System.currentTimeMillis()): Boolean = ahora > caducaEn

        override fun equals(other: Any?): Boolean = other is Declaracion && bindingId == other.bindingId
        override fun hashCode(): Int = bindingId.hashCode()
    }
}

/** Atadura viva en el telefono, para que la interfaz pueda decir a quien sirve la camara. */
data class BindingActivo(
    val bindingId: String,
    val userId: String,
    val bwcId: String,
    val caducaEn: Long,
) {
    fun caducado(ahora: Long = System.currentTimeMillis()): Boolean = ahora > caducaEn
}
