package com.dez.sports.ui.stats

import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.dez.sports.data.Favorites
import com.dez.sports.data.LeaderEntry
import com.dez.sports.data.LeagueStats
import com.dez.sports.data.LeagueTeamStats
import com.dez.sports.model.League
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.teamColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Full list behind a leaderboard: every qualified player (paged in as you scroll) or every team. */
class StatListViewModel(handle: SavedStateHandle) : ViewModel() {
    val league = League.valueOf(handle["league"]!!)
    val leaderName: String? = handle.get<String>("player")?.takeIf { it.isNotEmpty() }
    val title: String? = handle.get<String>("title")?.takeIf { it.isNotEmpty() }
    val teamSpec: Int? = handle.get<String>("team")?.toIntOrNull()
    val highlight: String? = handle.get<String>("hl")?.takeIf { it.isNotEmpty() }

    val players = MutableStateFlow<List<LeaderEntry>>(emptyList())
    val total = MutableStateFlow<Int?>(null)
    val teams = MutableStateFlow<LeagueTeamStats?>(null)
    val error = MutableStateFlow<String?>(null)
    private var key: String? = null
    private var page = 0
    private var pages = 1
    private var loading = false

    init {
        if (teamSpec != null) viewModelScope.launch(Dispatchers.Default) { runCatching { LeagueStats.teams(league) }.onSuccess { teams.value = it }.onFailure { error.value = it.message } }
        else viewModelScope.launch(Dispatchers.Default) {
            key = leaderName?.let { LeagueStats.sortKey(league, it) }
            if (key == null) error.value = "The full list isn't available for this stat." else more()
        }
    }

    fun more() {
        val k = key ?: return
        if (loading || page >= pages) return
        loading = true
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { LeagueStats.players(league, k, page + 1) }
                .onSuccess { p -> page++; pages = p.pages; total.value = p.total; players.value = players.value + p.entries }
                .onFailure { if (players.value.isEmpty()) error.value = it.message }
            loading = false
        }
    }
    val hasMore get() = page < pages
}

@Composable
fun StatListScreen(onBack: () -> Unit, onTeam: (League, String) -> Unit, vm: StatListViewModel = viewModel()) {
    val players by vm.players.collectAsState()
    val total by vm.total.collectAsState()
    val teams by vm.teams.collectAsState()
    val error by vm.error.collectAsState()
    val favs by Favorites.teams.collectAsState()
    val favIds = favs.filter { it.league == vm.league }.map { it.teamId }.toSet()
    val spec = teams?.specs?.getOrNull(vm.teamSpec ?: -1)
    val openPlayer = LocalOpenPlayer.current
    val title = spec?.label ?: vm.title ?: "Leaders"
    val subtitle = when {
        spec != null -> "${vm.league.shortLabel} · every team · ${if (spec.higherIsBetter) "higher" else "lower"} is better"
        total != null -> "${vm.league.shortLabel} · $total qualified players"
        else -> vm.league.shortLabel
    }
    val state = rememberLazyListState()
    // Page more players in as the end of the list comes into view.
    val nearEnd by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= state.layoutInfo.totalItemsCount - 8 } == true } }
    LaunchedEffect(nearEnd, players.size) { if (nearEnd && vm.teamSpec == null) vm.more() }

    Column(Modifier.fillMaxSize().background(Dez.Bg)) {
        Row(Modifier.statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Dez.Card).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Dez.Text)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), state = state, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 32.dp)) {
                when {
                    error != null && players.isEmpty() && teams == null -> item { EmptyState("Couldn't load the list", error) }
                    vm.teamSpec != null -> {
                        val t = teams
                        if (t == null || spec == null) item { SkeletonCards(3) }
                        else {
                            val rows = t.table(spec)
                            val avg = rows.map { it.value }.average()
                            item { Text("▏ league average ${spec.format(avg)}", style = MaterialTheme.typography.labelSmall, color = Dez.Faint, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) }
                            itemsIndexed(rows, key = { _, r -> r.team.id }) { i, r ->
                                Box(Modifier.clip(if (i == 0) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp) else if (i == rows.lastIndex) RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp) else RoundedCornerShape(0.dp)).background(Dez.Card)) {
                                    TeamBarRow(r, rows, spec, r.team.id in favIds || r.team.id == vm.highlight, i, avg) { onTeam(vm.league, r.team.id) }
                                }
                            }
                        }
                    }
                    players.isEmpty() -> item { SkeletonCards(4) }
                    else -> {
                        itemsIndexed(players, key = { _, e -> e.athleteId + "-" + e.rank }) { i, e ->
                            PlayerRow(e, e.team?.id in favIds, i) { openPlayer?.invoke(vm.league, e.athleteId) }
                        }
                        if (vm.hasMore) item { Text("Loading more…", style = MaterialTheme.typography.labelMedium, color = Dez.Sub, modifier = Modifier.padding(16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerRow(e: LeaderEntry, fav: Boolean, i: Int, onClick: () -> Unit) {
    val tc = e.team?.let { teamColor(it.color, it.altColor).readable() } ?: Dez.Card2
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (fav) tc.copy(alpha = .18f) else if (i % 2 == 0) Dez.Card else Dez.Bg)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${e.rank}", style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = if (e.rank <= 3) Dez.Gold else Dez.Sub, modifier = Modifier.width(36.dp))
        AsyncImage(e.headshot, e.name, Modifier.size(36.dp).clip(CircleShape).background(Dez.Card2), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.titleSmall, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.team?.let { Text(it.displayName, style = MaterialTheme.typography.labelSmall, color = if (fav) Dez.Gold else Dez.Sub, maxLines = 1) }
        }
        e.team?.let { TeamLogo(it, 22.dp); Spacer(Modifier.width(10.dp)) }
        Text(e.value, style = MaterialTheme.typography.titleMedium.merge(TabularNums).copy(fontWeight = if (e.rank == 1) FontWeight.Bold else FontWeight.Medium), color = Dez.Text)
    }
}
