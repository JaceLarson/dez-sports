package com.dez.sports.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dez.sports.data.EspnApi
import com.dez.sports.data.FavoriteTeam
import com.dez.sports.data.Favorites
import com.dez.sports.model.League
import com.dez.sports.model.Team
import com.dez.sports.ui.components.TeamLogo
import com.dez.sports.ui.stats.TeamPickerSheet
import com.dez.sports.ui.theme.Dez
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** My Teams (add, remove, reorder) and the score list style. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onTeam: (League, String) -> Unit) {
    val favs by Favorites.teams.collectAsState()
    val compact by Favorites.compactScores.collectAsState()
    val showDez by Favorites.showDez.collectAsState()
    val showWatch by Favorites.showWatch.collectAsState()
    // Names and logos for the saved ids, from each league's team directory.
    var names by remember { mutableStateOf<Map<FavoriteTeam, Team>>(emptyMap()) }
    LaunchedEffect(favs) {
        names = withContext(Dispatchers.Default) {
            favs.map { it.league }.distinct().flatMap { l ->
                val dir = runCatching { EspnApi.directory(l) }.getOrDefault(emptyMap())
                favs.filter { it.league == l }.mapNotNull { f -> dir[f.teamId]?.let { f to it } }
            }.toMap()
        }
    }
    var pickLeague by remember { mutableStateOf<League?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val version = LocalContext.current.let { c -> runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() }

    LazyColumn(Modifier.fillMaxSize().background(Dez.Bg), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Dez.Card).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Dez.Text)
                }
                Text("Settings", style = MaterialTheme.typography.headlineSmall, color = Dez.Text, modifier = Modifier.padding(start = 12.dp))
            }
        }

        item { Section("MY TEAMS", "Shown first on Scores, in this order. You can also tap the ☆ on any team page.") }
        if (favs.isEmpty()) item {
            Text("No teams yet. Add one below.", style = MaterialTheme.typography.bodyMedium, color = Dez.Sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        items(favs, key = { "${it.league}-${it.teamId}" }) { f ->
            val t = names[f]
            val i = favs.indexOf(f)
            Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dez.Card)
                .clickable { onTeam(f.league, f.teamId) }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (t != null) TeamLogo(t, 32.dp) else Box(Modifier.size(32.dp).clip(CircleShape).background(Dez.Card2))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t?.displayName ?: "Team ${f.teamId}", style = MaterialTheme.typography.titleSmall, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(f.league.label, style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
                }
                RowIcon(Icons.Filled.KeyboardArrowUp, "Move up", i > 0) { Favorites.move(f, -1) }
                RowIcon(Icons.Filled.KeyboardArrowDown, "Move down", i < favs.lastIndex) { Favorites.move(f, 1) }
                RowIcon(Icons.Filled.Close, "Remove", true) { Favorites.toggle(f.league, f.teamId) }
            }
        }

        item {
            Text("ADD A TEAM", style = MaterialTheme.typography.labelSmall, color = Dez.Faint, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(League.entries.filter { it != League.PGA }) { l ->
                    Text("+ ${l.shortLabel}", style = MaterialTheme.typography.labelLarge, color = Dez.Text,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Dez.Card2).clickable { pickLeague = l }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Reset to Dez's teams") { Favorites.resetToDefaults() }
                if (favs.isNotEmpty()) Pill("Remove all", Dez.Live) { confirmClear = true }
            }
        }

        item { Section("SCORES", null) }
        item { ToggleRow("Compact score list", "One line per game instead of full score cards", compact ?: Favorites.compact(), Favorites::setCompact) }
        item { ToggleRow("Dez Scores", "0–100 rating of how fun a finished game was", showDez, Favorites::setShowDez) }
        item { ToggleRow("Watch scores", "0–100 rating of how worth watching an upcoming game looks", showWatch, Favorites::setShowWatch) }
        item {
            Text("Dez Sports ${version.orEmpty()} · scores and stats from ESPN", style = MaterialTheme.typography.labelSmall, color = Dez.Faint,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }

    pickLeague?.let { l ->
        TeamPickerSheet(l, onDismiss = { pickLeague = null }) { t ->
            if (!Favorites.isFavorite(l, t.id)) Favorites.toggle(l, t.id)
            pickLeague = null
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, containerColor = Dez.Card,
        title = { Text("Remove all teams?", color = Dez.Text) },
        text = { Text("Scores will show only league games until you add teams again.", color = Dez.Sub) },
        confirmButton = { TextButton({ Favorites.clear(); confirmClear = false }) { Text("Remove all", color = Dez.Live) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel", color = Dez.Sub) } },
    )
}

@Composable
private fun ToggleRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dez.Card)
        .clickable { onChange(!on) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Dez.Text)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Dez.Accent, uncheckedTrackColor = Dez.Card2, uncheckedBorderColor = Dez.Hairline))
    }
}

@Composable
private fun Section(title: String, sub: String?) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
        sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Dez.Faint) }
    }
}

@Composable
private fun RowIcon(icon: ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    Icon(icon, desc, tint = if (enabled) Dez.Sub else Dez.Card2,
        modifier = Modifier.size(40.dp).clip(CircleShape).clickable { if (enabled) onClick() }.padding(8.dp))
}

@Composable
private fun Pill(label: String, color: Color = Dez.Text, onClick: () -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Dez.Card).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp))
}
