package com.dez.sports.ui.stats

import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dez.sports.data.Meeting
import com.dez.sports.data.StatsApi
import com.dez.sports.model.League
import com.dez.sports.model.Team
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.darken
import com.dez.sports.ui.theme.lighten
import com.dez.sports.ui.theme.teamColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HeadToHeadViewModel(handle: SavedStateHandle) : ViewModel() {
    val league = MutableStateFlow(League.valueOf(handle["league"]!!))
    val a = MutableStateFlow<Team?>(null)
    val b = MutableStateFlow<Team?>(null)
    val seasons = MutableStateFlow(10)
    val meetings = MutableStateFlow<List<Meeting>?>(null)
    val progress = MutableStateFlow(0f)
    private var job: Job? = null

    init {
        val ia = handle.get<String>("a"); val ib = handle.get<String>("b")
        if (!ia.isNullOrBlank() || !ib.isNullOrBlank()) viewModelScope.launch(Dispatchers.Default) {
            val all = runCatching { StatsApi.teams(league.value) }.getOrDefault(emptyList())
            a.value = all.firstOrNull { it.id == ia }; b.value = all.firstOrNull { it.id == ib }
            load()
        }
    }

    fun setLeague(l: League) { if (l != league.value) { league.value = l; a.value = null; b.value = null; meetings.value = null } }
    fun set(slot: Int, t: Team) { if (slot == 0) a.value = t else b.value = t; load() }
    fun swap() { val t = a.value; a.value = b.value; b.value = t; load() }
    fun setSeasons(n: Int) { seasons.value = n; load() }

    private fun load() {
        val ta = a.value ?: return; val tb = b.value ?: return
        job?.cancel(); meetings.value = null; progress.value = 0f
        job = viewModelScope.launch(Dispatchers.Default) {
            meetings.value = runCatching { StatsApi.headToHead(league.value, ta.id, tb.id, seasons.value) { progress.value = it } }.getOrDefault(emptyList())
        }
    }
}

@Composable
fun HeadToHeadScreen(onBack: () -> Unit, onGame: (League, String) -> Unit, vm: HeadToHeadViewModel = viewModel()) {
    val league by vm.league.collectAsState()
    val a by vm.a.collectAsState(); val b by vm.b.collectAsState()
    val meetings by vm.meetings.collectAsState()
    val progress by vm.progress.collectAsState()
    val seasons by vm.seasons.collectAsState()
    var picking by remember { mutableStateOf(-1) }
    val ca = a?.let { teamColor(it.color, it.altColor) } ?: Dez.Accent
    val cb = b?.let { secondColor(ca, it) } ?: Color(0xFFFF9F0A)

    LazyColumn(Modifier.fillMaxSize().background(Dez.Bg), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(ca.darken(.3f), Dez.Bg, cb.darken(.3f))))) {
                Column(Modifier.statusBarsPadding().padding(bottom = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(8.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .25f)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                        }
                        Text("HEAD-TO-HEAD", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .8f))
                    }
                    LeaguePicker(League.entries.filter { it != League.PGA }, league, vm::setLeague)
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TeamSlot(a, Modifier.weight(1f)) { picking = 0 }
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .1f)).clickable { vm.swap() }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.SwapHoriz, "Swap", tint = Color.White)
                        }
                        TeamSlot(b, Modifier.weight(1f)) { picking = 1 }
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("SEASONS", style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
                listOf(5, 10, 20, 30).forEach { n ->
                    Text("Last $n", style = MaterialTheme.typography.labelLarge, color = if (n == seasons) Color.Black else Dez.Text,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (n == seasons) Dez.Text else Dez.Card2).clickable { vm.setSeasons(n) }.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        if (a == null || b == null) {
            item { Text("Pick any two teams to see every meeting, streaks and margins.", style = MaterialTheme.typography.bodyMedium, color = Dez.Sub, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp)) }
        } else {
            val m = meetings
            if (m == null) item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Digging through $seasons seasons of schedules…", style = MaterialTheme.typography.bodyMedium, color = Dez.Sub)
                    LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(top = 8.dp), color = ca, trackColor = Dez.Card)
                }
            } else if (m.isEmpty()) item { EmptyState("No meetings found", "These teams haven't played in the last $seasons seasons.") }
            else {
                item { Box(Modifier.padding(horizontal = 12.dp)) { Summary(a!!, b!!, m, ca, cb) } }
                item { Box(Modifier.padding(horizontal = 12.dp)) { MarginChart(m, ca, cb) } }
                item { Box(Modifier.padding(horizontal = 16.dp)) { SectionTitle("Every meeting") } }
                items(m, key = { it.eventId }) { mt -> MeetingRow(mt, a!!, b!!, ca, cb) { onGame(league, mt.eventId) } }
            }
        }
    }
    if (picking >= 0) TeamPickerSheet(league, onDismiss = { picking = -1 }) { t -> vm.set(picking, t); picking = -1 }
}

@Composable
private fun TeamSlot(t: Team?, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (t == null) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(Color.White.copy(alpha = .08f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, null, tint = Color.White) }
            Text("Pick team", style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.padding(top = 6.dp))
        } else {
            TeamLogo(t, 84.dp)
            Text(t.displayName, style = MaterialTheme.typography.titleSmall, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Summary(a: Team, b: Team, m: List<Meeting>, ca: Color, cb: Color) {
    val aw = m.count { it.aWon }; val ties = m.count { it.tie }; val bw = m.size - aw - ties
    // current streak
    val first = m.first()
    val streakTeam = if (first.tie) null else if (first.aWon) a else b
    val streak = if (streakTeam == null) 0 else m.takeWhile { !it.tie && it.aWon == first.aWon }.size
    // longest streaks
    fun longest(aSide: Boolean): Int { var best = 0; var cur = 0; m.reversed().forEach { if (!it.tie && it.aWon == aSide) { cur++; best = maxOf(best, cur) } else cur = 0 }; return best }
    val post = m.filter { it.postseason }
    val homeA = m.filter { it.homeIsA }; val awayA = m.filter { !it.homeIsA }
    val bigA = m.filter { it.aWon }.maxByOrNull { it.aScore - it.bScore }; val bigB = m.filter { !it.aWon && !it.tie }.maxByOrNull { it.bScore - it.aScore }
    val leader = when { aw > bw -> "${a.name} lead $aw-$bw" ; bw > aw -> "${b.name} lead $bw-$aw"; else -> "Series tied $aw-$bw" } + if (ties > 0) "-$ties" else ""
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Dez.Card).padding(16.dp)) {
        Text(leader, style = MaterialTheme.typography.headlineSmall, color = Dez.Text)
        Text("${m.size} meetings · ${m.last().season} to ${m.first().season}", style = MaterialTheme.typography.bodySmall, color = Dez.Sub)
        Spacer(Modifier.height(12.dp))
        val fa = remember(m) { Animatable(0f) }
        LaunchedEffect(m) { fa.animateTo(aw.toFloat() / m.size.coerceAtLeast(1), tween(900, easing = FastOutSlowInEasing)) }
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            drawRoundRect(cb, cornerRadius = CornerRadius(size.height / 2))
            drawRoundRect(ca, size = Size(size.width * fa.value, size.height), cornerRadius = CornerRadius(size.height / 2))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("${a.abbreviation} $aw", style = MaterialTheme.typography.labelLarge, color = ca.lighten(.3f), modifier = Modifier.weight(1f))
            Text("$bw ${b.abbreviation}", style = MaterialTheme.typography.labelLarge, color = cb.lighten(.3f))
        }
        Spacer(Modifier.height(14.dp))
        val rows = listOfNotNull(
            "Current streak" to (streakTeam?.let { "${it.abbreviation} W$streak" } ?: "Tie"),
            "Longest streaks" to "${a.abbreviation} ${longest(true)} · ${b.abbreviation} ${longest(false)}",
            "${a.abbreviation} at home" to "${homeA.count { it.aWon }}-${homeA.count { !it.aWon && !it.tie }}",
            "${a.abbreviation} on the road" to "${awayA.count { it.aWon }}-${awayA.count { !it.aWon && !it.tie }}",
            if (post.isNotEmpty()) "Postseason" to "${a.abbreviation} ${post.count { it.aWon }}-${post.count { !it.aWon && !it.tie }}" else null,
            "Avg score" to "%.1f – %.1f".format(m.map { it.aScore }.average(), m.map { it.bScore }.average()),
            bigA?.let { "Biggest ${a.abbreviation} win" to "${it.aScore}-${it.bScore} (${it.season})" },
            bigB?.let { "Biggest ${b.abbreviation} win" to "${it.bScore}-${it.aScore} (${it.season})" },
        )
        rows.forEach { (k, v) ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text(k, style = MaterialTheme.typography.bodyMedium, color = Dez.Sub, modifier = Modifier.weight(1f))
                Text(v, style = MaterialTheme.typography.bodyMedium.merge(TabularNums), color = Dez.Text)
            }
        }
    }
}

/** Each meeting as a bar: up = team A won by that much, down = team B. Oldest on the left. */
@Composable
private fun MarginChart(m: List<Meeting>, ca: Color, cb: Color) {
    val games = m.reversed()
    val max = games.maxOf { kotlin.math.abs(it.aScore - it.bScore) }.coerceAtLeast(1)
    val anim = remember(m) { Animatable(0f) }
    LaunchedEffect(m) { anim.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(14.dp)) {
        Text("MARGIN OF VICTORY, OLDEST → NEWEST", style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val mid = size.height / 2; val n = games.size
            val gap = 3f; val bw = ((size.width - gap * (n - 1)) / n).coerceIn(2f, 22.dp.toPx())
            val start = (size.width - (bw * n + gap * (n - 1))) / 2
            drawLine(Dez.Hairline, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 2f)
            games.forEachIndexed { i, g ->
                val d = (g.aScore - g.bScore).toFloat()
                val h = (mid - 4) * kotlin.math.abs(d) / max * anim.value
                val x = start + i * (bw + gap)
                if (d >= 0) drawRoundRect(if (g.postseason) ca.lighten(.3f) else ca, Offset(x, mid - h), Size(bw, h.coerceAtLeast(2f)), CornerRadius(4f))
                else drawRoundRect(if (g.postseason) cb.lighten(.3f) else cb, Offset(x, mid), Size(bw, h), CornerRadius(4f))
            }
        }
    }
}

@Composable
private fun MeetingRow(mt: Meeting, a: Team, b: Team, ca: Color, cb: Color, onClick: () -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dez.Card).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(if (mt.tie) Dez.Faint else if (mt.aWon) ca else cb))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(localDay(mt.dateIso), style = MaterialTheme.typography.labelLarge, color = Dez.Text)
            Text(listOfNotNull(mt.season, if (mt.homeIsA) "at ${a.abbreviation}" else "at ${b.abbreviation}", mt.note).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Dez.Sub, maxLines = 1)
        }
        if (mt.postseason) { Pill("PLAYOFF", Dez.Gold.copy(alpha = .2f), Dez.Gold); Spacer(Modifier.width(8.dp)) }
        TeamLogo(a, 20.dp); Spacer(Modifier.width(6.dp))
        Text("${mt.aScore}-${mt.bScore}", style = MaterialTheme.typography.titleMedium.merge(TabularNums).copy(fontWeight = FontWeight.Bold), color = Dez.Text)
        Spacer(Modifier.width(6.dp)); TeamLogo(b, 20.dp)
    }
}
