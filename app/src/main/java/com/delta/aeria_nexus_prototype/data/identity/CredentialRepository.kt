package com.delta.aeria_nexus_prototype.data.identity

import android.content.Context
import java.io.File
import java.security.cert.X509Certificate
import java.util.Base64
import org.json.JSONObject

/**
 * Credencial del agente: certificado propio, distinto del del telefono
 * (workflow 3).
 *
 * De los 16 pasos del catalogo, 5 son nuestros y estan aqui: generar el par de
 * claves del agente (6), construir su peticion de certificado (7), entregarla
 * (8), instalar el certificado que devuelva la CA (11) y demostrar posesion de la
 * clave (14). El paso 13 es el PIN, que es el workflow 4. Los otros 10 son del
 * backend.
 *
 * POR QUE UN SEGUNDO PAR DE CLAVES, que es la pregunta que va a salir: porque el
 * documento de arquitectura lo pone en sus reglas de no equivalencia, "user
 * certificate is not the device certificate", y de ahi cuelga todo lo demas. Un
 * telefono no es una persona: si la misma clave sirviera para las dos cosas, dar
 * de baja al agente obligaria a retirar el terminal, prestar el terminal
 * prestaria la identidad, y la evidencia no podria decir quien la grabo, solo
 * con que aparato. Con dos claves, cada una se revoca por su lado (IAM-05).
 *
 * QUE FALTA, dicho sin rodeos: no hay backend. Los pasos 1 a 5, 9, 10, 12, 15 y
 * 16 no ocurren. En particular la identidad del agente no la emite ningun IAM,
 * la pone [IdentityRepository] con el ejemplo del documento; el paso 5 (atadura
 * agente-telefono) lo apuntamos en la solicitud pero no lo registra nadie; y el
 * reto de la prueba de posesion se lo inventa el propio telefono, asi que
 * demuestra que las dos mitades del par se corresponden, pero no frescura.
 *
 * Lo que SI es real: la clave del agente vive en el Keystore, no sale de alli, y
 * no es la misma que la del terminal.
 */
class CredentialRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("aeria_credential", Context.MODE_PRIVATE)
    private val carpeta: File get() = File(context.filesDir, "credential").apply { mkdirs() }

    /** Paso 6: par de claves del agente. Sin reto de atestacion, ver [ClaveEnKeystore]. */
    fun generarClave(): NivelClave {
        ClaveEnKeystore.agente.generarPar()
        return ClaveEnKeystore.agente.nivelDeLaClave()
    }

    /** Paso 7: peticion con el identificador del agente como nombre comun. */
    fun crearCsr(userId: String, tenant: String): ByteArray =
        ClaveEnKeystore.agente.crearCsr(
            SujetoCsr(commonName = userId, organizationalUnit = tenant),
        )

    /**
     * Paso 8: entrega de la peticion, firmada ademas por el terminal.
     *
     * Sin canal al backend, "entregar" es dejarla en la carpeta privada de la app
     * para poder sacarla con `adb`. Pero el paso 8 del catalogo dice que la
     * peticion viaja "through the authenticated AeriaOne backend together with the
     * relevant enrollment context", y ese contexto es justo lo que se pierde al
     * dejar un fichero suelto: cualquiera podria presentar un CSR de agente.
     *
     * Por eso el terminal firma el DER del CSR del agente con SU clave, la del
     * workflow 12. Asi la solicitud lleva dentro la prueba de que salio de un
     * telefono ya dado de alta, que es la mitad del paso 5 (atadura
     * agente-dispositivo) que se puede hacer desde este lado.
     *
     * ATENCION: el formato de este fichero es PROPUESTA NUESTRA. La
     * especificacion de API es uno de los diez artefactos de diseno que el propio
     * documento reconoce pendientes; cuando llegue, esto se sustituye.
     */
    fun entregarSolicitud(
        csrDelAgente: ByteArray,
        userId: String,
        tenant: String,
        deviceId: String,
    ): File {
        val firmaDelTerminal = ClaveEnKeystore.terminal.firmarReto(csrDelAgente)
        val base64 = Base64.getEncoder()

        val solicitud = JSONObject().apply {
            put("version", 1)
            put("userId", userId)
            put("tenant", tenant)
            put("deviceId", deviceId)
            put("csr", Pkcs10.aPem(csrDelAgente))
            put("deviceSignature", base64.encodeToString(firmaDelTerminal))
            put("signatureAlgorithm", Pkcs10.ALGORITMO_FIRMA)
        }

        // Ademas del JSON se deja el CSR suelto en PEM: es lo que come `openssl`
        // sin tener que extraerlo antes de un campo.
        File(carpeta, FICHERO_CSR).writeText(Pkcs10.aPem(csrDelAgente))
        return File(carpeta, FICHERO_SOLICITUD).apply { writeText(solicitud.toString(2)) }
    }

    fun solicitudGuardada(): String? =
        File(carpeta, FICHERO_SOLICITUD).takeIf { it.exists() }?.readText()

    /**
     * Paso 11: instalar el certificado del agente y su cadena.
     *
     * Se comprueba ademas que el nombre comun del certificado es el agente que
     * pedimos. Sin esa comprobacion, una CA mal configurada podria devolver un
     * certificado a nombre de otro y el telefono lo aceptaria sin rechistar.
     */
    fun instalarCertificado(pemDeLaCadena: String, userIdEsperado: String): X509Certificate {
        val delAgente = ClaveEnKeystore.agente.instalarCertificadoEmitido(pemDeLaCadena)
        val nombreComun = ClaveEnKeystore.agente.commonNameDelCertificado()
        require(nombreComun == userIdEsperado) {
            "El certificado viene a nombre de $nombreComun y no de $userIdEsperado"
        }
        prefs.edit().putString(CLAVE_USER_ID, userIdEsperado).apply()
        return delAgente
    }

    /**
     * Paso 14: prueba de posesion durante el ALTA, que ocurre antes de que exista
     * ningun PIN. Por eso es la unica firma con la clave del agente que no pasa por
     * la autorizacion de abajo: en ese momento no hay nada con que autorizarla.
     */
    fun pruebaDePosesionDelAlta(reto: ByteArray): ByteArray =
        ClaveEnKeystore.agente.firmarReto(reto)

    /**
     * Autorizacion de uso de la clave del agente (workflow 27, paso 8).
     *
     * Vive en memoria y muere con el proceso, que es lo que se quiere: si la app se
     * cierra, la autorizacion no sobrevive y hay que volver a poner el PIN.
     *
     * HASTA DONDE LLEGA ESTO, para no venderlo por mas de lo que es. No es una
     * barrera criptografica: la clave del Keystore es utilizable por el proceso
     * pase lo que pase, y quien ejecute codigo dentro de la app puede saltarse este
     * booleano. Lo que si garantiza es estructural y no es poco: **ningun camino
     * del codigo puede firmar como el agente sin que se haya introducido el PIN en
     * esta sesion**, ni por descuido nuestro ni desde un servicio en segundo plano.
     * La barrera de verdad —atar la clave a una autenticacion del sistema— es la
     * decision D2, que sigue abierta.
     */
    private var usoAutorizado = false

    val puedeFirmarComoElAgente: Boolean get() = usoAutorizado

    /** Solo lo llama el desbloqueo, y solo tras verificar el PIN. */
    fun autorizarUso() {
        usoAutorizado = true
    }

    /** Cierre de sesion, bloqueo o corte: la clave deja de poder usarse. */
    fun retirarAutorizacion() {
        usoAutorizado = false
    }

    /**
     * Paso 12: firma el reto del backend con la clave del agente.
     *
     * Falla en vez de firmar si nadie ha puesto el PIN. Que sea un error y no un
     * `null` es deliberado: llegar aqui sin autorizacion es un fallo de
     * programacion nuestro, no una situacion que el agente pueda provocar.
     */
    fun firmarRetoDeSesion(reto: ByteArray): ByteArray {
        check(usoAutorizado) {
            "La clave del agente no esta autorizada: no se ha verificado el PIN en esta sesion"
        }
        return ClaveEnKeystore.agente.firmarReto(reto)
    }

    /** Agente cuyo certificado esta instalado, o null si aun no hay credencial. */
    fun agenteProvisionado(): String? = prefs.getString(CLAVE_USER_ID, null)

    /** §13: destruir la identidad local, no solo olvidarla. */
    fun borrarCredencial() {
        retirarAutorizacion()
        ClaveEnKeystore.agente.borrar()
        carpeta.deleteRecursively()
        prefs.edit().clear().apply()
    }

    private companion object {
        const val CLAVE_USER_ID = "user_id"
        const val FICHERO_CSR = "user.csr.pem"
        const val FICHERO_SOLICITUD = "user.request.json"
    }
}
