package com.delta.aeria_nexus_prototype.data

import com.delta.aeria_nexus_prototype.data.model.ConfidenceLevel
import com.delta.aeria_nexus_prototype.data.model.EvidenceRef
import com.delta.aeria_nexus_prototype.data.model.EvidenceSource
import com.delta.aeria_nexus_prototype.data.model.IncidentStatus
import com.delta.aeria_nexus_prototype.data.model.NarrativeSegment
import com.delta.aeria_nexus_prototype.data.model.Officer
import com.delta.aeria_nexus_prototype.data.model.Priority
import com.delta.aeria_nexus_prototype.data.model.ReportEvidence
import com.delta.aeria_nexus_prototype.data.model.ReportEvidenceStatus
import com.delta.aeria_nexus_prototype.data.model.ReportIncident
import com.delta.aeria_nexus_prototype.data.model.Suspect

/**
 * Datos de ejemplo del flujo de reportes IA (borrador, RMS y confirmacion).
 * Son estaticos: para conectar un backend real, reemplazar las lecturas de
 * este objeto en IncidentRepository.
 */
object ReportSampleData {

    private val mendez = Officer(
        id = "off-001", badge = "P-4471", name = "Officer Carlos Mendez",
        rank = "Officer", unit = "North Patrol Unit", email = "c.mendez@police.local",
    )
    private val torres = Officer(
        id = "off-002", badge = "P-3318", name = "Officer Lucia Torres",
        rank = "Senior Officer", unit = "North Patrol Unit", email = "l.torres@police.local",
    )

    private val narrativeInc001 = listOf(
        NarrativeSegment(
            id = "n1",
            text = "On the date of the incident, Officer Carlos Mendez (P-4471) responded to a 911 call " +
                "reporting a domestic disturbance at 1240 Constitution Ave, Apartment 2B, at 21:14 hours.",
            confidence = ConfidenceLevel.HIGH,
            evidenceRef = EvidenceRef("21:14:32", "21:14:48", "Unit 47 on scene. Code 10-16 confirmed.", EvidenceSource.FALCON_CORE),
        ),
        NarrativeSegment(
            id = "n2",
            text = "Upon arriving at the residence, the officer heard raised voices coming from the second " +
                "floor and proceeded to knock on the door, identifying himself as a member of the Municipal Police.",
            confidence = ConfidenceLevel.HIGH,
            evidenceRef = EvidenceRef("21:15:20", "21:16:22", "Accessing building. Loud voices heard from the corridor.", EvidenceSource.FALCON_CORE),
        ),
        NarrativeSegment(
            id = "n3",
            text = "The male subject, later identified as Juan R. M., age 34, refused to open the door and " +
                "instructed the officer to leave the premises.",
            confidence = ConfidenceLevel.MEDIUM,
            evidenceRef = EvidenceRef("21:16:10", "21:16:45", "Go away! We don't need anyone!", EvidenceSource.FALCON_LENS),
        ),
        NarrativeSegment(
            id = "n4",
            text = "The female victim requested help from inside the residence, which led Officer Mendez to " +
                "request additional backup and proceed to authorize forced entry of the door.",
            confidence = ConfidenceLevel.HIGH,
            evidenceRef = EvidenceRef("21:16:45", "21:17:30", "Please... help me...", EvidenceSource.FALCON_LENS),
        ),
        NarrativeSegment(
            id = "n5",
            text = "With the support of Officer Lucia Torres (P-3318), forced entry was executed at 21:18 hours, " +
                "finding the victim with visible facial injuries and injuries to her upper extremities.",
            confidence = ConfidenceLevel.HIGH,
            evidenceRef = EvidenceRef("21:18:05", "21:18:45", "Forced entry authorized. Entering the residence.", EvidenceSource.FALCON_CORE),
        ),
        NarrativeSegment(
            id = "n6",
            text = "An ambulance unit was requested via code 10-52. The subject was secured without further " +
                "resistance and transported to the unit for the corresponding proceedings.",
            confidence = ConfidenceLevel.MEDIUM,
            evidenceRef = EvidenceRef("21:18:22", "21:18:55", "Male subject secured. No further resistance.", EvidenceSource.FALCON_CORE),
        ),
        NarrativeSegment(
            id = "n7",
            text = "The officer presumes the conflict originated from a domestic dispute, although the initial " +
                "trigger of the disturbance could not be established with certainty.",
            confidence = ConfidenceLevel.LOW,
        ),
    )

    private val narrativeInc002 = listOf(
        NarrativeSegment(
            id = "n1",
            text = "On April 04, 2026, at 14:22 hours, Officer Lucia Torres (P-3318) conducted a vehicle stop " +
                "on a plate XBT-8821 at 540 Reform Street, based on suspicious behavior observed during patrol.",
            confidence = ConfidenceLevel.HIGH,
            evidenceRef = EvidenceRef("14:22:10", "14:22:45", "Unit 52 conducting stop of suspicious vehicle.", EvidenceSource.FALCON_CORE),
        ),
        NarrativeSegment(
            id = "n2",
            text = "The driver identified himself as Marco Aurelio Sanchez, ID 8-712-455. The officer requested " +
                "a background check from central dispatch.",
            confidence = ConfidenceLevel.HIGH,
            evidenceRef = EvidenceRef("14:24:10", "14:24:55", "Dispatch, run background check.", EvidenceSource.FALCON_CORE),
        ),
        NarrativeSegment(
            id = "n3",
            text = "Dispatch confirmed the existence of an active arrest warrant in the subject's name, " +
                "corresponding to judicial case 2025-CR-0881.",
            confidence = ConfidenceLevel.HIGH,
            evidenceRef = EvidenceRef("14:24:55", "14:25:15", "Confirmed. Subject has an active arrest warrant.", EvidenceSource.FALCON_CORE),
        ),
        NarrativeSegment(
            id = "n4",
            text = "The subject was placed under arrest. Mr. Sanchez stepped out of the vehicle cooperatively, " +
                "though verbally protested claiming this was a mistake.",
            confidence = ConfidenceLevel.MEDIUM,
            evidenceRef = EvidenceRef("14:25:15", "14:26:05", "Mr. Sanchez, you have an active arrest warrant.", EvidenceSource.FALCON_LENS),
        ),
        NarrativeSegment(
            id = "n5",
            text = "Handcuffs were applied without need for additional force. Miranda rights were read to the " +
                "detainee in English.",
            confidence = ConfidenceLevel.HIGH,
            evidenceRef = EvidenceRef("14:26:05", "14:26:30", "Proceeding with arrest. Subject cooperative.", EvidenceSource.FALCON_CORE),
        ),
    )

    private val narrativeInc003 = listOf(
        NarrativeSegment(
            id = "n1",
            text = "On April 04, 2026 at 09:45 hours, Officer Carlos Mendez (P-4471) conducted a traffic stop " +
                "at Heroes Blvd km 12, after observing a vehicle that disregarded a red traffic signal.",
            confidence = ConfidenceLevel.HIGH,
            evidenceRef = EvidenceRef("09:45:02", "09:45:38", "Conducting traffic stop. Vehicle ran red light.", EvidenceSource.FALCON_CORE),
        ),
        NarrativeSegment(
            id = "n2",
            text = "The driver, identified as Ana Lucia Ramirez with a valid license, cooperated immediately, " +
                "providing documentation without incident.",
            confidence = ConfidenceLevel.HIGH,
            evidenceRef = EvidenceRef("09:45:42", "09:46:05", "Good morning. License and registration please.", EvidenceSource.FALCON_CORE),
        ),
        NarrativeSegment(
            id = "n3",
            text = "License and vehicle registration were verified through the SITEV system, with no prior " +
                "violations or outstanding warrants found.",
            confidence = ConfidenceLevel.HIGH,
            evidenceRef = EvidenceRef("09:46:30", "09:47:10", "All clear.", EvidenceSource.FALCON_CORE),
        ),
        NarrativeSegment(
            id = "n4",
            text = "Citation number 2026-TR-00412 was issued under Traffic Regulation Article 78. The driver " +
                "accepted the citation without incident.",
            confidence = ConfidenceLevel.HIGH,
            evidenceRef = EvidenceRef("09:47:10", "09:47:45", "I'm issuing you a citation for running a red light.", EvidenceSource.FALCON_CORE),
        ),
    )

    val incidents = listOf(
        ReportIncident(
            id = "INC-001",
            caseNumber = "45821-2026",
            type = "Domestic Disturbance",
            typeCode = "10-16",
            status = IncidentStatus.DRAFT,
            priority = Priority.HIGH,
            location = "1240 Constitution Ave, Apt 2B",
            date = "04/04/2026",
            startTime = "21:14",
            duration = "47 min",
            officer = mendez,
            involvedOfficers = listOf(mendez, torres),
            narrative = narrativeInc001,
            suspects = listOf(
                Suspect(
                    id = "s1", name = "Juan R. M.", dob = "03/12/1990", gender = "Male",
                    height = "5'10\"", weight = "181 lbs",
                    charges = listOf("Domestic Violence (Art. 188 PC)", "Resisting Arrest"),
                ),
            ),
            evidence = listOf(
                ReportEvidence("ev1", "BWC Video", "Full body cam recording — Officer Mendez", EvidenceSource.FALCON_CORE, "21:14:32", "21:19:10", ReportEvidenceStatus.SUBMITTED),
                ReportEvidence("ev2", "FaceWorn Video", "Falcon Lens recording — entry into residence", EvidenceSource.FALCON_LENS, "21:16:45", "21:18:55", ReportEvidenceStatus.SUBMITTED),
                ReportEvidence("ev3", "Transcript", "AI transcription of scene audio", EvidenceSource.FALCON_CORE, "21:14:32", "21:18:55", ReportEvidenceStatus.LOGGED),
            ),
        ),
        ReportIncident(
            id = "INC-002",
            caseNumber = "45822-2026",
            type = "Arrest by Warrant",
            typeCode = "10-15",
            status = IncidentStatus.NEEDS_REVIEW,
            priority = Priority.MEDIUM,
            location = "540 Reform Street",
            date = "04/04/2026",
            startTime = "14:22",
            duration = "28 min",
            officer = torres,
            involvedOfficers = listOf(torres),
            narrative = narrativeInc002,
            suspects = listOf(
                Suspect(
                    id = "s1", name = "Marco Aurelio Sanchez", dob = "11/07/1985", gender = "Male",
                    height = "5'8\"", weight = "165 lbs",
                    charges = listOf("Tax Evasion (case 2025-CR-0881)", "Active Arrest Warrant"),
                ),
            ),
            evidence = listOf(
                ReportEvidence("ev1", "BWC Video", "Full recording of vehicle stop", EvidenceSource.FALCON_CORE, "14:22:10", "14:26:45", ReportEvidenceStatus.SUBMITTED),
                ReportEvidence("ev2", "FaceWorn Video", "Falcon Lens recording — arrest moment", EvidenceSource.FALCON_LENS, "14:25:15", "14:26:30", ReportEvidenceStatus.SUBMITTED),
            ),
            supervisorNote = "Verify Miranda citation matches recording timestamps.",
        ),
        ReportIncident(
            id = "INC-003",
            caseNumber = "45823-2026",
            type = "Traffic Violation",
            typeCode = "10-99",
            status = IncidentStatus.COMPLETED,
            priority = Priority.LOW,
            location = "Heroes Blvd km 12",
            date = "04/04/2026",
            startTime = "09:45",
            duration = "12 min",
            officer = mendez,
            involvedOfficers = listOf(mendez),
            narrative = narrativeInc003,
            suspects = emptyList(),
            evidence = listOf(
                ReportEvidence("ev1", "BWC Video", "Full recording of traffic stop", EvidenceSource.FALCON_CORE, "09:45:02", "09:48:30", ReportEvidenceStatus.SUBMITTED),
            ),
        ),
    )
}
