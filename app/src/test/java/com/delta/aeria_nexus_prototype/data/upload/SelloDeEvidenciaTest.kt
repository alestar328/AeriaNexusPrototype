package com.delta.aeria_nexus_prototype.data.upload

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelloDeEvidenciaTest {

    private val sello = SelloDeEvidencia(
        sha256Cipher = "aa".repeat(32),
        sha256Plain = "bb".repeat(32),
        kind = "evidence",
        mediaType = "video",
        incidentId = "INC-1",
        principal = "agente.uno",
        capturedAt = "2026-11-02T10:00:00Z",
    )

    @Test
    fun `lleva los campos del contrato y binding_id vacio`() {
        val json = JSONObject(sello.json("DEV-8DB2C628", "1a2b", "2026-11-02T10:05:00Z"))
        assertEquals("evidence-seal/1", json.getString("schema"))
        assertEquals("aa".repeat(32), json.getString("sha256_cipher"))
        assertEquals("DEV-8DB2C628", json.getString("device_id"))
        assertEquals("1a2b", json.getString("cert_serial"))
        assertEquals("agente.uno", json.getString("principal"))
        assertEquals("", json.getString("binding_id"))
        assertEquals("INC-1", json.getString("device_incident_id"))
        assertEquals("video", json.getString("media_type"))
        assertFalse(json.has("recorded_by"))
    }

    @Test
    fun `sin agente el principal va vacio, no null`() {
        val json = JSONObject(sello.copy(principal = null).json("DEV-1", "1", "2026-11-02T10:05:00Z"))
        assertEquals("", json.getString("principal"))
    }

    @Test
    fun `lo de las gafas se marca como goggles, tambien su proxy y su transcripcion`() {
        assertEquals("goggles", SelloDeEvidencia.grabadoPor("gafas_2026-11-02_10-00-00-000.mp4.fev"))
        assertEquals("goggles", SelloDeEvidencia.grabadoPor("gafas_2026-11-02_10-00-00-000_proxy.mp4.fev"))
        assertEquals("goggles", SelloDeEvidencia.grabadoPor("gafas_2026-11-02_10-00-00-000.transcript.json.fev"))
        assertNull(SelloDeEvidencia.grabadoPor("video_2026-11-02_10-00-00-000.mp4.fev"))
    }

    /** Lo que hara el servidor: verificar sobre los bytes tal cual, sin re-canonizar. */
    @Test
    fun `la firma verifica sobre los bytes exactos del JSON`() {
        val par = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val bytes = sello.json("DEV-1", "1", "2026-11-02T10:05:00Z").toByteArray(Charsets.UTF_8)
        val firma = Signature.getInstance("SHA256withECDSA").run { initSign(par.private); update(bytes); sign() }
        val verifica = Signature.getInstance("SHA256withECDSA").run { initVerify(par.public); update(bytes); verify(firma) }
        assertTrue(verifica)
    }
}
