package com.delta.aeria_nexus_prototype.data.identity

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El QR de alta (iam-session.md §4.4). Lo que importa es la comprobacion del
 * servidor: el hash de la CA no basta, porque el certificado de la CA es publico y
 * un impostor puede servir el autentico. Lo que no puede falsificar es la firma.
 */
class InvitacionDeAltaTest {

    private val caDeTerminales = CaDePrueba("AeriaOne Device CA")
    private val caDeAgentes = CaDePrueba("AeriaOne User CA")
    private val invitacion = invitacionPara(caDeTerminales, caDeAgentes)

    @Test
    fun acepta_el_certificado_firmado_por_la_ca_del_qr() {
        val terminal = caDeTerminales.emitir("DEV-8DB2C628", parEc())
        invitacion.comprobarCaDeTerminales(caDeTerminales.pem, pem(terminal))
    }

    @Test
    fun acepta_la_cadena_que_termina_en_la_propia_ca() {
        val agente = caDeAgentes.emitir("rdiaz.aeriaone.com", parEc())
        invitacion.comprobarCaDeAgentes(caDeAgentes.pem, pem(agente) + "\n" + caDeAgentes.pem)
    }

    @Test
    fun rechaza_una_ca_distinta_de_la_del_qr() {
        val otra = CaDePrueba("Impostor CA")
        val terminal = otra.emitir("DEV-8DB2C628", parEc())
        assertThrows(ServidorNoVerificado::class.java) {
            invitacion.comprobarCaDeTerminales(otra.pem, pem(terminal))
        }
    }

    /** El caso por el que existe la comprobacion de firma (revision del 18-oct). */
    @Test
    fun rechaza_al_impostor_que_sirve_la_ca_autentica_pero_firma_con_la_suya() {
        val impostor = CaDePrueba("AeriaOne Device CA")
        val terminal = impostor.emitir("DEV-8DB2C628", parEc())
        assertThrows(ServidorNoVerificado::class.java) {
            invitacion.comprobarCaDeTerminales(caDeTerminales.pem, pem(terminal))
        }
    }

    @Test
    fun rechaza_si_el_servidor_no_manda_la_ca() {
        val agente = caDeAgentes.emitir("rdiaz.aeriaone.com", parEc())
        assertThrows(ServidorNoVerificado::class.java) {
            invitacion.comprobarCaDeAgentes(null, pem(agente))
        }
    }

    @Test
    fun no_confunde_la_ca_de_agentes_con_la_de_terminales() {
        val terminal = caDeTerminales.emitir("DEV-8DB2C628", parEc())
        assertThrows(ServidorNoVerificado::class.java) {
            invitacion.comprobarCaDeAgentes(caDeTerminales.pem, pem(terminal))
        }
    }

    @Test
    fun lee_el_qr_del_panel_y_completa_las_barras() {
        val texto = """{"v":"AERIA-ENROLL-1","tenant":"QPD","api_url":"http://192.168.0.18:8000/api",""" +
            """"base_url":"http://192.168.0.18:8000/files/","user_id":"rdiaz.aeriaone.com",""" +
            """"code":"K7Q2-M9XD-4TPA-W3HN","expires_at":1792152000000,""" +
            """"device_ca_sha256":"9F2CE1","user_ca_sha256":"41ab07"}"""
        val leida = InvitacionDeAlta.desdeQr(texto)
        assertEquals("http://192.168.0.18:8000/api/", leida.apiUrl)
        assertEquals("rdiaz.aeriaone.com", leida.userId)
        assertEquals("K7Q2-M9XD-4TPA-W3HN", leida.codigo)
        assertEquals("9f2ce1", leida.huellaCaDeTerminales)
    }

    @Test
    fun rechaza_otra_version_y_lo_que_no_es_json() {
        assertThrows(QrNoValido::class.java) { InvitacionDeAlta.desdeQr("""{"v":"AERIA-ENROLL-2"}""") }
        assertThrows(QrNoValido::class.java) { InvitacionDeAlta.desdeQr("https://example.com") }
    }

    /** Lo que se guarda entre las dos fases: sin codigo tras el 201, y se relee igual. */
    @Test
    fun sobrevive_al_guardado_sin_codigo() {
        val gastada = invitacion.copy(codigo = null)
        val releida = InvitacionDeAlta.desdeJson(gastada.aJson())
        assertNull(releida?.codigo)
        assertEquals(gastada, releida)
        assertTrue(releida!!.esLaCaDeTerminales(caDeTerminales.pem))
    }
}

private fun invitacionPara(terminales: CaDePrueba, agentes: CaDePrueba) = InvitacionDeAlta(
    tenant = "QPD",
    apiUrl = "https://nexus.example/api/",
    baseUrl = "https://nexus.example/files/",
    userId = "rdiaz.aeriaone.com",
    codigo = "K7Q2-M9XD-4TPA-W3HN",
    caducaEnMillis = 1_792_152_000_000,
    huellaCaDeTerminales = InvitacionDeAlta.huellaDe(terminales.pem),
    huellaCaDeAgentes = InvitacionDeAlta.huellaDe(agentes.pem),
)

/** CA de juguete: se autofirma y emite. Mismo enfoque que la de EmparejamientoBodycamTest. */
private class CaDePrueba(private val nombre: String) {
    private val par = parEc()
    val certificado: X509Certificate = x509(nombre, par.public.encoded, par.private, nombre)
    val pem: String = pem(certificado)

    fun emitir(commonName: String, del: KeyPair): X509Certificate =
        x509(commonName, del.public.encoded, par.private, nombre)
}

private fun parEc(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
    initialize(ECGenParameterSpec("secp256r1"))
}.generateKeyPair()

private fun pem(certificado: X509Certificate): String =
    "-----BEGIN CERTIFICATE-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificado.encoded) +
        "\n-----END CERTIFICATE-----\n"

private fun x509(commonName: String, publicaDer: ByteArray, firmante: PrivateKey, emisor: String): X509Certificate {
    val ahora = System.currentTimeMillis()
    val algoritmo = Der.secuencia(Der.oid("1.2.840.10045.4.3.2"))
    val tbs = Der.secuencia(
        Der.tlv(0xA0, Der.entero(2)),
        Der.entero((ahora and 0x7FFFFFFF).toInt()),
        algoritmo,
        nombre(emisor),
        Der.secuencia(utc(Date(ahora - 60_000)), utc(Date(ahora + 86_400_000))),
        nombre(commonName),
        publicaDer,
    )
    val firma = Signature.getInstance("SHA256withECDSA").run {
        initSign(firmante)
        update(tbs)
        sign()
    }
    val der = Der.secuencia(tbs, algoritmo, Der.bitString(firma))
    return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
}

private fun nombre(commonName: String): ByteArray =
    Der.secuencia(Der.conjunto(Der.secuencia(Der.oid("2.5.4.3"), Der.utf8(commonName))))

private fun utc(fecha: Date): ByteArray {
    val formato = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
    formato.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return Der.tlv(0x17, formato.format(fecha).toByteArray(Charsets.US_ASCII))
}
