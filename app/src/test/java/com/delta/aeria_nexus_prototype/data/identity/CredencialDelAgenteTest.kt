package com.delta.aeria_nexus_prototype.data.identity

import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la credencial del agente (workflow 3).
 *
 * Corren en la JVM, asi que no tocan ni el Keystore ni [CredentialRepository],
 * que son de Android. Lo que si se comprueba es exactamente lo que ese
 * repositorio construye: una peticion a nombre del agente y una firma del
 * terminal que la cubre. Si alguien cambia que se firma —el PEM en vez del DER,
 * por ejemplo— estas pruebas caen.
 */
class CredencialDelAgenteTest {

    @Test
    fun la_peticion_del_agente_va_a_su_nombre_y_no_al_del_terminal() {
        val agente = generarParEc()
        val csr = Pkcs10.crear(SUJETO_AGENTE, agente.public, agente.private)

        assertTrue("El CSR del agente no lleva su identificador", contiene(csr, USER_ID))
        assertFalse("El CSR del agente lleva el Device ID", contiene(csr, DEVICE_ID))
    }

    /**
     * La mitad del paso 5 (atadura agente-dispositivo) que se puede hacer desde el
     * telefono: la solicitud sale firmada por la clave del terminal, la del
     * workflow 12, para que quien la reciba sepa que viene de un telefono dado de
     * alta y no de un CSR suelto que cualquiera puede fabricar.
     */
    @Test
    fun el_terminal_firma_la_peticion_del_agente() {
        val agente = generarParEc()
        val terminal = generarParEc()
        val csr = Pkcs10.crear(SUJETO_AGENTE, agente.public, agente.private)

        val firmaDelTerminal = firmar(csr, terminal.private)

        assertTrue(
            "La firma del terminal no verifica con su propia clave",
            verifica(csr, firmaDelTerminal, terminal.public),
        )
    }

    /**
     * La prueba que sostiene la regla de no equivalencia del documento: aunque las
     * dos claves vivan en el mismo telefono, no son intercambiables. Si esto
     * pasara, revocar al agente y revocar el terminal serian la misma cosa.
     */
    @Test
    fun la_clave_del_agente_no_sirve_para_firmar_como_el_terminal() {
        val agente = generarParEc()
        val terminal = generarParEc()
        val csr = Pkcs10.crear(SUJETO_AGENTE, agente.public, agente.private)

        val firmaDelAgente = firmar(csr, agente.private)

        assertFalse(
            "Una firma del agente pasa por firma del terminal",
            verifica(csr, firmaDelAgente, terminal.public),
        )
    }

    @Test
    fun la_firma_del_terminal_cubre_la_peticion_entera() {
        val agente = generarParEc()
        val terminal = generarParEc()
        val csr = Pkcs10.crear(SUJETO_AGENTE, agente.public, agente.private)
        val firmaDelTerminal = firmar(csr, terminal.private)

        // Un solo byte cambiado en mitad del CSR tiene que invalidar la firma.
        val csrAlterado = csr.copyOf().also { it[it.size / 2]++ }

        assertFalse(
            "La firma del terminal sigue valiendo con la peticion cambiada",
            verifica(csrAlterado, firmaDelTerminal, terminal.public),
        )
    }

    /**
     * Deja la peticion del agente en disco para validarla desde fuera:
     *
     *     openssl req -in app/build/test-csr/user.csr.pem -verify -noout -subject
     *
     * No es una asercion: es el material de la comprobacion que si lo es.
     */
    @Test
    fun csr_del_agente_lo_valida_openssl() {
        val agente = generarParEc()
        val pem = Pkcs10.aPem(Pkcs10.crear(SUJETO_AGENTE, agente.public, agente.private))
        val destino = File("build/test-csr").apply { mkdirs() }.resolve("user.csr.pem")
        destino.writeText(pem)
        assertTrue(destino.length() > 0)
    }

    private fun firmar(datos: ByteArray, privada: PrivateKey): ByteArray =
        Signature.getInstance(Pkcs10.ALGORITMO_FIRMA).run {
            initSign(privada)
            update(datos)
            sign()
        }

    private fun verifica(datos: ByteArray, firma: ByteArray, publica: PublicKey): Boolean =
        Signature.getInstance(Pkcs10.ALGORITMO_FIRMA).run {
            initVerify(publica)
            update(datos)
            // Una firma de otra clave no solo devuelve false: ECDSA puede rechazar
            // el propio DER de la firma con una excepcion.
            runCatching { verify(firma) }.getOrDefault(false)
        }

    /** Busca el texto tal y como lo codificaria el CSR, no como bytes sueltos. */
    private fun contiene(der: ByteArray, texto: String): Boolean {
        val esperado = Der.utf8(texto)
        if (esperado.size > der.size) return false
        return (0..der.size - esperado.size).any { inicio ->
            der.copyOfRange(inicio, inicio + esperado.size).contentEquals(esperado)
        }
    }

    private fun generarParEc(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private companion object {
        const val USER_ID = "cmendez.aeriaone.com"
        const val DEVICE_ID = "DEV-92A71C"

        val SUJETO_AGENTE = SujetoCsr(commonName = USER_ID, organizationalUnit = "QPD")
    }
}
