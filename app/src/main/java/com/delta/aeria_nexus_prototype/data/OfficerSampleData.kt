package com.delta.aeria_nexus_prototype.data

import com.delta.aeria_nexus_prototype.data.model.AgentIdCard
import com.delta.aeria_nexus_prototype.data.model.EvidenceClass
import com.delta.aeria_nexus_prototype.data.model.EvidenceRecord
import com.delta.aeria_nexus_prototype.data.model.EvidenceType
import com.delta.aeria_nexus_prototype.data.model.IncidentStatus
import com.delta.aeria_nexus_prototype.data.model.OfficerIncident
import com.delta.aeria_nexus_prototype.data.model.OfficerProfile
import com.delta.aeria_nexus_prototype.data.model.Priority
import com.delta.aeria_nexus_prototype.data.model.SyncState
import com.delta.aeria_nexus_prototype.data.model.TimelineEntry
import com.delta.aeria_nexus_prototype.data.model.TimelineEntryType

/**
 * Datos de ejemplo del flujo de campo. Son estaticos: para conectar un
 * backend real, reemplazar las lecturas de este objeto en IncidentRepository.
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

    val incidents = listOf(
        OfficerIncident(
            id = "INC-2026-00184",
            type = "Domestic Disturbance",
            typeCode = "10-16",
            location = "1240 Constitution Ave, Apt 2B",
            date = "04/04/2026",
            time = "21:14",
            officerName = "Carlos Mendez",
            officerNum = "P-4471",
            status = IncidentStatus.DRAFT,
            priority = Priority.HIGH,
            evidenceCount = 4,
            witnessCount = 1,
            sync = SyncState.LOCAL_ONLY,
            duration = "47 min",
            narrative = "Officers responded to a domestic disturbance call at 1240 Constitution Ave. " +
                "Upon arrival, officers identified visible signs of altercation. Subject was secured " +
                "without further resistance. Victim transported to local hospital.",
            timeline = listOf(
                TimelineEntry("t1", "21:14", "Incident created", TimelineEntryType.CREATED),
                TimelineEntry("t2", "21:15", "Recording started — FC + FL active", TimelineEntryType.RECORDING_START),
                TimelineEntry("t3", "21:16", "Photo captured — Scene exterior", TimelineEntryType.PHOTO),
                TimelineEntry("t4", "21:17", "Audio note added — Officer narrative", TimelineEntryType.AUDIO),
                TimelineEntry("t5", "21:21", "Witness QR generated", TimelineEntryType.WITNESS_QR),
                TimelineEntry("t6", "21:29", "Witness upload received", TimelineEntryType.WITNESS_UPLOAD),
                TimelineEntry("t7", "21:31", "Recording ended — 16m 12s captured", TimelineEntryType.RECORDING_END),
                TimelineEntry("t8", "21:31", "Incident ended — moved to draft", TimelineEntryType.ENDED),
            ),
            evidence = listOf(
                EvidenceRecord(
                    id = "ev1", type = EvidenceType.VIDEO, label = "Video recording — FC + FL",
                    time = "21:15", duration = "16m 12s", device = "FC + FL",
                    classification = EvidenceClass.EVIDENCE,
                    hash = "sha256:8f14e45a...", sync = SyncState.LOCAL_ONLY,
                ),
                EvidenceRecord(
                    id = "ev2", type = EvidenceType.PHOTO, label = "Scene exterior — entrance door",
                    time = "21:16", classification = EvidenceClass.SCENE_CONTEXT,
                    hash = "sha256:2b4c7f91...", sync = SyncState.LOCAL_ONLY,
                ),
                EvidenceRecord(
                    id = "ev3", type = EvidenceType.AUDIO, label = "Officer narrative note",
                    time = "21:17", duration = "1m 24s", classification = EvidenceClass.EVIDENCE,
                    hash = "sha256:9a3f1c82...", sync = SyncState.LOCAL_ONLY,
                ),
                EvidenceRecord(
                    id = "ev4", type = EvidenceType.WITNESS_UPLOAD, label = "Witness upload — Anonymous #1",
                    time = "21:29", classification = EvidenceClass.WITNESS_STATEMENT,
                    hash = "sha256:4d8e2b03...", sync = SyncState.LOCAL_ONLY,
                ),
            ),
        ),
        OfficerIncident(
            id = "INC-2026-00183",
            type = "Arrest by Warrant",
            typeCode = "10-15",
            location = "540 Reform Street",
            date = "04/04/2026",
            time = "14:22",
            officerName = "Lucia Torres",
            officerNum = "P-3318",
            status = IncidentStatus.NEEDS_REVIEW,
            priority = Priority.MEDIUM,
            evidenceCount = 6,
            witnessCount = 2,
            sync = SyncState.PENDING_SYNC,
            duration = "38 min",
            narrative = "Officers executed a valid arrest warrant for subject at 540 Reform Street. " +
                "Subject was compliant. Property search conducted pursuant to warrant.",
            timeline = listOf(
                TimelineEntry("t1", "14:22", "Incident created", TimelineEntryType.CREATED),
                TimelineEntry("t2", "14:23", "Recording started — FC active", TimelineEntryType.RECORDING_START),
                TimelineEntry("t3", "14:26", "Photo captured — Subject identification", TimelineEntryType.PHOTO),
                TimelineEntry("t4", "14:28", "Photo captured — Seized item", TimelineEntryType.PHOTO),
                TimelineEntry("t5", "14:29", "Audio note added — Warrant verification", TimelineEntryType.AUDIO),
                TimelineEntry("t6", "14:31", "Recording ended — 8m 02s captured", TimelineEntryType.RECORDING_END),
                TimelineEntry("t7", "14:32", "Witness QR generated", TimelineEntryType.WITNESS_QR),
                TimelineEntry("t8", "14:35", "Witness upload received", TimelineEntryType.WITNESS_UPLOAD),
                TimelineEntry("t9", "15:00", "Incident ended — moved to draft", TimelineEntryType.ENDED),
            ),
            evidence = listOf(
                EvidenceRecord(
                    id = "ev1", type = EvidenceType.VIDEO, label = "Video recording — FC",
                    time = "14:23", duration = "8m 02s", device = "FC",
                    classification = EvidenceClass.EVIDENCE,
                    hash = "sha256:3c2a9f44...", sync = SyncState.PENDING_SYNC,
                ),
                EvidenceRecord(
                    id = "ev2", type = EvidenceType.PHOTO, label = "Subject identification — front",
                    time = "14:26", classification = EvidenceClass.PERSON,
                    hash = "sha256:7b1e4d50...", sync = SyncState.PENDING_SYNC,
                ),
                EvidenceRecord(
                    id = "ev3", type = EvidenceType.PHOTO, label = "Seized item — narcotics",
                    time = "14:28", classification = EvidenceClass.SEIZED_PROPERTY,
                    hash = "sha256:5c3f8a12...", sync = SyncState.PENDING_SYNC,
                ),
                EvidenceRecord(
                    id = "ev4", type = EvidenceType.AUDIO, label = "Warrant verification note",
                    time = "14:29", duration = "0m 52s", classification = EvidenceClass.EVIDENCE,
                    hash = "sha256:2e9b7c11...", sync = SyncState.PENDING_SYNC,
                ),
                EvidenceRecord(
                    id = "ev5", type = EvidenceType.WITNESS_UPLOAD, label = "Witness #1 statement",
                    time = "14:35", classification = EvidenceClass.WITNESS_STATEMENT,
                    hash = "sha256:8a4d0f22...", sync = SyncState.PENDING_SYNC,
                ),
                EvidenceRecord(
                    id = "ev6", type = EvidenceType.WITNESS_UPLOAD, label = "Witness #2 statement",
                    time = "14:38", classification = EvidenceClass.WITNESS_STATEMENT,
                    hash = "sha256:1b5e3c99...", sync = SyncState.PENDING_SYNC,
                ),
            ),
        ),
        OfficerIncident(
            id = "INC-2026-00182",
            type = "Traffic Violation",
            typeCode = "10-55",
            location = "Heroes Blvd km 12",
            date = "04/04/2026",
            time = "10:05",
            officerName = "Carlos Mendez",
            officerNum = "P-4471",
            status = IncidentStatus.COMPLETED,
            priority = Priority.LOW,
            evidenceCount = 3,
            witnessCount = 0,
            sync = SyncState.VERIFIED,
            duration = "22 min",
            narrative = "Motorist cited for reckless driving at Heroes Blvd km 12. License plate " +
                "documented. Field sobriety assessment completed.",
            timeline = listOf(
                TimelineEntry("t1", "10:05", "Incident created", TimelineEntryType.CREATED),
                TimelineEntry("t2", "10:06", "Recording started — FC active", TimelineEntryType.RECORDING_START),
                TimelineEntry("t3", "10:10", "Photo captured — License plate", TimelineEntryType.PHOTO),
                TimelineEntry("t4", "10:12", "Audio note added — Field assessment", TimelineEntryType.AUDIO),
                TimelineEntry("t5", "10:18", "Recording ended — 12m 00s captured", TimelineEntryType.RECORDING_END),
                TimelineEntry("t6", "10:27", "Incident ended — moved to draft", TimelineEntryType.ENDED),
            ),
            evidence = listOf(
                EvidenceRecord(
                    id = "ev1", type = EvidenceType.VIDEO, label = "Dashcam recording",
                    time = "10:06", duration = "12m 00s", device = "AN",
                    classification = EvidenceClass.EVIDENCE,
                    hash = "sha256:5f2d8a77...", sync = SyncState.VERIFIED,
                ),
                EvidenceRecord(
                    id = "ev2", type = EvidenceType.PHOTO, label = "License plate documentation",
                    time = "10:10", classification = EvidenceClass.EVIDENCE,
                    hash = "sha256:1a7b3c66...", sync = SyncState.VERIFIED,
                ),
                EvidenceRecord(
                    id = "ev3", type = EvidenceType.AUDIO, label = "Field sobriety assessment",
                    time = "10:12", duration = "0m 45s", classification = EvidenceClass.EVIDENCE,
                    hash = "sha256:8e9c0d11...", sync = SyncState.VERIFIED,
                ),
            ),
        ),
    )
}
