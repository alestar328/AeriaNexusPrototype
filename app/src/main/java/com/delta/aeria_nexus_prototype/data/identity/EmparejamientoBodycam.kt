package com.delta.aeria_nexus_prototype.data.identity

import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Emparejamiento autenticado con la bodycam (workflow 31, pasos 1 a 3).
 *
 * QUE ARREGLA. El canal con la W1 es hoy un RFCOMM sin cifrado de enlace y con
 * un UUID fijo publicado en la documentacion: **cualquiera que conozca ese UUID
 * habla con la camara**, le manda `REC_STOP` o le pide `STATUS`, y la camara no
 * tiene forma de saber que no somos nosotros. Al reves tampoco: el telefono se
 * conecta a la primera MAC que responda a ese UUID y se fia. Es la deuda mas
 * grave del repositorio y la unica que no depende de que exista un backend,
 * porque las dos puntas son nuestras.
 *
 * COMO. Cuatro lineas sobre el mismo canal de texto que ya existe:
 *
 *     telefono -> bodycam   AUTH_HELLO:<version>:<deviceId>:<nonceA>
 *     bodycam  -> telefono  AUTH_ID:<bwcId>:<nonceB>:<certificado>
 *     telefono -> bodycam   AUTH_PROOF:<firma>:<certificado>
 *     bodycam  -> telefono  AUTH_OK:<firma>          o  AUTH_FAIL:<motivo>
 *
 * Cada lado firma la MISMA transcripcion con su propia clave, y ahi esta todo lo
 * que hace que esto valga algo. Ver [transcripcion].
 *
 * QUE NO HACE, para no venderlo por mas de lo que es:
 *
 *  - **No cifra el canal.** Autentica quien habla, no oculta lo que dice. Cifrarlo
 *    necesita un acuerdo de claves efimero, y las claves que tenemos son de firma
 *    (`PURPOSE_SIGN`), asi que no sirven para derivar un secreto compartido. Es la
 *    parte que queda del paso 6 del catalogo.
 *  - **No hay identidad de la bodycam todavia**: es el workflow 13. Hasta que la
 *    W1 tenga su par de claves y su certificado, este intercambio no puede
 *    completarse contra el hardware real, y el enlace queda marcado como NO
 *    autenticado en vez de fingir que lo esta.
 */
object ProtocoloEmparejamiento {

    /**
     * Version del protocolo, dentro de lo que se firma.
     *
     * Va firmada para que no se pueda negociar a la baja: si manana hay una
     * version 2 con cifrado, nadie puede convencer a un extremo de volver a la 1
     * cambiando la linea del saludo, porque la firma no cuadraria.
     */
    const val VERSION = "AERIA-BWC-1"

    const val ALGORITMO_FIRMA = Pkcs10.ALGORITMO_FIRMA

    /** Longitud del nonce. 32 bytes de [SecureRandom] no se repiten en la practica. */
    const val NONCE_BYTES = 32

    /** Quien firma. Cada rol firma una transcripcion distinta, ver [transcripcion]. */
    enum class Rol { TELEFONO, BODYCAM }

    /**
     * Lo que firma cada extremo. Cada trozo esta por un motivo concreto:
     *
     *  - **la version**, para que no se pueda negociar a la baja;
     *  - **el rol**, para que la firma del telefono no se pueda presentar como la
     *    de la bodycam devolviendosela tal cual (ataque de reflexion);
     *  - **los dos identificadores**, para que una prueba valida entre otro
     *    telefono y otra camara no sirva aqui;
     *  - **los dos nonces**, para que ninguno de los dos extremos pueda decidir
     *    por su cuenta lo que se va a firmar y precalcularlo.
     *
     * Quitar cualquiera de los cuatro rompe una de esas cuatro cosas, y por eso
     * esto no se toca sin volver a leer esta lista.
     */
    fun transcripcion(
        rol: Rol,
        deviceId: String,
        bwcId: String,
        nonceDelTelefono: ByteArray,
        nonceDeLaBodycam: ByteArray,
    ): ByteArray {
        val codificador = Base64.getEncoder()
        return listOf(
            VERSION,
            rol.name,
            deviceId,
            bwcId,
            codificador.encodeToString(nonceDelTelefono),
            codificador.encodeToString(nonceDeLaBodycam),
        ).joinToString("|").toByteArray(Charsets.UTF_8)
    }

    fun nonce(): ByteArray = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }

    fun verificar(
        firma: ByteArray,
        transcripcion: ByteArray,
        certificado: X509Certificate,
    ): Boolean = try {
        Signature.getInstance(ALGORITMO_FIRMA).run {
            initVerify(certificado.publicKey)
            update(transcripcion)
            verify(firma)
        }
    } catch (e: Exception) {
        // Una firma de otra clave no solo devuelve false: ECDSA puede rechazar el
        // propio DER de la firma con una excepcion.
        false
    }
}

/** Como acabo el emparejamiento. */
sealed interface ResultadoEmparejamiento {
    /**
     * La bodycam demostro ser quien dice, y nosotros tambien ante ella.
     *
     * Se devuelve tambien el nonce de la camara porque la atadura agente-camara
     * (workflow 33) va firmada sobre el: eso la hace tan efimera como el
     * emparejamiento que la respalda, en vez de un permiso reutilizable.
     */
    data class Autenticado(
        val bwcId: String,
        val sujeto: String,
        val nonceDeLaSesion: ByteArray,
    ) : ResultadoEmparejamiento {
        override fun equals(other: Any?): Boolean = other is Autenticado && bwcId == other.bwcId
        override fun hashCode(): Int = bwcId.hashCode()
    }

    /** El intercambio se completo y algo no cuadro. El enlace NO es de fiar. */
    data class Rechazado(val motivo: String) : ResultadoEmparejamiento

    /**
     * La bodycam no entiende el protocolo: firmware viejo o sin el workflow 13.
     * Se distingue del rechazo a proposito: no es que mienta, es que no sabe.
     */
    data class NoSoportado(val motivo: String) : ResultadoEmparejamiento
}

/**
 * Lado del TELEFONO del intercambio (workflow 31).
 *
 * No sabe nada de Bluetooth: recibe lineas y devuelve lineas. Eso permite
 * probarlo entero contra el otro extremo sin hardware, que es como esta probado
 * (ver `EmparejamientoBodycamTest`), y deja el transporte donde ya estaba.
 *
 * El responsable del otro lado esta escrito como implementacion de referencia en
 * el codigo de pruebas: **es lo que hay que portar a BodyCamServer**, que vive en
 * otro repositorio.
 */
class EmparejamientoDelTelefono(
    private val deviceId: String,
    private val certificadoDelTelefono: X509Certificate,
    private val firmar: (ByteArray) -> ByteArray,
    /**
     * Ancla con la que se valida el certificado que presente la bodycam. Cuando
     * exista el workflow 13 sera la CA de perifericos de AeriaOne; hasta entonces
     * puede no haber ninguna, y sin ancla no hay emparejamiento posible.
     */
    private val anclaDePerifericos: X509Certificate?,
) {

    private val nonceDelTelefono = ProtocoloEmparejamiento.nonce()
    private var nonceDeLaBodycam: ByteArray? = null
    private var bwcId: String? = null
    private var certificadoDeLaBodycam: X509Certificate? = null

    /** Primera linea: quien somos y nuestro nonce. */
    fun saludo(): String = listOf(
        "AUTH_HELLO",
        ProtocoloEmparejamiento.VERSION,
        deviceId,
        codificar(nonceDelTelefono),
    ).joinToString(":")

    /**
     * Segunda vuelta: se valida el certificado que presenta la bodycam y se
     * devuelve nuestra prueba. Null si la respuesta no es un AUTH_ID valido.
     */
    fun responderA(linea: String): String? {
        val partes = linea.split(":")
        if (partes.size != 4 || partes[0] != "AUTH_ID") return null

        val certificado = leerCertificado(partes[3]) ?: return null
        bwcId = partes[1]
        nonceDeLaBodycam = decodificar(partes[2]) ?: return null

        val prueba = firmar(
            ProtocoloEmparejamiento.transcripcion(
                rol = ProtocoloEmparejamiento.Rol.TELEFONO,
                deviceId = deviceId,
                bwcId = partes[1],
                nonceDelTelefono = nonceDelTelefono,
                nonceDeLaBodycam = nonceDeLaBodycam!!,
            ),
        )
        certificadoDeLaBodycam = certificado
        return listOf(
            "AUTH_PROOF",
            codificar(prueba),
            codificar(certificadoDelTelefono.encoded),
        ).joinToString(":")
    }

    /** Ultima vuelta: se comprueba la firma de la bodycam sobre la transcripcion. */
    fun comprobar(linea: String): ResultadoEmparejamiento {
        if (linea.startsWith("AUTH_FAIL")) {
            return ResultadoEmparejamiento.Rechazado(
                "La bodycam rechazo nuestra identidad: ${linea.substringAfter(':', "sin motivo")}",
            )
        }
        val partes = linea.split(":")
        if (partes.size != 2 || partes[0] != "AUTH_OK") {
            return ResultadoEmparejamiento.NoSoportado("Respuesta inesperada al emparejamiento")
        }

        val certificado = certificadoDeLaBodycam
            ?: return ResultadoEmparejamiento.Rechazado("La bodycam no presento certificado")
        val nonceB = nonceDeLaBodycam
            ?: return ResultadoEmparejamiento.Rechazado("Falta el nonce de la bodycam")
        val identificador = bwcId
            ?: return ResultadoEmparejamiento.Rechazado("La bodycam no dijo quien es")

        val ancla = anclaDePerifericos
            ?: return ResultadoEmparejamiento.NoSoportado(
                "Este telefono no tiene ancla de confianza para perifericos (workflow 13)",
            )
        if (!estaFirmadoPor(certificado, ancla)) {
            return ResultadoEmparejamiento.Rechazado("El certificado de la bodycam no lo emitio AeriaOne")
        }

        val firma = decodificar(partes[1])
            ?: return ResultadoEmparejamiento.Rechazado("Firma ilegible")
        val valida = ProtocoloEmparejamiento.verificar(
            firma = firma,
            transcripcion = ProtocoloEmparejamiento.transcripcion(
                rol = ProtocoloEmparejamiento.Rol.BODYCAM,
                deviceId = deviceId,
                bwcId = identificador,
                nonceDelTelefono = nonceDelTelefono,
                nonceDeLaBodycam = nonceB,
            ),
            certificado = certificado,
        )
        if (!valida) {
            return ResultadoEmparejamiento.Rechazado("La bodycam no pudo demostrar su clave")
        }

        return ResultadoEmparejamiento.Autenticado(
            bwcId = identificador,
            sujeto = certificado.subjectX500Principal.name,
            nonceDeLaSesion = nonceB,
        )
    }

    /**
     * Que el ancla emitio ese certificado. Se comprueba la firma, no el nombre:
     * comparar emisores por cadena de texto es lo que convierte una validacion en
     * un adorno.
     */
    private fun estaFirmadoPor(certificado: X509Certificate, ancla: X509Certificate): Boolean = try {
        certificado.verify(ancla.publicKey)
        certificado.checkValidity()
        true
    } catch (e: Exception) {
        false
    }

    private fun leerCertificado(base64: String): X509Certificate? =
        decodificar(base64)?.let { der ->
            runCatching {
                java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(der.inputStream()) as X509Certificate
            }.getOrNull()
        }

    private fun codificar(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decodificar(texto: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(texto) }.getOrNull()
}
