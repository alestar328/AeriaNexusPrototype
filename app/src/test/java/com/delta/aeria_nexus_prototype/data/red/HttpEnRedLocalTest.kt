package com.delta.aeria_nexus_prototype.data.red

import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpEnRedLocalTest {

    @Test
    fun soloAceptaIpv4Privadas() {
        assertTrue(HttpEnRedLocal.esIpPrivada("192.168.0.14"))
        assertTrue(HttpEnRedLocal.esIpPrivada("10.1.2.3"))
        assertTrue(HttpEnRedLocal.esIpPrivada("172.20.0.1"))
        assertFalse(HttpEnRedLocal.esIpPrivada("8.8.8.8"))
        assertFalse(HttpEnRedLocal.esIpPrivada("172.32.0.1"))
        assertFalse(HttpEnRedLocal.esIpPrivada("bodycam.local"))
        assertFalse(HttpEnRedLocal.esIpPrivada(""))
    }

    // Con una IP publica ni siquiera intenta conectar: vuelve al momento.
    @Test
    fun ipPublicaNoConecta() {
        assertNull(HttpEnRedLocal.get("8.8.8.8", 80, "/", 100, 100))
    }

    @Test
    fun quitaElTroceadoDeNanoHttpd() {
        val servidor = ServerSocket(0)
        thread {
            servidor.accept().use { cliente ->
                val entrada = cliente.getInputStream().bufferedReader()
                while (entrada.readLine().isNotEmpty()) Unit
                val salida = cliente.getOutputStream()
                salida.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace\r\n" +
                        "Transfer-Encoding: chunked\r\n\r\n" +
                        "5\r\nhola \r\n" + "6\r\nmundo!\r\n" + "0\r\n\r\n").toByteArray(),
                )
                salida.flush()
            }
        }
        val respuesta = HttpEnRedLocal.conectar("127.0.0.1", servidor.localPort, "/preview/stream", 1000, 1000)
        val texto = respuesta!!.use { it.cuerpo.readBytes().toString(Charsets.US_ASCII) }
        assertEquals("hola mundo!", texto)
        servidor.close()
    }
}
