package com.delta.aeria_nexus_prototype.data.audit

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El diario de auditoria tiene que delatar cualquier manipulacion que no sea cortar
 * el final. Cada prueba es un ataque concreto contra un diario valido de cinco eventos.
 */
class CadenaDeAuditoriaTest {

    private val cadena = CadenaDeAuditoria(hmac("clave-de-prueba"))

    private fun hmac(clave: String): (ByteArray) -> ByteArray = { datos ->
        Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(clave.toByteArray(), "HmacSHA256")) }
            .doFinal(datos)
    }

    private fun diario(cadena: CadenaDeAuditoria = this.cadena, eventos: Int = 5): List<String> {
        val lineas = mutableListOf<String>()
        var anterior = CadenaDeAuditoria.GENESIS
        for (orden in 1..eventos) {
            val json = JsonPlano.objeto(listOf("tipo" to "PIN_WRONG", "intentos" to "${5 - orden}"))
            val linea = cadena.linea(orden.toLong(), anterior, json)
            lineas += linea
            anterior = CadenaDeAuditoria.macDe(linea)!!
        }
        return lineas
    }

    @Test
    fun `un diario sin tocar esta intacto`() {
        val resultado = cadena.verificar(diario())
        assertTrue(resultado.intacta)
        assertEquals(5, resultado.eventos)
    }

    @Test
    fun `cambiar un dato de un evento se detecta en ese evento`() {
        val lineas = diario().toMutableList()
        lineas[2] = lineas[2].replace("PIN_WRONG", "SESSION_OPENED")
        val resultado = cadena.verificar(lineas)
        assertFalse(resultado.intacta)
        assertEquals(3L, resultado.rotaEn)
    }

    @Test
    fun `borrar un evento de en medio se detecta`() {
        val lineas = diario().toMutableList().apply { removeAt(1) }
        assertEquals(2L, cadena.verificar(lineas).rotaEn)
    }

    @Test
    fun `reordenar dos eventos se detecta`() {
        val lineas = diario().toMutableList()
        val segundo = lineas[1]
        lineas[1] = lineas[2]
        lineas[2] = segundo
        assertFalse(cadena.verificar(lineas).intacta)
    }

    @Test
    fun `renumerar para tapar un borrado no basta sin la clave`() {
        val lineas = diario().toMutableList().apply { removeAt(1) }
        // Se corrige el orden y el enlace a mano, pero la mac no se puede recalcular.
        val partes = lineas[1].split(CadenaDeAuditoria.SEP).toMutableList()
        partes[0] = "2"
        partes[1] = CadenaDeAuditoria.macDe(lineas[0])!!
        lineas[1] = partes.joinToString(CadenaDeAuditoria.SEP)
        assertEquals(2L, cadena.verificar(lineas).rotaEn)
    }

    @Test
    fun `una cadena rehecha con otra clave no pasa`() {
        val falsificado = diario(CadenaDeAuditoria(hmac("clave-del-atacante")))
        assertEquals(1L, cadena.verificar(falsificado).rotaEn)
    }

    @Test
    fun `cortar el final NO se detecta, y la prueba lo deja escrito`() {
        // Limite conocido y documentado: solo lo ve un testigo externo (el backend).
        assertTrue(cadena.verificar(diario().dropLast(2)).intacta)
    }

    @Test
    fun `un tabulador dentro de un valor no rompe el formato`() {
        val json = JsonPlano.objeto(listOf("detalle" to "con\ttab y \"comillas\""))
        val linea = cadena.linea(1, CadenaDeAuditoria.GENESIS, json)
        assertTrue(cadena.verificar(listOf(linea)).intacta)
    }
}
