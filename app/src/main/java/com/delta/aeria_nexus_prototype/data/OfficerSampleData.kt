package com.delta.aeria_nexus_prototype.data

import com.delta.aeria_nexus_prototype.data.model.AgentIdCard
import com.delta.aeria_nexus_prototype.data.model.OfficerProfile

/**
 * Identidad del agente y padron de placas. Estatico hasta que exista login
 * contra un backend real.
 *
 * Ya NO trae incidentes de ejemplo: la lista de incidentes sale entera de Room
 * (lo que el agente ha capturado de verdad). Se retiraron el 2026-08-25 porque
 * mezclar demo con evidencia real en la misma pantalla es justo lo que una
 * cadena de custodia no puede permitirse.
 */
object OfficerSampleData {

    val profile = OfficerProfile(
        name = "Carlos Mendez",
        officerNum = "P-4471",
        agency = "Aeria Demo Police Department",
        unit = "Night Shift Patrol",
        rank = "Patrol Officer",
        jurisdiction = "Demo City",
        languageProfile = "Philippines — Tagalog/English",
        transcriptLanguage = "Tagalog/Taglish aware",
        reportLanguage = "English",
        deviceId = "DEVICE-MB-4471",
        fcConnected = true,
        flConnected = true,
        appVersion = "v1.0.0",
    )

    // Ficha de cada agente conocido, por numero de placa. El SOS solo viaja
    // con la placa del emisor; aqui se resuelve el resto de sus datos. Padron
    // estatico hasta que exista login contra un backend real.
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
