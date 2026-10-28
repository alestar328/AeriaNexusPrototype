package com.delta.aeria_nexus_prototype.data.red

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * GET en http a un aparato de la red local (el visor de la bodycam W1). Es la unica
 * excepcion a HTTPS de la app y vive aqui sola para que se vea (COM-004).
 *
 * Va con un socket y no con HttpURLConnection a proposito: network_security_config
 * prohibe el http en release, y no deja abrir una excepcion por subred, solo por
 * dominio o IP fija. La IP de la W1 la da su STATUS y cambia con la wifi. A cambio
 * aqui solo se acepta una IPv4 privada: nunca sale a internet en claro.
 *
 * El servidor de la W1 es NanoHTTPD, que manda el stream troceado (chunked).
 */
class HttpEnRedLocal private constructor(
    private val socket: Socket,
    /** Cuerpo de la respuesta, ya sin cabeceras ni troceado. */
    val cuerpo: InputStream,
) : Closeable {

    override fun close() {
        try { socket.close() } catch (_: IOException) {}
    }

    companion object {

        /** Abre `GET http://ip:puerto/ruta`. Null si la IP no es local o no contesta 200. */
        fun get(ip: String, puerto: Int, ruta: String, conectarMillis: Int, leerMillis: Int): HttpEnRedLocal? {
            if (!esIpPrivada(ip)) return null
            return conectar(ip, puerto, ruta, conectarMillis, leerMillis)
        }

        /** [get] sin mirar la IP. Solo para las pruebas, que usan el loopback. */
        internal fun conectar(ip: String, puerto: Int, ruta: String, conectarMillis: Int, leerMillis: Int): HttpEnRedLocal? {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(ip, puerto), conectarMillis)
                socket.soTimeout = leerMillis
                val peticion = "GET $ruta HTTP/1.1\r\nHost: $ip:$puerto\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(peticion.toByteArray(Charsets.US_ASCII))
                val entrada = BufferedInputStream(socket.getInputStream())
                // "HTTP/1.1 200 OK"
                val estado = leerLinea(entrada)?.split(' ')?.getOrNull(1)
                var troceado = false
                while (true) {
                    val cabecera = leerLinea(entrada) ?: break
                    if (cabecera.isEmpty()) break
                    if (cabecera.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                        cabecera.contains("chunked", ignoreCase = true)
                    ) {
                        troceado = true
                    }
                }
                if (estado != "200") {
                    socket.close()
                    return null
                }
                val cuerpo = if (troceado) BufferedInputStream(EntradaTroceada(entrada)) else entrada
                return HttpEnRedLocal(socket, cuerpo)
            } catch (e: IOException) {
                socket.close()
                throw e
            }
        }

        /**
         * Solo una IPv4 literal de red privada (10/8, 172.16/12, 192.168/16) o de enlace
         * local. Con un nombre no se resuelve nada: no hay DNS que engañar.
         */
        fun esIpPrivada(ip: String): Boolean {
            if (!ip.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return false
            val direccion = runCatching { InetAddress.getByName(ip) }.getOrNull() as? Inet4Address
                ?: return false
            return direccion.isSiteLocalAddress || direccion.isLinkLocalAddress
        }

        /** Linea terminada en \n (sin el \r\n), o null si el stream acabo. */
        fun leerLinea(entrada: InputStream): String? {
            val linea = StringBuilder()
            while (true) {
                val b = entrada.read()
                if (b < 0) return null
                if (b == '\n'.code) return linea.toString().trimEnd('\r')
                linea.append(b.toChar())
            }
        }
    }

    /** Quita el troceado HTTP: cada trozo es "tamaño en hex\r\n datos \r\n"; el de 0 cierra. */
    private class EntradaTroceada(private val entrada: InputStream) : InputStream() {
        private var quedanEnElTrozo = 0
        private var terminado = false

        override fun read(): Int {
            val uno = ByteArray(1)
            return if (read(uno, 0, 1) < 0) -1 else uno[0].toInt() and 0xFF
        }

        override fun read(destino: ByteArray, desde: Int, cuantos: Int): Int {
            if (terminado) return -1
            if (quedanEnElTrozo == 0) {
                val tamano = leerLinea(entrada)?.substringBefore(';')?.trim()?.toIntOrNull(16)
                if (tamano == null || tamano == 0) {
                    terminado = true
                    return -1
                }
                quedanEnElTrozo = tamano
            }
            val leidos = entrada.read(destino, desde, minOf(cuantos, quedanEnElTrozo))
            if (leidos < 0) {
                terminado = true
                return -1
            }
            quedanEnElTrozo -= leidos
            // El \r\n que cierra cada trozo.
            if (quedanEnElTrozo == 0) leerLinea(entrada)
            return leidos
        }
    }
}
