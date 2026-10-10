package com.akashrajeev.voicebeam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akashrajeev.voicebeam.Screen

@Composable
fun Chip(text: String, modifier: Modifier = Modifier, color: Color = Card, dot: Color? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(99.dp))
            .background(color)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.padding(end = 6.dp).clip(RoundedCornerShape(99.dp)).background(dot).padding(4.dp))
        }
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color(0xFF6B727B),
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
}

@Composable
fun BottomNav(current: Screen, onNavigate: (Screen) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF111418)).navigationBarsPadding().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavItem(Icons.Filled.CenterFocusStrong, "Listen", current == Screen.FOCUS) { onNavigate(Screen.FOCUS) }
        NavItem(Icons.AutoMirrored.Filled.ViewList, "Recall", current == Screen.RECALL) { onNavigate(Screen.RECALL) }
        NavItem(Icons.Filled.Videocam, "Footage", current == Screen.FOOTAGE) { onNavigate(Screen.FOOTAGE) }
        NavItem(Icons.Filled.Settings, "Settings", current == Screen.SETTINGS) { onNavigate(Screen.SETTINGS) }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = if (selected) Accent else Muted
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = label, tint = c)
        Text(label, color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "${s / 3600} h ${(s % 3600) / 60} min" else if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
}
