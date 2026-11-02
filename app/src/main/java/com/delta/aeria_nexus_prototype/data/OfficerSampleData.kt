package com.delta.aeria_nexus_prototype.data

import com.delta.aeria_nexus_prototype.data.model.AgentIdCard

/**
 * Padron de placas para rotular el SOS de otro agente. Estatico hasta que AeriaOne
 * entregue el de su tenant. La identidad de este telefono ya no sale de aqui: es la
 * de la sesion (PerfilDelAgente) desde el 2026-11-02.
 *
 * Ya NO trae incidentes de ejemplo: la lista de incidentes sale entera de Room
 * (lo que el agente ha capturado de verdad). Se retiraron el 2026-08-25 porque
 * mezclar demo con evidencia real en la misma pantalla es justo lo que una
 * cadena de custodia no puede permitirse.
 */
object OfficerSampleData {

    // Ficha de cada agente conocido, por numero de placa. El SOS solo viaja
    // con la placa del emisor; aqui se resuelve el resto de sus datos.
    private val agentsByBadge = mapOf(
        "P-4471" to AgentIdCard(
            firstName = "Carlos",
            lastName = "Mendez",
            badgeNumber = "P-4471",
            rank = "Patrol Officer",
            bloodType = "O+",
        ),
        "P-3318" to AgentIdCard(
            firstName = "Lucia",
            lastName = "Torres",
            badgeNumber = "P-3318",
            rank = "Patrol Officer",
            bloodType = "A-",
        ),
    )

    /** Ficha del agente con esa placa; null si no esta en el padron. */
    fun findAgent(badgeNumber: String): AgentIdCard? = agentsByBadge[badgeNumber]
}
