package com.dez.sports.ui.stats

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dez.sports.data.Favorites
import com.dez.sports.data.SimGame
import com.dez.sports.data.SimSetup
import com.dez.sports.data.SimTeamResult
import com.dez.sports.data.Simulator
import com.dez.sports.model.League
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.teamColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SimulatorViewModel(handle: SavedStateHandle) : ViewModel() {
    val league = MutableStateFlow(League.valueOf(handle["league"]!!).takeIf { it in Simulator.supported } ?: League.NFL)
    val setup = MutableStateFlow<SimSetup?>(null)
    val picks = MutableStateFlow<Map<String, String>>(emptyMap())
    val results = MutableStateFlow<List<SimTeamResult>?>(null)
    val baseline = MutableStateFlow<Map<String, SimTeamResult>>(emptyMap())
    val running = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var job: Job? = null

    init { load() }

    fun select(l: League) { if (l != league.value) { league.value = l; load() } }

    private fun load() {
        setup.value = null; results.value = null; picks.value = emptyMap(); error.value = null
        val l = league.value
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { Simulator.setup(l) }.onSuccess { s ->
                if (league.value != l) return@onSuccess
                setup.value = s
                val base = withContext(Dispatchers.Default) { Simulator.run(s, emptyMap()) }
                baseline.value = base.associateBy { it.team.id }
                results.value = base
            }.onFailure { error.value = it.message }
        }
    }

    /** Tap a team to pick it; tap again to go back to "simulate". */
    fun pick(g: SimGame, teamId: String) {
        picks.value = picks.value.toMutableMap().also { if (it[g.id] == teamId) it.remove(g.id) else it[g.id] = teamId }
        rerun()
    }

    fun reset() { picks.value = emptyMap(); rerun() }

    private fun rerun() {
        val s = setup.value ?: return
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.Default) {
            delay(250)
            running.value = true
            results.value = withContext(Dispatchers.Default) { Simulator.run(s, picks.value) }
            running.value = false
        }
    }
}

@Composable
fun SimulatorScreen(onBack: () -> Unit, vm: SimulatorViewModel = viewModel()) {
    val league by vm.league.collectAsState()
    val setup by vm.setup.collectAsState()
    val picks by vm.picks.collectAsState()
    val results by vm.results.collectAsState()
    val baseline by vm.baseline.collectAsState()
    val running by vm.running.collectAsState()
    val error by vm.error.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize().background(Dez.Bg)) {
        val wide = maxWidth >= 700.dp
        val header: LazyListScope.() -> Unit = {
            item {
                Column(Modifier.statusBarsPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(8.dp).size(40.dp).clip(CircleShape).background(Dez.Card2).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Dez.Text)
                        }
                        Text("Playoff Simulator", style = MaterialTheme.typography.headlineSmall, color = Dez.Text, modifier = Modifier.weight(1f))
                        if (picks.isNotEmpty()) Text("Reset ${picks.size}", style = MaterialTheme.typography.labelLarge, color = Dez.Accent, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { vm.reset() }.padding(12.dp))
                    }
                    LeaguePicker(Simulator.supported, league, vm::select)
                    setup?.let { Text(it.note, style = MaterialTheme.typography.bodySmall, color = Dez.Sub, modifier = Modifier.padding(horizontal = 16.dp)) }
                    if (running || (setup == null && error == null)) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), color = Dez.Accent, trackColor = Dez.Card)
                }
            }
            error?.let { e -> item { EmptyState("Couldn't build the simulator", e) } }
        }
        val s = setup
        val resultsList: LazyListScope.() -> Unit = { if (s != null) resultsContent(s, results, baseline) }
        val gamesList: LazyListScope.() -> Unit = { if (s != null) gamesContent(s, picks, vm::pick) }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { header(); gamesList() }
                LazyColumn(Modifier.weight(1f).statusBarsPadding(), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { resultsList() }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                header(); resultsList(); gamesList()
            }
        }
    }
}

private fun LazyListScope.resultsContent(s: SimSetup, results: List<SimTeamResult>?, baseline: Map<String, SimTeamResult>) {
    val r = results ?: return
    val favIds = Favorites.teams.value.filter { it.league == s.league }.map { it.teamId }.toSet()
    val mine = r.filter { it.team.id in favIds }
    item { Box(Modifier.padding(horizontal = 16.dp)) { SectionTitle("Projected field") } }
    mine.forEach { t -> item(key = "my-${t.team.id}") { Box(Modifier.padding(horizontal = 12.dp)) { MyTeamOdds(t, baseline[t.team.id], s.metricLabels) } } }
    item {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
            Text("TEAM", style = MaterialTheme.typography.labelSmall, color = Dez.Sub, modifier = Modifier.weight(1f))
            Text("PROJ", style = MaterialTheme.typography.labelSmall, color = Dez.Sub, textAlign = TextAlign.Center, modifier = Modifier.width(60.dp))
            Text(s.metricLabels.second, style = MaterialTheme.typography.labelSmall, color = Dez.Sub, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
            Text(s.metricLabels.first, style = MaterialTheme.typography.labelSmall, color = Dez.Sub, textAlign = TextAlign.End, modifier = Modifier.width(84.dp))
        }
    }
    val shown = if (s.league == League.CFB) r.filter { it.playoff >= .5 || it.team.id in favIds }.take(30) else r
    val grouped = if (s.league == League.CFB) mapOf("" to shown) else shown.groupBy { it.conference }
    grouped.forEach { (conf, list) ->
        if (conf.isNotEmpty()) item(key = "conf-$conf") { Text(conf.uppercase(), style = MaterialTheme.typography.labelMedium, color = Dez.Sub, modifier = Modifier.padding(start = 24.dp, top = 8.dp)) }
        items(list, key = { "res-${it.team.id}" }) { t -> ResultRow(t, baseline[t.team.id], t.team.id in favIds, Modifier.animateItem()) }
    }
}

private fun pct(v: Double) = when { v >= 99.95 -> "100%"; v < .5 && v > 0 -> "<1%"; v == 0.0 -> "0%"; else -> "%.0f%%".format(v) }

@Composable
private fun Delta(now: Double, base: Double?) {
    val d = base?.let { now - it } ?: 0.0
    if (kotlin.math.abs(d) < 1) return
    Text((if (d > 0) "▲" else "▼") + "%.0f".format(kotlin.math.abs(d)), style = MaterialTheme.typography.labelSmall, color = if (d > 0) Dez.Win else Dez.Live, modifier = Modifier.padding(end = 4.dp))
}

@Composable
private fun ResultRow(t: SimTeamResult, base: SimTeamResult?, fav: Boolean, modifier: Modifier) {
    val tc = teamColor(t.team.color, t.team.altColor)
    val bar by animateFloatAsState((t.playoff / 100).toFloat(), tween(600), label = "po")
    Column(modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (fav) tc.copy(alpha = .22f) else Dez.Card).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(t.team, 24.dp); Spacer(Modifier.width(8.dp))
            Text(t.team.displayName, style = MaterialTheme.typography.labelLarge, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("%.1f-%.1f".format(t.projW, t.projL), style = MaterialTheme.typography.labelMedium.merge(TabularNums), color = Dez.Sub, textAlign = TextAlign.Center, modifier = Modifier.width(60.dp))
            Text(pct(t.second), style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = Dez.Sub, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
            Row(Modifier.width(84.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Delta(t.playoff, base?.playoff)
                Text(pct(t.playoff), style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = if (t.playoff >= 75) Dez.Win else Dez.Text)
            }
        }
        ProgressBar(bar, tc, Modifier.fillMaxWidth().padding(top = 6.dp), 3.dp)
    }
}

@Composable
private fun MyTeamOdds(t: SimTeamResult, base: SimTeamResult?, labels: Pair<String, String>) {
    val tc = teamColor(t.team.color, t.team.altColor)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(tc.copy(alpha = .22f)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(t.team, 44.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.team.displayName, style = MaterialTheme.typography.titleMedium, color = Dez.Text)
            Text("Projected %.1f-%.1f".format(t.projW, t.projL), style = MaterialTheme.typography.bodySmall, color = Dez.Sub)
            base?.let { b ->
                val d = t.playoff - b.playoff
                if (kotlin.math.abs(d) >= 1) Text("${if (d > 0) "▲" else "▼"} %.0f pts from your picks".format(kotlin.math.abs(d)), style = MaterialTheme.typography.labelMedium, color = if (d > 0) Dez.Win else Dez.Live)
            }
        }
        listOf(labels.first to t.playoff, labels.second to t.second).forEach { (l, v) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 8.dp)) {
                RingProgress((v / 100).toFloat(), tc, 60.dp, 6.dp) { Text(pct(v), style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = Dez.Text) }
                Text(l, style = MaterialTheme.typography.labelSmall, color = Dez.Sub, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

private fun LazyListScope.gamesContent(s: SimSetup, picks: Map<String, String>, onPick: (SimGame, String) -> Unit) {
    if (s.games.isEmpty()) { item { EmptyState("No games left to pick", "Everything remaining is simulated.") }; return }
    val favIds = Favorites.teams.value.filter { it.league == s.league }.map { it.teamId }.toSet()
    item { Box(Modifier.padding(horizontal = 16.dp)) { SectionTitle("Pick winners") } }
    item { Text("Tap a team to lock in a win. Your teams' games are listed first each week.", style = MaterialTheme.typography.bodySmall, color = Dez.Sub, modifier = Modifier.padding(horizontal = 16.dp)) }
    s.games.groupBy { it.label }.forEach { (label, games) ->
        item(key = "wk-$label") { Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = Dez.Sub, modifier = Modifier.padding(start = 24.dp, top = 10.dp)) }
        val sorted = games.sortedByDescending { it.home.id in favIds || it.away.id in favIds }
        items(sorted, key = { "g-${it.id}" }) { g -> PickRow(s, g, picks[g.id], onPick) }
    }
}

@Composable
private fun PickRow(s: SimSetup, g: SimGame, picked: String?, onPick: (SimGame, String) -> Unit) {
    val ph = remember(g.id) { Simulator.homeWinProb(s, g) }
    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dez.Card).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        PickSide(g.away, picked, 1 - ph, Modifier.weight(1f)) { onPick(g, g.away.id) }
        Text(if (picked == null) "SIM" else "@", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black), color = Dez.Faint, modifier = Modifier.padding(horizontal = 8.dp))
        PickSide(g.home, picked, ph, Modifier.weight(1f)) { onPick(g, g.home.id) }
    }
}

@Composable
private fun PickSide(t: com.dez.sports.model.Team, picked: String?, prob: Double, modifier: Modifier, onClick: () -> Unit) {
    val tc = teamColor(t.color, t.altColor)
    val sel = picked == t.id
    val lost = picked != null && !sel
    val bg by animateColorAsState(if (sel) tc.copy(alpha = .55f) else Color.Transparent, tween(200), label = "pick")
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(bg).then(if (sel) Modifier.border(1.5.dp, Color.White.copy(alpha = .7f), RoundedCornerShape(12.dp)) else Modifier)
        .clickable(onClick = onClick).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.graphicsLayer { alpha = if (lost) .35f else 1f }) { TeamLogo(t, 26.dp) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(t.abbreviation, style = MaterialTheme.typography.titleSmall, color = if (lost) Dez.Faint else Dez.Text)
            Text(if (sel) "WIN ✓" else "%.0f%%".format(prob * 100), style = MaterialTheme.typography.labelSmall, color = if (sel) Color.White else Dez.Sub)
        }
    }
}
