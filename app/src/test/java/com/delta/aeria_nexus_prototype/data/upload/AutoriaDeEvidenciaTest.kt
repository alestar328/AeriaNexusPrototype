package com.delta.aeria_nexus_prototype.data.upload

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoriaDeEvidenciaTest {

    @Test
    fun `lo sellado se lee igual`() {
        val datos = AutoriaDeEvidencia("agente.uno", "PL-101").sellarEn(JSONObject().put("type", "VIDEO"))
        assertEquals(AutoriaDeEvidencia("agente.uno", "PL-101"), AutoriaDeEvidencia.leerDe(datos))
    }

    @Test
    fun `datos de antes del cambio no tienen autoria`() {
        assertNull(AutoriaDeEvidencia.leerDe(JSONObject().put("type", "VIDEO")))
        assertNull(AutoriaDeEvidencia.leerDe(null))
    }

    @Test
    fun `con autoria sellada manda la del que grabo y no la del agente de ahora`() {
        val metadata = AutoriaDeEvidencia.metadata(
            sellada = AutoriaDeEvidencia("agente.uno", "PL-101"),
            placaActual = "PL-202",
        )
        assertEquals(mapOf("officer_user_id" to "agente.uno", "officer_code" to "PL-101"), metadata)
    }

    @Test
    fun `sin autoria sellada se manda como antes, solo la placa de ahora`() {
        assertEquals(mapOf("officer_code" to "PL-202"), AutoriaDeEvidencia.metadata(null, "PL-202"))
        assertEquals(emptyMap<String, String>(), AutoriaDeEvidencia.metadata(null, null))
    }

    @Test
    fun `sellada sin placa no hereda la del agente de ahora`() {
        val metadata = AutoriaDeEvidencia.metadata(AutoriaDeEvidencia("agente.uno", null), "PL-202")
        assertEquals(mapOf("officer_user_id" to "agente.uno"), metadata)
    }
}
