package com.delta.aeria_nexus_prototype.data.red

import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pinning contra servidores reales: necesita un telefono con internet.
 * letsencrypt.org lleva certificado de Let's Encrypt; google.com, de Google Trust
 * Services, que Android acepta pero el pin no.
 */
@RunWith(AndroidJUnit4::class)
class ConexionBackendTest {

    @Test
    fun aceptaUnServidorDeLetsEncrypt() {
        val conexion = ConexionBackend.abrir("https://letsencrypt.org/")
        try {
            assertEquals(200, conexion.responseCode)
        } finally {
            conexion.disconnect()
        }
    }

    @Test
    fun rechazaOtraCaAunqueAndroidLaAcepte() {
        val conexion = ConexionBackend.abrir("https://www.google.com/")
        try {
            conexion.responseCode
            fail("google.com no deberia pasar el pin")
        } catch (_: SSLHandshakeException) {
            // Lo esperado.
        } finally {
            conexion.disconnect()
        }
    }
}
