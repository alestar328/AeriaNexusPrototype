package com.delta.aeria_nexus_prototype.data.identity

import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del PKCS#10 escrito a mano (workflow 12, paso 11).
 *
 * Corren en la JVM, sin dispositivo: lo unico de Android en el camino es la clave
 * de Keystore, y para lo que se comprueba aqui da igual de donde salga la clave.
 *
 * La prueba fuerte es [csr_lo_valida_openssl], que deja el CSR en
 * `app/build/test-csr/` para pasarle `openssl req -verify`. Comprobar el DER
 * contra nuestro propio codificador seria circular; quien tiene que aceptar la
 * peticion es una CA, asi que quien tiene que dar el visto bueno es openssl.
 */
class Pkcs10Test {

    // ---- Codificador DER -------------------------------------------------

    @Test
    fun entero_cero_ocupa_un_byte() {
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x00), Der.entero(0))
    }

    @Test
    fun entero_con_bit_alto_lleva_cero_delante() {
        // 0x80 se leeria como negativo sin el relleno.
        assertArrayEquals(byteArrayOf(0x02, 0x02, 0x00, 0x80.toByte()), Der.entero(0x80))
    }

    @Test
    fun oid_empaqueta_los_dos_primeros_componentes() {
        // 2.5.4.3 (commonName): 2 * 40 + 5 = 85 = 0x55
        assertArrayEquals(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03), Der.oid("2.5.4.3"))
    }

    @Test
    fun oid_codifica_componentes_grandes_en_base_128() {
        // 1.2.840.10045.4.3.2 (ecdsa-with-SHA256). El 840 y el 10045 no caben en
        // siete bits y se parten en grupos con el bit alto marcado.
        val esperado = byteArrayOf(
            0x06, 0x08,
            0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02,
        )
        assertArrayEquals(esperado, Der.oid("1.2.840.10045.4.3.2"))
    }

    @Test
    fun longitud_larga_a_partir_de_128_bytes() {
        val contenido = ByteArray(200)
        val codificado = Der.tlv(0x04, contenido)
        // 0x81 = "viene un byte de longitud", y 200 = 0xC8.
        assertEquals(0x04.toByte(), codificado[0])
        assertEquals(0x81.toByte(), codificado[1])
        assertEquals(0xC8.toByte(), codificado[2])
        assertEquals(203, codificado.size)
    }

    // ---- CSR -------------------------------------------------------------

    @Test
    fun la_firma_del_csr_cubre_el_bloque_de_informacion() {
        val par = generarParEc()
        val der = Pkcs10.crear(SUJETO, par.public, par.private)

        // Se releen los tres campos del CertificationRequest sin usar nuestro
        // codificador, para que la prueba no valide el codigo contra si mismo.
        val peticion = LectorDer(der).entrarEnSecuencia()
        val informacion = peticion.siguienteElementoCompleto()
        peticion.saltarElemento() // signatureAlgorithm
        val bits = peticion.siguienteContenido()
        val firma = bits.copyOfRange(1, bits.size) // el primer byte son bits sobrantes

        val valida = Signature.getInstance(Pkcs10.ALGORITMO_FIRMA).run {
            initVerify(par.public)
            update(informacion)
            verify(firma)
        }
        assertTrue("La firma no cubre el certificationRequestInfo tal cual se codifico", valida)
    }

    @Test
    fun el_pem_lleva_las_cabeceras_y_lineas_de_64() {
        val par = generarParEc()
        val pem = Pkcs10.aPem(Pkcs10.crear(SUJETO, par.public, par.private))
        val lineas = pem.trim().lines()

        assertEquals("-----BEGIN CERTIFICATE REQUEST-----", lineas.first())
        assertEquals("-----END CERTIFICATE REQUEST-----", lineas.last())
        lineas.drop(1).dropLast(2).forEach { assertEquals(64, it.length) }
    }

    /**
     * Deja un CSR real en disco para validarlo desde fuera:
     *
     *     openssl req -in app/build/test-csr/device.csr.pem -verify -noout -text
     *
     * No es una asercion: es el material de la comprobacion que si lo es.
     */
    @Test
    fun csr_lo_valida_openssl() {
        val par = generarParEc()
        val pem = Pkcs10.aPem(Pkcs10.crear(SUJETO, par.public, par.private))
        val destino = File("build/test-csr").apply { mkdirs() }.resolve("device.csr.pem")
        destino.writeText(pem)
        assertTrue(destino.length() > 0)
    }

    private fun generarParEc() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private companion object {
        val SUJETO = SujetoCsr(commonName = "DEV-92A71C", organizationalUnit = "QPD")
    }
}

/**
 * Lector DER de usar y tirar, solo para las pruebas. Recorre elementos "tipo,
 * longitud, valor" sin interpretarlos.
 */
private class LectorDer(private val datos: ByteArray, private var posicion: Int = 0) {

    fun entrarEnSecuencia(): LectorDer {
        val inicio = inicioDelContenido()
        val fin = inicio + longitud()
        return LectorDer(datos.copyOfRange(inicio, fin))
    }

    /** El elemento entero, con su etiqueta y su longitud: lo que se firma. */
    fun siguienteElementoCompleto(): ByteArray {
        val inicio = posicion
        val fin = inicioDelContenido() + longitud()
        posicion = fin
        return datos.copyOfRange(inicio, fin)
    }

    fun siguienteContenido(): ByteArray {
        val inicio = inicioDelContenido()
        val fin = inicio + longitud()
        posicion = fin
        return datos.copyOfRange(inicio, fin)
    }

    fun saltarElemento() {
        siguienteElementoCompleto()
    }

    /** Devuelve donde empieza el contenido del elemento que hay en [posicion]. */
    private fun inicioDelContenido(): Int {
        val primerByteDeLongitud = datos[posicion + 1].toInt() and 0xFF
        return if (primerByteDeLongitud < 0x80) {
            posicion + 2
        } else {
            posicion + 2 + (primerByteDeLongitud and 0x7F)
        }
    }

    private fun longitud(): Int {
        val primerByteDeLongitud = datos[posicion + 1].toInt() and 0xFF
        if (primerByteDeLongitud < 0x80) return primerByteDeLongitud
        var total = 0
        repeat(primerByteDeLongitud and 0x7F) { indice ->
            total = (total shl 8) or (datos[posicion + 2 + indice].toInt() and 0xFF)
        }
        return total
    }
}
