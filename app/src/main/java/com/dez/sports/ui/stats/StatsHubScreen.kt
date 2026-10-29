package com.dez.sports.ui.stats

import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.dez.sports.data.LeaderCategory
import com.dez.sports.data.LeagueStats
import com.dez.sports.data.LeagueTeamStats
import com.dez.sports.data.Simulator
import com.dez.sports.data.StatsApi
import com.dez.sports.model.League
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.teamColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class StatsHubViewModel : ViewModel() {
    val league = MutableStateFlow(League.NBA)
    val leaders = MutableStateFlow<List<LeaderCategory>?>(null)
    val category = MutableStateFlow(0)
    /** false = player leaders, true = team rankings. */
    val teamsMode = MutableStateFlow(false)
    val teamStats = MutableStateFlow<LeagueTeamStats?>(null)
    val teamCategory = MutableStateFlow(0)
    val teamError = MutableStateFlow(false)
    private val cache = java.util.concurrent.ConcurrentHashMap<League, List<LeaderCategory>>()
    init { select(League.NBA) }
    fun select(l: League) {
        league.value = l; leaders.value = cache[l]; category.value = 0; teamCategory.value = 0
        teamStats.value = null; teamError.value = false
        viewModelScope.launch(Dispatchers.Default) { runCatching { StatsApi.leaders(l) }.onSuccess { cache[l] = it; if (league.value == l) leaders.value = it }.onFailure { if (league.value == l) leaders.value = emptyList() } }
        viewModelScope.launch(Dispatchers.Default) { runCatching { LeagueStats.teams(l) }.onSuccess { if (league.value == l) teamStats.value = it }.onFailure { if (league.value == l) teamError.value = true } }
    }
}

@Composable
fun StatsHubScreen(onTeam: (League, String) -> Unit, onCompare: (League) -> Unit, onH2H: (League) -> Unit, onSim: (League) -> Unit, vm: StatsHubViewModel = viewModel()) {
    val league by vm.league.collectAsState()
    val leaders by vm.leaders.collectAsState()
    val cat by vm.category.collectAsState()
    val teamsMode by vm.teamsMode.collectAsState()
    val teamStats by vm.teamStats.collectAsState()
    val teamCat by vm.teamCategory.collectAsState()
    val teamError by vm.teamError.collectAsState()
    val favs by com.dez.sports.data.Favorites.teams.collectAsState()
    val favIds = favs.filter { it.league == league }.map { it.teamId }.toSet()
    val openPlayer = LocalOpenPlayer.current
    val openRoute = LocalOpenRoute.current
    Column(Modifier.fillMaxSize().background(Dez.Bg)) {
        ScreenTitle("Stats", "Leaders · compare · history")
        LeaguePicker(League.entries.filter { it != League.PGA }, league, vm::select)
        LazyVerticalGrid(GridCells.Adaptive(340.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolCard("Compare\nPlayers", Icons.Filled.CompareArrows, Color(0xFF0A84FF), Modifier.weight(1f)) { onCompare(league) }
                    ToolCard("Head-to-\nHead", Icons.Filled.History, Color(0xFFFF9F0A), Modifier.weight(1f)) { onH2H(league) }
                    ToolCard("Playoff\nSimulator", Icons.Filled.Casino, Color(0xFF34C759), Modifier.weight(1f)) {
                        onSim(if (league in Simulator.supported) league else League.NFL)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle(if (teamsMode) "Team Rankings" else "League Leaders") }
                    ModeSwitch(teamsMode) { vm.teamsMode.value = it }
                }
            }
            if (teamsMode) {
                val ts = teamStats
                when {
                    ts == null && teamError -> item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("Couldn't load team stats") }
                    ts == null -> item(span = { GridItemSpan(maxLineSpan) }) { SkeletonCards(3) }
                    ts.specs.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("No team stats yet", "Check back once the season is underway.") }
                    else -> {
                        item(span = { GridItemSpan(maxLineSpan) }) { CategoryChips(ts.specs.map { it.label }, teamCat) { vm.teamCategory.value = it } }
                        val spec = ts.specs.getOrNull(teamCat) ?: ts.specs.first()
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            AnimatedContent(spec, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "teams") { sp ->
                                TeamBoard(ts, sp, favIds) { id -> onTeam(league, id) }
                            }
                        }
                        // Each favourite team's full rank sheet, one tap from the leaderboard.
                        favIds.filter { it in ts.teams }.forEach { id ->
                            item(key = "fav-$id") { com.dez.sports.ui.stats.TeamRanksCard(ts, id) }
                        }
                    }
                }
                return@LazyVerticalGrid
            }
            val l = leaders
            when {
                l == null -> item(span = { GridItemSpan(maxLineSpan) }) { SkeletonCards(3) }
                l.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("No leaders yet", "Check back once the season is underway.") }
                else -> {
                    item(span = { GridItemSpan(maxLineSpan) }) { CategoryChips(l.map { it.name }, cat) { vm.category.value = it } }
                    val c = l.getOrNull(cat) ?: l.first()
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        AnimatedContent(c, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "leaders") { cc ->
                            LeaderBoard(cc, favIds, onViewAll = { openRoute(playerListRoute(league, cc.key, cc.name)) }) { id -> openPlayer?.invoke(league, id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSwitch(teams: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Dez.Card).padding(3.dp)) {
        listOf("Players" to false, "Teams" to true).forEach { (label, v) ->
            val sel = v == teams
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (sel) Dez.Text else Dez.Sub,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Dez.Card2 else Color.Transparent).clickable { onChange(v) }.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun CategoryChips(names: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(names.size) { i ->
            val sel = i == selected
            Text(names[i], style = MaterialTheme.typography.labelLarge, color = if (sel) Color.Black else Dez.Text,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Dez.Text else Dez.Card2).clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun ToolCard(title: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Brush.verticalGradient(listOf(color.copy(alpha = .28f), Dez.Card))).clickable(onClick = onClick).padding(14.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = Dez.Text)
    }
}

@Composable
private fun LeaderBoard(c: LeaderCategory, favIds: Set<String>, onViewAll: () -> Unit, onPlayer: (String) -> Unit) {
    val top = c.entries.first()
    val tc = top.team?.let { teamColor(it.color, it.altColor) } ?: Dez.Card2
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Dez.Card)) {
        // #1 gets a hero row
        Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(tc.copy(alpha = .55f), Dez.Card))).clickable { onPlayer(top.athleteId) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(top.headshot, top.name, Modifier.size(84.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = .2f)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("1  ·  ${c.name.uppercase()}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .75f))
                Text(top.name, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                top.team?.let { Row(verticalAlignment = Alignment.CenterVertically) { TeamLogo(it, 16.dp); Spacer(Modifier.width(4.dp)); Text(it.displayName, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .75f)) } }
            }
            Text(top.value, style = MaterialTheme.typography.headlineLarge.merge(TabularNums), color = Color.White)
        }
        c.entries.drop(1).forEach { e ->
            val mine = e.team?.id in favIds
            Row(Modifier.fillMaxWidth().then(if (mine) Modifier.background(teamColor(e.team!!.color, e.team.altColor).copy(alpha = .18f)) else Modifier).clickable { onPlayer(e.athleteId) }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${e.rank}", style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = Dez.Sub, modifier = Modifier.width(26.dp))
                AsyncImage(e.headshot, e.name, Modifier.size(36.dp).clip(CircleShape).background(Dez.Card2), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name, style = MaterialTheme.typography.titleSmall, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    e.team?.let { Text(it.abbreviation, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = Dez.Sub) }
                }
                e.team?.let { TeamLogo(it, 20.dp); Spacer(Modifier.width(10.dp)) }
                Text(e.value, style = MaterialTheme.typography.titleMedium.merge(TabularNums), color = Dez.Text)
            }
        }
        Text("View full list ›", style = MaterialTheme.typography.labelLarge, color = Color(0xFF64D2FF),
            modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 12.dp).clip(RoundedCornerShape(50)).clickable(onClick = onViewAll).padding(vertical = 4.dp))
    }
}
