package com.delta.aeria_nexus_prototype.data.identity

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emparejamiento autenticado con la bodycam (workflow 31).
 *
 * Se prueba el intercambio ENTERO en la JVM, con los dos extremos hablando entre
 * si. El transporte real es un socket RFCOMM, pero el protocolo no depende de el:
 * son lineas de texto, y eso permite comprobar aqui lo unico que importa de
 * verdad, que es que un impostor no pase.
 *
 * Las pruebas negativas son el motivo de que este fichero exista. Que el caso
 * bueno funcione no dice nada: lo que hay que demostrar es que la reflexion, el
 * cambio de nonce y un certificado de otra CA fallan.
 */
class EmparejamientoBodycamTest {

    @Test
    fun el_intercambio_completo_autentica_a_las_dos_partes() {
        val escenario = Escenario()
        val resultado = escenario.emparejar()

        assertTrue("El emparejamiento honesto deberia salir: $resultado",
            resultado is ResultadoEmparejamiento.Autenticado)
        assertEquals(BWC_ID, (resultado as ResultadoEmparejamiento.Autenticado).bwcId)
        assertTrue("La bodycam no acepto al telefono", escenario.bodycam.aceptoAlTelefono)
    }

    /**
     * Ataque de reflexion: la bodycam devuelve la firma que acaba de recibir del
     * telefono. Sin el rol dentro de la transcripcion esto pasaria, porque los dos
     * extremos firmarian exactamente los mismos bytes.
     */
    @Test
    fun devolver_la_firma_del_telefono_no_cuela_como_firma_de_la_bodycam() {
        val escenario = Escenario(bodycamDevuelveLaFirmaRecibida = true)
        val resultado = escenario.emparejar()

        assertTrue("Una firma reflejada paso por buena: $resultado",
            resultado is ResultadoEmparejamiento.Rechazado)
    }

    /**
     * La bodycam firma una transcripcion con OTRO nonce del telefono. Es lo que
     * haria quien reutilizase una respuesta capturada de una sesion anterior.
     */
    @Test
    fun una_firma_sobre_otro_nonce_no_vale() {
        val escenario = Escenario(bodycamFirmaOtroNonce = true)
        val resultado = escenario.emparejar()

        assertTrue("Una firma de otra sesion paso por buena: $resultado",
            resultado is ResultadoEmparejamiento.Rechazado)
    }

    /** Una camara con clave propia pero certificado que no emitio AeriaOne. */
    @Test
    fun una_bodycam_de_otra_ca_no_se_acepta() {
        val escenario = Escenario(bodycamDeOtraCa = true)
        val resultado = escenario.emparejar()

        assertTrue("Se acepto una bodycam ajena a la CA: $resultado",
            resultado is ResultadoEmparejamiento.Rechazado)
    }

    /**
     * Sin el workflow 13 no hay ancla de perifericos. El emparejamiento no puede
     * completarse, y eso se distingue de un rechazo: la camara no miente, es que
     * todavia no hay con que comprobarla.
     */
    @Test
    fun sin_ancla_de_confianza_no_hay_emparejamiento_posible() {
        val escenario = Escenario(telefonoSinAncla = true)
        val resultado = escenario.emparejar()

        assertTrue("Sin ancla deberia ser NoSoportado: $resultado",
            resultado is ResultadoEmparejamiento.NoSoportado)
    }

    @Test
    fun la_transcripcion_de_cada_rol_es_distinta() {
        val nonceA = ProtocoloEmparejamiento.nonce()
        val nonceB = ProtocoloEmparejamiento.nonce()
        val delTelefono = ProtocoloEmparejamiento.transcripcion(
            ProtocoloEmparejamiento.VERSION_2, ProtocoloEmparejamiento.Rol.TELEFONO, DEVICE_ID, BWC_ID, nonceA, nonceB,
        )
        val deLaBodycam = ProtocoloEmparejamiento.transcripcion(
            ProtocoloEmparejamiento.VERSION_2, ProtocoloEmparejamiento.Rol.BODYCAM, DEVICE_ID, BWC_ID, nonceA, nonceB,
        )
        assertTrue("Los dos roles firman lo mismo", !delTelefono.contentEquals(deLaBodycam))
    }

    /**
     * La v1 tiene que seguir firmando exactamente lo de antes: la bodycam la acepta
     * para telefonos viejos, y un byte distinto los dejaria a todos fuera.
     */
    @Test
    fun la_transcripcion_v1_no_cambia() {
        val nonceA = ByteArray(32) { 1 }
        val nonceB = ByteArray(32) { 2 }
        val v1 = ProtocoloEmparejamiento.transcripcion(
            ProtocoloEmparejamiento.VERSION_1, ProtocoloEmparejamiento.Rol.TELEFONO, DEVICE_ID, BWC_ID, nonceA, nonceB,
        )
        val b64 = Base64.getEncoder()
        val esperada = "AERIA-BWC-1|TELEFONO|$DEVICE_ID|$BWC_ID|${b64.encodeToString(nonceA)}|${b64.encodeToString(nonceB)}"
        assertEquals(esperada, String(v1, Charsets.UTF_8))
    }

    // ── Canal cifrado (v2) ────────────────────────────────────────────────────

    /** Lo que cifra un extremo lo descifra el otro, en los dos sentidos y en orden. */
    @Test
    fun el_canal_acordado_funciona_en_los_dos_sentidos() {
        val escenario = Escenario()
        val telefono = (escenario.emparejar() as ResultadoEmparejamiento.Autenticado).canal
        val bodycam = escenario.bodycam.canal!!

        assertEquals("STATUS", bodycam.descifrar(telefono.cifrar("STATUS")))
        assertEquals("TOKEN:jwt:600", bodycam.descifrar(telefono.cifrar("TOKEN:jwt:600")))
        assertEquals("OK:STATUS", telefono.descifrar(bodycam.cifrar("OK:STATUS")))
    }

    /**
     * Alguien en medio cambia la clave efimera del telefono por la suya. Si las
     * efimeras no fueran dentro de lo firmado, los dos extremos se autenticarian y
     * el intermediario leeria todo. Tienen que fallar las dos firmas.
     */
    @Test
    fun cambiar_la_clave_efimera_por_el_camino_no_cuela() {
        val escenario = Escenario(intermediarioCambiaLaEfimera = true)
        val resultado = escenario.emparejar()

        assertTrue("Un intermediario coloco su clave efimera: $resultado",
            resultado is ResultadoEmparejamiento.Rechazado)
        assertTrue("La bodycam acepto un saludo manipulado", !escenario.bodycam.aceptoAlTelefono)
    }

    /** Una respuesta sin clave efimera a un saludo v2 es intentar que no se cifre. */
    @Test
    fun una_bodycam_que_no_manda_clave_efimera_no_se_acepta() {
        val escenario = Escenario(bodycamSinEfimera = true)
        val resultado = escenario.emparejar()

        assertTrue("Se acepto un AUTH_ID sin clave efimera: $resultado",
            resultado is ResultadoEmparejamiento.Rechazado)
    }

    /** Una trama repetida o fuera de orden no descifra, y el canal no se recupera. */
    @Test
    fun una_trama_repetida_o_reordenada_rompe_el_canal() {
        val escenario = Escenario()
        val telefono = (escenario.emparejar() as ResultadoEmparejamiento.Autenticado).canal
        val bodycam = escenario.bodycam.canal!!

        val primera = telefono.cifrar("REC_START")
        val segunda = telefono.cifrar("REC_STOP")
        assertEquals("REC_START", bodycam.descifrar(primera))
        assertNull("Se acepto una trama repetida", bodycam.descifrar(primera))
        assertNull("El canal siguio vivo tras una trama mala", bodycam.descifrar(segunda))
        assertTrue(bodycam.roto)
    }

    /** Una trama del telefono devuelta al telefono no pasa por mensaje de la camara. */
    @Test
    fun devolver_una_trama_a_quien_la_mando_no_descifra() {
        val escenario = Escenario()
        val telefono = (escenario.emparejar() as ResultadoEmparejamiento.Autenticado).canal

        assertNull("Una trama reflejada descifro", telefono.descifrar(telefono.cifrar("STATUS")))
    }

    /** Un bit cambiado en la trama se detecta: GCM autentica, no solo cifra. */
    @Test
    fun una_trama_manipulada_no_descifra() {
        val escenario = Escenario()
        val telefono = (escenario.emparejar() as ResultadoEmparejamiento.Autenticado).canal
        val bodycam = escenario.bodycam.canal!!

        val trama = telefono.cifrar("REC_STOP")
        val bytes = Base64.getDecoder().decode(trama.removePrefix(CanalCifrado.PREFIJO))
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        assertNull(bodycam.descifrar(CanalCifrado.PREFIJO + Base64.getEncoder().encodeToString(bytes)))
    }

    private companion object {
        const val DEVICE_ID = "DEV-74435826"
        const val BWC_ID = "BWC-0042"
    }
}

/** Monta las dos puntas y las hace hablar. */
private class Escenario(
    bodycamDevuelveLaFirmaRecibida: Boolean = false,
    bodycamFirmaOtroNonce: Boolean = false,
    bodycamDeOtraCa: Boolean = false,
    telefonoSinAncla: Boolean = false,
    bodycamSinEfimera: Boolean = false,
    private val intermediarioCambiaLaEfimera: Boolean = false,
) {
    private val caDePerifericos = Ca("AeriaOne Peripheral CA test")
    private val otraCa = Ca("Alguien Else CA")

    private val parDelTelefono = generarParEc()
    private val certificadoDelTelefono = caDePerifericos.emitir("DEV-74435826", parDelTelefono)

    val bodycam = BodycamDeReferencia(
        bwcId = "BWC-0042",
        deviceIdEsperado = "DEV-74435826",
        ca = if (bodycamDeOtraCa) otraCa else caDePerifericos,
        anclaParaValidarAlTelefono = caDePerifericos.certificado,
        devuelveLaFirmaRecibida = bodycamDevuelveLaFirmaRecibida,
        firmaOtroNonce = bodycamFirmaOtroNonce,
        sinEfimera = bodycamSinEfimera,
    )

    private val telefono = EmparejamientoDelTelefono(
        deviceId = "DEV-74435826",
        certificadoDelTelefono = certificadoDelTelefono,
        firmar = { datos -> firmar(datos, parDelTelefono.private) },
        anclaDePerifericos = if (telefonoSinAncla) null else caDePerifericos.certificado,
    )

    fun emparejar(): ResultadoEmparejamiento {
        var saludo = telefono.saludo()
        if (intermediarioCambiaLaEfimera) {
            val partes = saludo.split(":").toMutableList()
            partes[4] = AcuerdoDeClaves.codificar(AcuerdoDeClaves.parEfimero().public)
            saludo = partes.joinToString(":")
        }
        val identidad = bodycam.responderASaludo(saludo)
        val prueba = telefono.responderA(identidad)
            ?: return ResultadoEmparejamiento.Rechazado("AUTH_ID ilegible")
        return telefono.comprobar(bodycam.responderAPrueba(prueba))
    }
}

/**
 * Implementacion de referencia del lado de la BODYCAM, en v2.
 *
 * Es el espejo de `Emparejamiento.kt` de BodyCamServer (otro repositorio): si se
 * cambia uno, se cambian los dos. Vive en el codigo de pruebas y no en el de la app
 * a proposito: el telefono nunca hace de bodycam, y meterlo en el APK seria codigo
 * muerto que ademas confundiria sobre quien responde que.
 *
 * Los interruptores son ataques, no configuracion.
 */
private class BodycamDeReferencia(
    private val bwcId: String,
    private val deviceIdEsperado: String,
    ca: Ca,
    private val anclaParaValidarAlTelefono: X509Certificate,
    private val devuelveLaFirmaRecibida: Boolean,
    private val firmaOtroNonce: Boolean,
    private val sinEfimera: Boolean,
) {
    private val par = generarParEc()
    private val certificado = ca.emitir(bwcId, par)
    private val nonce = ProtocoloEmparejamiento.nonce()
    private var nonceDelTelefono: ByteArray = ByteArray(0)
    private val efimera = AcuerdoDeClaves.parEfimero()
    private val efimeraPropia = AcuerdoDeClaves.codificar(efimera.public)
    private var efimeraDelTelefono = ""

    var aceptoAlTelefono = false
        private set

    /** El canal acordado, si el telefono se acredito. */
    var canal: CanalCifrado? = null
        private set

    fun responderASaludo(linea: String): String {
        val partes = linea.split(":")
        require(partes[0] == "AUTH_HELLO" && partes[1] == ProtocoloEmparejamiento.VERSION_2 && partes.size == 5)
        nonceDelTelefono = Base64.getDecoder().decode(partes[3])
        efimeraDelTelefono = partes[4]
        return (listOf(
            "AUTH_ID",
            bwcId,
            Base64.getEncoder().encodeToString(nonce),
            Base64.getEncoder().encodeToString(certificado.encoded),
        ) + if (sinEfimera) emptyList() else listOf(efimeraPropia)).joinToString(":")
    }

    fun responderAPrueba(linea: String): String {
        val partes = linea.split(":")
        val firmaDelTelefono = Base64.getDecoder().decode(partes[1])
        val certificadoDelTelefono = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(Base64.getDecoder().decode(partes[2]).inputStream()) as X509Certificate

        // La camara valida al telefono igual que el telefono la valida a ella: es
        // autenticacion MUTUA, no un login del telefono contra la camara.
        val delTelefono = ProtocoloEmparejamiento.transcripcion(
            version = ProtocoloEmparejamiento.VERSION_2,
            rol = ProtocoloEmparejamiento.Rol.TELEFONO,
            deviceId = deviceIdEsperado,
            bwcId = bwcId,
            nonceDelTelefono = nonceDelTelefono,
            nonceDeLaBodycam = nonce,
            efimeraDelTelefono = efimeraDelTelefono,
            efimeraDeLaBodycam = efimeraPropia,
        )
        val emitidoPorLaCa = runCatching {
            certificadoDelTelefono.verify(anclaParaValidarAlTelefono.publicKey)
            true
        }.getOrDefault(false)
        aceptoAlTelefono = emitidoPorLaCa &&
            ProtocoloEmparejamiento.verificar(firmaDelTelefono, delTelefono, certificadoDelTelefono)
        if (!aceptoAlTelefono) return "AUTH_FAIL:el telefono no demostro su identidad"

        if (devuelveLaFirmaRecibida) {
            return "AUTH_OK:" + Base64.getEncoder().encodeToString(firmaDelTelefono)
        }

        val nonceQueFirma = if (firmaOtroNonce) ProtocoloEmparejamiento.nonce() else nonceDelTelefono
        val mia = ProtocoloEmparejamiento.transcripcion(
            version = ProtocoloEmparejamiento.VERSION_2,
            rol = ProtocoloEmparejamiento.Rol.BODYCAM,
            deviceId = deviceIdEsperado,
            bwcId = bwcId,
            nonceDelTelefono = nonceQueFirma,
            nonceDeLaBodycam = nonce,
            efimeraDelTelefono = efimeraDelTelefono,
            efimeraDeLaBodycam = efimeraPropia,
        )
        canal = CanalCifrado.derivar(
            secreto = AcuerdoDeClaves.secreto(efimera.private, AcuerdoDeClaves.leer(efimeraDelTelefono)!!),
            nonceDelTelefono = nonceDelTelefono,
            nonceDeLaBodycam = nonce,
            soyTelefono = false,
        )
        return "AUTH_OK:" + Base64.getEncoder().encodeToString(firmar(mia, par.private))
    }
}

/** CA de juguete: solo emite y firma, que es lo unico que hace falta aqui. */
private class Ca(nombre: String) {
    private val par = generarParEc()
    val certificado: X509Certificate = autofirmar(nombre, par)

    fun emitir(commonName: String, del: KeyPair): X509Certificate =
        certificadoX509(commonName, del.public.encoded, par.private, emisor = certificado.subjectX500Principal.name)
}

private fun generarParEc(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
    initialize(ECGenParameterSpec("secp256r1"))
}.generateKeyPair()

private fun firmar(datos: ByteArray, privada: PrivateKey): ByteArray =
    Signature.getInstance(ProtocoloEmparejamiento.ALGORITMO_FIRMA).run {
        initSign(privada)
        update(datos)
        sign()
    }

private fun autofirmar(nombre: String, par: KeyPair): X509Certificate =
    certificadoX509(nombre, par.public.encoded, par.private, emisor = "CN=$nombre")

/**
 * Certificado X.509 minimo, escrito a mano con el mismo [Der] del PKCS#10.
 *
 * Se hace aqui y no con BouncyCastle por la misma razon que el CSR: la regla de
 * peso de la app vale tambien para sus pruebas, y lo que hace falta es un
 * certificado que la plataforma sepa leer y verificar, no una biblioteca entera.
 */
private fun certificadoX509(
    commonName: String,
    clavePublicaDer: ByteArray,
    firmanteDeLaCa: PrivateKey,
    emisor: String,
): X509Certificate {
    val ahora = System.currentTimeMillis()
    val tbs = Der.secuencia(
        contextoExplicito(0, Der.entero(2)), // version v3
        Der.entero((ahora and 0x7FFFFFFF).toInt()), // numero de serie
        Der.secuencia(Der.oid(OID_ECDSA_SHA256)),
        nombreX500(emisor.removePrefix("CN=")),
        Der.secuencia(
            tiempoUtc(Date(ahora - 60_000)),
            tiempoUtc(Date(ahora + 86_400_000)),
        ),
        nombreX500(commonName),
        clavePublicaDer,
    )
    val der = Der.secuencia(
        tbs,
        Der.secuencia(Der.oid(OID_ECDSA_SHA256)),
        Der.bitString(firmar(tbs, firmanteDeLaCa)),
    )
    return java.security.cert.CertificateFactory.getInstance("X.509")
        .generateCertificate(der.inputStream()) as X509Certificate
}

private fun nombreX500(commonName: String): ByteArray =
    Der.secuencia(Der.conjunto(Der.secuencia(Der.oid("2.5.4.3"), Der.utf8(commonName))))

private const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"

/**
 * Etiqueta de contexto EXPLICITA `[n]`: envuelve un elemento completo, a
 * diferencia de la implicita que ya tiene [Der] para el CSR. Solo la usa el
 * campo de version del certificado, y por eso vive aqui y no en produccion.
 */
private fun contextoExplicito(numero: Int, contenido: ByteArray): ByteArray =
    Der.tlv(0xA0 or numero, contenido)

/** UTCTime: dos digitos de ano y sufijo Z, que es lo que espera X.509. */
private fun tiempoUtc(fecha: Date): ByteArray {
    val formato = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
    return Der.tlv(0x17, formato.format(fecha).toByteArray(Charsets.US_ASCII))
}
