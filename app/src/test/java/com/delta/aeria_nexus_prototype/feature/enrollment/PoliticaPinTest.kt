package com.delta.aeria_nexus_prototype.feature.enrollment

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Politica minima del PIN (workflow 4).
 *
 * El verificador de verdad ([com.delta.aeria_nexus_prototype.data.identity.PinLocal])
 * no se puede probar aqui porque vive sobre el Keystore de Android. Lo que si se
 * puede es esta parte, que es la que decide que PIN se acepta, y conviene tenerla
 * fijada: si alguien afloja la regla, cae una prueba en vez de pasar inadvertido.
 */
class PoliticaPinTest {

    @Test
    fun se_rechaza_el_mismo_digito_repetido() {
        assertNotNull(PoliticaPin.motivoDeRechazo("000000"))
        assertNotNull(PoliticaPin.motivoDeRechazo("777777"))
    }

    @Test
    fun se_rechazan_las_secuencias_en_los_dos_sentidos() {
        assertNotNull(PoliticaPin.motivoDeRechazo("123456"))
        assertNotNull(PoliticaPin.motivoDeRechazo("654321"))
        assertNotNull(PoliticaPin.motivoDeRechazo("456789"))
    }

    @Test
    fun se_rechaza_lo_que_no_tiene_seis_digitos() {
        assertNotNull(PoliticaPin.motivoDeRechazo("0044"))
        assertNotNull(PoliticaPin.motivoDeRechazo("00447100"))
    }

    /**
     * La regla se queda aqui a proposito. Un PIN de campo tiene que poder teclearse
     * con guantes y de noche; exigir mas acaba en un PIN apuntado en la funda.
     */
    @Test
    fun se_aceptan_los_pines_normales() {
        assertNull(PoliticaPin.motivoDeRechazo("004471"))
        assertNull(PoliticaPin.motivoDeRechazo("135790"))
        // Repetidos y tramos consecutivos sueltos si valen: lo que se rechaza es
        // que el PIN ENTERO sea uno de los dos patrones.
        assertNull(PoliticaPin.motivoDeRechazo("112233"))
        assertNull(PoliticaPin.motivoDeRechazo("123450"))
    }
}
