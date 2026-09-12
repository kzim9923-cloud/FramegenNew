package com.firstt175.deepdrop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.firstt175.deepdrop.ui.components.LsfgCard
import com.firstt175.deepdrop.ui.components.LsfgTopBar
import com.firstt175.deepdrop.ui.components.SectionHeader

@Composable
fun SettingsHubScreen(nav: NavHostController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LsfgTopBar(
            title = "การตั้งค่า",
            onBack = { nav.popBackStack() },
        )

        SectionHeader(eyebrow = "ทั่วไป")

        LsfgCard {
            SettingsHubRow(
                icon = Icons.Filled.Speed,
                title = "การสร้างเฟรมและการจัดจังหวะ",
                subtitle = "Frame generation, FPS และ pacing",
                onClick = { nav.navigate(Routes.FRAMEGEN) },
            )
            SettingsHubRow(
                icon = Icons.Filled.DisplaySettings,
                title = "โอเวอร์เลย์และการแสดงผล",
                subtitle = "Capture, HUD และตำแหน่ง overlay",
                onClick = { nav.navigate(Routes.OVERLAY_DISPLAY) },
            )
            SettingsHubRow(
                icon = Icons.Filled.Visibility,
                title = "โอเวอร์เลย์อัตโนมัติ",
                subtitle = "กำหนดเกมที่เปิด overlay อัตโนมัติ",
                onClick = { nav.navigate(Routes.AUTOMATIC_OVERLAY) },
            )
        }

        SectionHeader(eyebrow = "เกม")

        LsfgCard {
            SettingsHubRow(
                icon = Icons.Filled.Security,
                title = "การตั้งค่าเริ่มต้น",
                subtitle = "สิทธิ์และการเตรียมระบบ",
                onClick = { nav.navigate(Routes.SETUP) },
            )
            SettingsHubRow(
                icon = Icons.Filled.BatteryFull,
                title = "โปรไฟล์อุปกรณ์",
                subtitle = "ข้อมูลเครื่องและ diagnostics",
                onClick = { nav.navigate(Routes.PROFILE) },
            )
        }

        SectionHeader(eyebrow = "ระบบ")

        LsfgCard {
            SettingsHubRow(
                icon = Icons.Filled.Tune,
                title = "Lossless.dll",
                subtitle = "เลือก DLL และจัดการ shader",
                onClick = { nav.navigate(Routes.DLL) },
            )
            SettingsHubRow(
                icon = Icons.Filled.Info,
                title = "เครดิต",
                subtitle = "Open-source credits และ license",
                onClick = { nav.navigate(Routes.CREDITS) },
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsHubRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
