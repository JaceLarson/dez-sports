package com.dez.sports.ui.projections

import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dez.sports.data.EspnApi
import com.dez.sports.data.Favorites
import com.dez.sports.model.League
import com.dez.sports.model.Projection
import com.dez.sports.model.ProjectionTable
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.teamColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ProjectionsViewModel : ViewModel() {
    val league = MutableStateFlow(League.NFL)
    val tables = MutableStateFlow<List<ProjectionTable>?>(null)
    val error = MutableStateFlow<String?>(null)
    private val cache = mutableMapOf<League, List<ProjectionTable>>()
    init { select(League.NFL) }
    fun select(l: League) {
        league.value = l; tables.value = cache[l]; error.value = null
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { EspnApi.projections(l) }.onSuccess { cache[l] = it; if (league.value == l) tables.value = it }
                .onFailure { if (league.value == l) error.value = it.message }
        }
    }
}

@Composable
fun ProjectionsScreen(onTeam: (League, String) -> Unit, onSimulate: (League) -> Unit = {}, vm: ProjectionsViewModel = viewModel()) {
    val league by vm.league.collectAsState()
    val tables by vm.tables.collectAsState()
    val error by vm.error.collectAsState()
    val favs by Favorites.teams.collectAsState()
    Column(Modifier.fillMaxSize().background(Dez.Bg)) {
        ScreenTitle("Outlook", "Playoff predictions")
        LeaguePicker(League.entries.filter { it.hasProjections }, league, vm::select)
        AnimatedContent(tables to league, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "proj") { (t, l) ->
            when {
                t == null && error != null -> EmptyState("Couldn't load projections", error)
                t == null -> Column(Modifier.padding(top = 8.dp)) { SkeletonCards(5) }
                else -> {
                    val table = t.firstOrNull() ?: return@AnimatedContent EmptyState("No projections")
                    val myTeams = table.rows.filter { r -> favs.any { it.league == l && it.teamId == r.team.id } }
                    LazyVerticalGrid(GridCells.Adaptive(380.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(Modifier.padding(horizontal = 6.dp)) {
                                Text(table.title, style = MaterialTheme.typography.titleLarge, color = Dez.Text)
                                Text(table.blurb, style = MaterialTheme.typography.bodySmall, color = Dez.Sub)
                            }
                        }
                        if (l in com.dez.sports.data.Simulator.supported) item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Dez.Win.copy(alpha = .15f)).clickable { onSimulate(l) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("What if…?", style = MaterialTheme.typography.titleMedium, color = Dez.Text)
                                    Text("Pick winners and watch these odds change in the playoff simulator", style = MaterialTheme.typography.bodySmall, color = Dez.Sub)
                                }
                                Text("Simulate ›", style = MaterialTheme.typography.labelLarge, color = Dez.Win)
                            }
                        }
                        if (myTeams.isNotEmpty()) items(myTeams, key = { "my-${it.team.id}" }, span = { GridItemSpan(maxLineSpan) }) { MyTeamOutlook(it, table) { onTeam(l, it.team.id) } }
                        item(span = { GridItemSpan(maxLineSpan) }) { HeaderRow(table) }
                        items(table.rows, key = { it.team.id }) { r -> ProjRow(r, table, favs.any { it.league == l && it.teamId == r.team.id }) { onTeam(l, r.team.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyTeamOutlook(p: Projection, t: ProjectionTable, onClick: () -> Unit) {
    val tc = teamColor(p.team.color, p.team.altColor)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(tc.copy(alpha = .2f)).clickable(onClick = onClick).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(p.team, 44.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.team.displayName, style = MaterialTheme.typography.titleMedium, color = Dez.Text)
                Text("${p.record} now · projected ${p.projRecord}" + (p.extra?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = Dez.Sub)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(t.ratingName, style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
                Text("${p.rating}" + (p.ratingRank?.let { "  #$it" } ?: ""), style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = Dez.Text)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            p.columns.forEachIndexed { i, (_, v) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RingProgress((v ?: 0f) / 100f, tc, 64.dp, 6.dp) {
                        Text(pct(v), style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = Dez.Text)
                    }
                    Text(t.columns.getOrNull(i) ?: "", style = MaterialTheme.typography.labelSmall, color = Dez.Sub, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(t: ProjectionTable) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("TEAM", style = MaterialTheme.typography.labelSmall, color = Dez.Sub, modifier = Modifier.weight(1f))
        t.columns.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = Dez.Sub, textAlign = TextAlign.Center, modifier = Modifier.width(58.dp)) }
    }
}

@Composable
private fun ProjRow(p: Projection, t: ProjectionTable, fav: Boolean, onClick: () -> Unit) {
    val tc = teamColor(p.team.color, p.team.altColor)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (fav) tc.copy(alpha = .22f) else Dez.Card).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(p.team, 26.dp); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(p.team.displayName,
                    style = MaterialTheme.typography.labelLarge, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.record} · proj ${p.projRecord}" + (p.extra?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = Dez.Sub, maxLines = 1)
            }
            p.columns.forEach { (_, v) ->
                Text(pct(v), style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = heat(v), textAlign = TextAlign.Center, modifier = Modifier.width(58.dp))
            }
        }
        ProgressBar((p.columns.firstOrNull()?.second ?: 0f) / 100f, tc, Modifier.fillMaxWidth().padding(top = 8.dp), 4.dp)
    }
}

private fun pct(v: Float?) = when { v == null -> "–"; v >= 99.95f -> "100%"; v > 0f && v < 0.5f -> "<1%"; v >= 10f -> "%.0f%%".format(v); else -> "%.1f%%".format(v) }
private fun heat(v: Float?) = when { v == null -> Dez.Faint; v >= 75 -> Dez.Win; v >= 35 -> Dez.Text; v >= 5 -> Dez.Sub; else -> Dez.Faint }
