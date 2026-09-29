package com.delta.aeria_nexus_prototype.data.identity

import org.junit.Assert.assertEquals
import org.junit.Test

/** Workflow 53: que hace el telefono cuando la credencial caduca, con y sin grabacion. */
class PoliticaDeCaducidadTest {

    @Test
    fun sin_certificado_no_hay_nada_que_juzgar() {
        assertEquals(FaseDeCaducidad.VIGENTE, PoliticaDeCaducidad.evaluar(null, AHORA, grabando = false))
    }

    @Test
    fun lejos_de_caducar_esta_vigente() {
        assertEquals(FaseDeCaducidad.VIGENTE, evaluar(caducaEn = AHORA + 30 * DIA, grabando = false))
    }

    @Test
    fun dentro_del_aviso_previo_se_avisa() {
        assertEquals(FaseDeCaducidad.POR_CADUCAR, evaluar(caducaEn = AHORA + 14 * DIA, grabando = false))
        assertEquals(FaseDeCaducidad.POR_CADUCAR, evaluar(caducaEn = AHORA + 1, grabando = true))
    }

    @Test
    fun caducada_sin_grabar_deja_el_telefono_fuera_de_servicio() {
        assertEquals(FaseDeCaducidad.CADUCADA, evaluar(caducaEn = AHORA - 1, grabando = false))
    }

    @Test
    fun caducada_mientras_se_graba_no_corta_la_grabacion() {
        assertEquals(FaseDeCaducidad.EN_GRACIA, evaluar(caducaEn = AHORA - 1, grabando = true))
    }

    @Test
    fun el_mismo_instante_de_caducar_todavia_vale() {
        assertEquals(FaseDeCaducidad.POR_CADUCAR, evaluar(caducaEn = AHORA, grabando = false))
    }

    @Test
    fun los_dias_restantes_se_redondean_hacia_arriba() {
        assertEquals(1L, PoliticaDeCaducidad.diasRestantes(AHORA + 1, AHORA))
        assertEquals(14L, PoliticaDeCaducidad.diasRestantes(AHORA + 14 * DIA, AHORA))
        assertEquals(0L, PoliticaDeCaducidad.diasRestantes(AHORA - DIA, AHORA))
    }

    private fun evaluar(caducaEn: Long, grabando: Boolean) =
        PoliticaDeCaducidad.evaluar(caducaEn, AHORA, grabando)

    private companion object {
        const val AHORA = 1_790_000_000_000L
        const val DIA = 24L * 60 * 60 * 1000
    }
}
