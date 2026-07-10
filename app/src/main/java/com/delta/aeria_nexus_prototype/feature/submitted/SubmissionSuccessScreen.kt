package com.delta.aeria_nexus_prototype.feature.submitted

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.data.model.ReportIncident
import com.delta.aeria_nexus_prototype.ui.components.AppScaffold
import com.delta.aeria_nexus_prototype.ui.components.CardSurface
import com.delta.aeria_nexus_prototype.ui.components.MainTab
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.BordeMuySutil
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

/** Confirmacion de envio exitoso del reporte al RMS. */
@Composable
fun SubmissionSuccessScreen(
    incident: ReportIncident?,
    onBackToList: () -> Unit,
    onTabSelected: (MainTab) -> Unit,
) {
    // Valores de respaldo cuando el incidente no existe, igual que la web.
    val caseNumber = incident?.caseNumber ?: "45821-2026"
    val type = incident?.type ?: "Incident"
    val officerName = incident?.officer?.name ?: "Officer"
    val date = incident?.date ?: "04/04/2026"

    AppScaffold(currentTab = null, onTabSelected = onTabSelected, showNav = false) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            CardSurface {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(VerdeOk.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = VerdeOk,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "Report Submitted",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "The report has been successfully transmitted to the Records Management System (RMS).",
                        color = TextoSecundario,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(20.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CaseInfoRow("Case Number", caseNumber, mono = true)
                        CaseInfoRow("Incident Type", type)
                        CaseInfoRow("Reporting Officer", officerName)
                        CaseInfoRow("Date", date)
                        HorizontalDivider(color = BordeMuySutil)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            InfoRowLabel("RMS Status")
                            Spacer(Modifier.weight(1f))
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .background(VerdeOk, CircleShape),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Received & Processed",
                                color = VerdeOk,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = TextoTerciario,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Transmitted on $date at 21:35 hrs",
                            color = TextoTerciario,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Ver en RMS es decorativo en el prototipo.
                        OutlinedButton(
                            onClick = { },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = TextoPrincipal,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("View in RMS", color = TextoPrincipal, fontSize = 13.sp)
                        }
                        Button(
                            onClick = onBackToList,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AzulPrimario),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Back to List", fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                FooterNote(Icons.Filled.Shield, "Digitally sealed record")
                Spacer(Modifier.width(20.dp))
                FooterNote(Icons.Filled.Description, "Linked evidence preserved")
            }
        }
    }
}

@Composable
private fun CaseInfoRow(label: String, value: String, mono: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        InfoRowLabel(label)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            color = TextoPrincipal,
            fontSize = 13.sp,
            fontWeight = if (mono) FontWeight.Bold else FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun InfoRowLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = TextoTerciario,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun FooterNote(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = TextoTerciario, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = text, color = TextoTerciario, fontSize = 11.sp)
    }
}
