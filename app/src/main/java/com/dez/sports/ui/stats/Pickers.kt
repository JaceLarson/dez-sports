package com.dez.sports.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dez.sports.data.SearchPlayer
import com.dez.sports.data.StatsApi
import com.dez.sports.model.League
import com.dez.sports.model.Team
import com.dez.sports.ui.components.TeamLogo
import com.dez.sports.ui.theme.Dez
import kotlinx.coroutines.delay

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = Dez.Sub) },
        placeholder = { Text(placeholder, color = Dez.Faint) },
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Dez.Accent, unfocusedBorderColor = Dez.Hairline, focusedContainerColor = Dez.Card, unfocusedContainerColor = Dez.Card),
    )
}

/** Bottom sheet: type a name, pick a player (optionally limited to one league). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSearchSheet(league: League?, onDismiss: () -> Unit, onPick: (SearchPlayer) -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchPlayer>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(300); busy = true
        results = runCatching { StatsApi.searchPlayers(q, league) }.getOrDefault(emptyList())
        busy = false
    }
    ModalBottomSheet(onDismiss, containerColor = Dez.Bg, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxHeight(.85f)) {
            Text("Find a player" + (league?.let { " · ${it.shortLabel}" } ?: ""), style = MaterialTheme.typography.titleLarge, color = Dez.Text)
            Spacer(Modifier.height(10.dp))
            SearchField(q, { q = it }, "Name…")
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = Dez.Accent, trackColor = Dez.Card)
            LazyColumn(contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(results, key = { "${it.league}-${it.id}" }) { p ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Dez.Card).clickable { onPick(p) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(p.headshot, p.name, Modifier.size(40.dp).clip(CircleShape).background(Dez.Card2), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall, color = Dez.Text)
                            Text(listOfNotNull(p.league.shortLabel, p.position, p.team?.displayName).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Dez.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        p.team?.let { TeamLogo(it, 26.dp) }
                    }
                }
            }
        }
    }
}

/** Bottom sheet: pick any team in a league. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamPickerSheet(league: League, onDismiss: () -> Unit, onPick: (Team) -> Unit) {
    var all by remember(league) { mutableStateOf<List<Team>?>(null) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(league) { all = runCatching { StatsApi.teams(league) }.getOrDefault(emptyList()) }
    ModalBottomSheet(onDismiss, containerColor = Dez.Bg, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxHeight(.85f)) {
            Text("Pick a ${league.shortLabel} team", style = MaterialTheme.typography.titleLarge, color = Dez.Text)
            Spacer(Modifier.height(10.dp))
            SearchField(q, { q = it }, "Team…")
            val shown = all.orEmpty().filter { q.isBlank() || it.displayName.contains(q, true) || it.abbreviation.contains(q, true) }
            if (all == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = Dez.Accent, trackColor = Dez.Card)
            LazyColumn(contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.id }) { t ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Dez.Card).clickable { onPick(t) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TeamLogo(t, 30.dp); Spacer(Modifier.width(10.dp))
                        Text(t.displayName, style = MaterialTheme.typography.titleSmall, color = Dez.Text)
                    }
                }
            }
        }
    }
}
