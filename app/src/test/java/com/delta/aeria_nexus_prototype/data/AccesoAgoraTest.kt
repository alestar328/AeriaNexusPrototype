package com.delta.aeria_nexus_prototype.data

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccesoAgoraTest {

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val respuesta = JSONObject(
        """
        {"app_id": "x", "channel": "falcon_group_channel", "uid": 1839201774,
         "token": "007tok", "expires_at": "2026-11-02T22:00:00Z",
         "reserve": {"token": "007res", "expires_at": "2026-11-03T10:00:00Z"},
         "encryption": {"mode": "AES_256_GCM2",
           "current": {"key_id": "2026-W45", "key": "${"a".repeat(64)}", "salt": "c2FsdA==",
                       "valid_from": "2026-11-01T19:00:00Z", "valid_until": "2026-11-08T19:00:00Z"},
           "next": {"key_id": "2026-W46", "key": "${"b".repeat(64)}", "salt": "c2FsdA==",
                    "valid_from": "2026-11-08T19:00:00Z", "valid_until": "2026-11-15T19:00:00Z"}}}
        """,
    )

    @Test
    fun `token normal, despues el de reserva y despues ninguno`() {
        val acceso = AccesoAgoraRepository.leer(respuesta)
        assertEquals(1839201774, acceso.uid)
        assertEquals("007tok", acceso.tokenPara(ms("2026-11-02T12:00:00Z")))
        assertEquals("007res", acceso.tokenPara(ms("2026-11-03T01:00:00Z")))
        assertNull(acceso.tokenPara(ms("2026-11-03T11:00:00Z")))
    }

    @Test
    fun `la clave se elige por la hora, no por el orden`() {
        val acceso = AccesoAgoraRepository.leer(respuesta)
        assertEquals("2026-W45", acceso.claveEn(ms("2026-11-08T18:59:59Z"))?.keyId)
        assertEquals("2026-W46", acceso.claveEn(ms("2026-11-08T19:00:00Z"))?.keyId)
    }

    @Test
    fun `sin certificado ni cifrado se entra sin token y en claro`() {
        val acceso = AccesoAgoraRepository.leer(
            JSONObject("""{"uid": 100042, "channel": "c", "token": null, "expires_at": "2026-11-02T22:00:00Z", "reserve": null}"""),
        )
        assertNull(acceso.tokenPara(ms("2026-11-02T12:00:00Z")))
        assertNull(acceso.claveEn(ms("2026-11-02T12:00:00Z")))
        assertTrue(acceso.claves.isEmpty())
    }
}
