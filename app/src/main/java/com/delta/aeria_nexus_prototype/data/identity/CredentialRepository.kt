package com.delta.aeria_nexus_prototype.data.identity

import android.content.Context
import java.io.File
import java.security.cert.X509Certificate

/**
 * Credencial del agente: certificado propio, distinto del del telefono
 * (workflow 3).
 *
 * Lo que es del telefono esta aqui: generar el par de claves del agente (paso 6),
 * construir su peticion de certificado (7), hacer que el terminal la firme (8) e
 * instalar el certificado que devuelve la CA (11). El paso 13 es el PIN, que es el
 * workflow 4. La entrega y la emision van por [IamClient].
 *
 * POR QUE UN SEGUNDO PAR DE CLAVES, que es la pregunta que va a salir: porque el
 * documento de arquitectura lo pone en sus reglas de no equivalencia, "user
 * certificate is not the device certificate", y de ahi cuelga todo lo demas. Un
 * telefono no es una persona: si la misma clave sirviera para las dos cosas, dar
 * de baja al agente obligaria a retirar el terminal, prestar el terminal
 * prestaria la identidad, y la evidencia no podria decir quien la grabo, solo
 * con que aparato. Con dos claves, cada una se revoca por su lado (IAM-05).
 *
 * QUE FALTA: el agente al que se pide la credencial todavia no lo elige ningun IAM,
 * lo pone [IdentityRepository] con el ejemplo del documento. Tiene que existir ya en
 * AeriaOne: el alta entrega credenciales, no crea usuarios.
 */
class CredentialRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("aeria_credential", Context.MODE_PRIVATE)

    /** Paso 6: par de claves del agente. Sin reto de atestacion, ver [ClaveEnKeystore]. */
    fun generarClave(): NivelClave {
        ClaveEnKeystore.agente.generarPar()
        return ClaveEnKeystore.agente.nivelDeLaClave()
    }

    /** Paso 7: peticion en PEM con el identificador del agente como nombre comun. */
    fun crearCsr(userId: String, tenant: String): String {
        val der = ClaveEnKeystore.agente.crearCsr(
            SujetoCsr(commonName = userId, organizationalUnit = tenant),
        )
        return Pkcs10.aPem(der)
    }

    /**
     * Paso 8: firma del TERMINAL sobre la peticion del agente.
     *
     * Es lo que prueba al backend que la solicitud sale de un telefono ya dado de
     * alta, y la mitad del paso 5 (atadura agente-dispositivo) que se hace desde
     * este lado. Se firman los bytes UTF-8 del PEM **tal cual se envia**, que es lo
     * que verifica AeriaOne: firmar el DER, o un PEM reformateado, da una firma
     * correcta que el backend rechaza.
     */
    fun firmarSolicitud(csrPem: String): ByteArray =
        ClaveEnKeystore.terminal.firmarReto(csrPem.toByteArray(Charsets.UTF_8))

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
        // Solicitudes que dejaba el alta por adb en telefonos de antes del backend.
        File(context.filesDir, "credential").deleteRecursively()
        prefs.edit().clear().apply()
    }

    private companion object {
        const val CLAVE_USER_ID = "user_id"
    }
}
