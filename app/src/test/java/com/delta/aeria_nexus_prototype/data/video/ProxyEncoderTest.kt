package com.delta.aeria_nexus_prototype.data.video

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyEncoderTest {

    @Test
    fun `un original 1080p baja a 720`() {
        assertEquals(720, ProxyEncoder.ladoCortoDelProxy(1080))
    }

    @Test
    fun `un original 4K tambien baja a 720`() {
        assertEquals(720, ProxyEncoder.ladoCortoDelProxy(2160))
    }

    @Test
    fun `un original de menos de 720 no se agranda`() {
        assertEquals(480, ProxyEncoder.ladoCortoDelProxy(480))
    }

    @Test
    fun `un original de 720 se queda igual`() {
        assertEquals(720, ProxyEncoder.ladoCortoDelProxy(720))
    }
}
