package com.delta.aeria_nexus_prototype.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.data.model.OfficerProfile
import com.delta.aeria_nexus_prototype.ui.components.AppScaffold
import com.delta.aeria_nexus_prototype.ui.components.CardSurface
import com.delta.aeria_nexus_prototype.ui.components.MainTab
import com.delta.aeria_nexus_prototype.ui.components.SectionLabel
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.BordeMuySutil
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.SuperficiePresionada
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

/** Perfil del agente: datos administrativos, idioma y dispositivos vinculados. */
@Composable
fun ProfileScreen(
    profile: OfficerProfile,
    onTabSelected: (MainTab) -> Unit,
) {
    AppScaffold(currentTab = MainTab.PROFILE, onTabSelected = onTabSelected) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(
                    text = "AERIA NEXUS",
                    color = TextoTerciario,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 3.sp,
                )
                Text(
                    text = "PROFILE",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                )
            }

            AvatarCard(profile)

            SectionLabel("Officer Information")
            CardSurface {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    InfoRow("Agency", profile.agency)
                    InfoRow("Unit", profile.unit)
                    InfoRow("Rank", profile.rank)
                    InfoRow("Jurisdiction", profile.jurisdiction, isLast = true)
                }
            }

            SectionLabel("Language Profile")
            CardSurface {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    InfoRow("Profile", profile.languageProfile)
                    InfoRow("Transcript", profile.transcriptLanguage)
                    InfoRow("RMS Report", profile.reportLanguage, isLast = true)
                }
            }
            PolicyNote("Language settings are admin-assigned. Contact your supervisor to change language configuration.")

            SectionLabel("Device & Connections")
            CardSurface {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    InfoRow("Device ID", profile.deviceId, mono = true)
                    ConnectionRow("Falcon Core (FC)", profile.fcConnected)
                    ConnectionRow("Falcon Lens (FL)", profile.flConnected)
                    InfoRow("App Version", profile.appVersion, mono = true, isLast = true)
                }
            }
            PolicyNote(
                "GPS is enabled by agency policy. Evidence is automatically geotagged. " +
                    "Officer breadcrumb tracking is admin-controlled.",
            )
        }
    }
}

@Composable
private fun AvatarCard(profile: OfficerProfile) {
    CardSurface {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(AzulPrimario.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                    .border(1.dp, AzulPrimario.copy(alpha = 0.25f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = profile.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.joinToString(""),
                    color = AzulClaro,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = profile.name,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = profile.officerNum,
                    color = AzulClaro,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(text = profile.rank, color = TextoTerciario, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false, isLast: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(140.dp),
            color = TextoTerciario,
            fontSize = 12.sp,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            color = TextoPrincipal,
            fontSize = 12.sp,
            fontWeight = if (mono) FontWeight.Normal else FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 2,
        )
    }
    if (!isLast) {
        HorizontalDivider(color = BordeMuySutil)
    }
}

@Composable
private fun ConnectionRow(label: String, connected: Boolean) {
    val color = if (connected) VerdeOk else RojoSuave
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(140.dp),
            color = TextoTerciario,
            fontSize = 12.sp,
        )
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = if (connected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (connected) "Connected" else "Disconnected",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
    HorizontalDivider(color = BordeMuySutil)
}

@Composable
private fun PolicyNote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SuperficiePresionada.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .border(1.dp, BordeMuySutil, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = TextoTerciario,
            modifier = Modifier.padding(top = 2.dp).size(12.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = TextoTerciario,
            fontSize = 10.sp,
            lineHeight = 15.sp,
        )
    }
}
