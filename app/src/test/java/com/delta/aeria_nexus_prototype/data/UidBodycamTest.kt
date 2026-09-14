package com.delta.aeria_nexus_prototype.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rangos de uid del canal de Agora.
 *
 * Es un contrato entre dos repositorios que ningun compilador vigila: BodyCamServer
 * calcula el uid de cada unidad como 10000 + el sufijo hexadecimal de su BWC, y este
 * telefono lo reconoce por rango. Si una de las dos puntas cambia sin la otra, el
 * telefono deja de ver el SOS de las bodycams. Estas pruebas caen antes.
 */
class UidBodycamTest {

    @Test
    fun `los extremos del calculo de BodyCamServer son bodycams`() {
        assertTrue(AgoraRepository.esBodycam(10_000 + 0x0000))
        assertTrue(AgoraRepository.esBodycam(10_000 + 0xFFFF))
    }

    @Test
    fun `la W1 de pruebas BWC-896E es una bodycam`() {
        assertTrue(AgoraRepository.esBodycam(45_182))
    }

    @Test
    fun `una unidad sin actualizar sigue disparando el SOS`() {
        assertTrue(AgoraRepository.esBodycam(9001))
    }

    @Test
    fun `ni el grabador en la nube ni un telefono pasan por bodycam`() {
        // Tomar el grabador por una bodycam levantaria un SOS falso al empezar a
        // grabar; tomar un telefono, le quitaria su contador de usuarios.
        assertFalse(AgoraRepository.esBodycam(90_000))
        assertFalse(AgoraRepository.esBodycam(99_999))
        assertFalse(AgoraRepository.esBodycam(100_000))
        assertFalse(AgoraRepository.esBodycam(9_999))
    }
}
