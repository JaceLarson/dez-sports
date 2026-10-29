package com.dez.sports.ui.player

import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.dez.sports.data.PlayerApi
import com.dez.sports.model.*
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.darken
import com.dez.sports.ui.theme.lighten
import com.dez.sports.ui.theme.teamColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The player's stat line in a game happening right now (one entry per box-score table they appear in). */
data class LiveLine(val game: Game, val tables: List<Pair<String, List<Pair<String, String>>>>)

class PlayerViewModel(handle: SavedStateHandle) : ViewModel() {
    val league: League = League.valueOf(handle["league"]!!)
    val id: String = handle["id"]!!
    val athlete = MutableStateFlow<Athlete?>(null)
    val gameLog = MutableStateFlow<GameLog?>(null)
    val career = MutableStateFlow<List<CareerTable>?>(null)
    val error = MutableStateFlow<String?>(null)
    val loadingLog = MutableStateFlow(true)
    val splits = MutableStateFlow<List<CareerTable>?>(null)
    val live = MutableStateFlow<LiveLine?>(null)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            splits.value = runCatching { com.dez.sports.data.StatsApi.splits(league, id) }.getOrDefault(emptyList())
                .map { t -> CareerTable(t.title, t.labels, emptyList(), t.rows.map { (name, stats) -> CareerRow(name, null, stats) }, null) }
        }
        viewModelScope.launch(Dispatchers.Default) { watchLive() }
        viewModelScope.launch(Dispatchers.Default) { runCatching { PlayerApi.athlete(league, id) }.onSuccess { athlete.value = it }.onFailure { error.value = it.message } }
        loadSeason(null)
        viewModelScope.launch(Dispatchers.Default) { runCatching { PlayerApi.career(league, id) }.onSuccess { career.value = it }.onFailure { career.value = emptyList() } }
    }

    /** If the player's team is playing right now, refresh their box-score line every 20 seconds. */
    private suspend fun watchLive() {
        while (athlete.value == null) kotlinx.coroutines.delay(300)
        val teamId = athlete.value?.team?.id ?: return
        while (true) {
            val game = runCatching { com.dez.sports.data.EspnApi.scoreboard(league) }.getOrNull()?.games
                ?.firstOrNull { it.involves(teamId) && it.state == GameState.LIVE }
            if (game == null) { live.value = null; return }
            runCatching { com.dez.sports.data.EspnApi.summary(league, game.id) }.onSuccess { d ->
                val lines = d.boxes.flatMap { b -> b.tables.mapNotNull { t -> t.rows.firstOrNull { it.id == id }?.let { r -> t.title to t.labels.zip(r.stats) } } }
                live.value = if (lines.isEmpty()) null else LiveLine(d.game, lines)
            }
            kotlinx.coroutines.delay(20_000)
        }
    }

    fun loadSeason(season: String?) {
        loadingLog.value = true
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { PlayerApi.gameLog(league, id, season) }
                .onSuccess { gameLog.value = it }
                .onFailure { if (gameLog.value == null) gameLog.value = GameLog(emptyList(), emptyList(), emptyList(), emptyList(), null, emptyList()) }
            loadingLog.value = false
        }
    }
}

@Composable
fun PlayerScreen(onBack: () -> Unit, onGame: (League, String) -> Unit, onTeam: (League, String) -> Unit, onCompare: (League, String) -> Unit = { _, _ -> }, vm: PlayerViewModel = viewModel()) {
    val a by vm.athlete.collectAsState()
    val log by vm.gameLog.collectAsState()
    val career by vm.career.collectAsState()
    val loadingLog by vm.loadingLog.collectAsState()
    val splits by vm.splits.collectAsState()
    val live by vm.live.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chartCol by rememberSaveable { mutableIntStateOf(-1) }
    val tc = a?.team?.let { teamColor(it.color, it.altColor) } ?: Dez.Card2
    // Form and the shot map only make sense for the current season's log.
    val current = log?.takeIf { it.selectedSeason == null || it.selectedSeason == it.seasons.firstOrNull()?.first }
    val form = remember(current) { current?.let { com.dez.sports.data.Forms.player(it) } }
    val shotIds = remember(current) { current?.sections?.firstOrNull()?.blocks?.flatMap { it.rows }?.sortedByDescending { it.dateIso }?.map { it.eventId }.orEmpty() }
    val extras: LazyListScope.() -> Unit = {
        form?.let { f -> item(key = "form") { FormCard(f, Modifier.padding(horizontal = 12.dp)) } }
        if (shotIds.isNotEmpty() && com.dez.sports.data.SeasonShots.supported(vm.league)) item(key = "shots") {
            com.dez.sports.ui.game.SeasonShotMap(vm.league, shotIds, null, vm.id, tc, "SEASON SHOT MAP", Modifier.padding(horizontal = 12.dp))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Dez.Bg)) {
        val wide = maxWidth >= 700.dp
        val tabs = @Composable { Pills(listOf("Game Log", "Splits", "Career"), tab) { tab = it } }
        val liveCard: LazyListScope.() -> Unit = { live?.let { l -> item(key = "live") { Box(Modifier.padding(horizontal = 12.dp)) { LiveNowCard(l, tc) { onGame(vm.league, l.game.id) } } } } }
        val splitsContent: LazyListScope.() -> Unit = {
            val sp = splits
            when {
                sp == null -> item { SkeletonCards(2) }
                sp.isEmpty() -> item { EmptyState("No splits yet") }
                else -> items(sp, key = { "split-${it.title}" }) { CareerTableView(it, tc) }
            }
        }
        val logContent: LazyListScope.() -> Unit = {
            val l = log
            if (l == null) item { SkeletonCards(3) }
            else gameLogContent(l, vm.league, tc, chartCol, { chartCol = it }, loadingLog, vm::loadSeason) { onGame(vm.league, it) }
        }
        val careerContent: LazyListScope.() -> Unit = {
            val c = career
            when {
                c == null -> item { SkeletonCards(2) }
                c.isEmpty() -> item { EmptyState("No career stats") }
                else -> items(c, key = { "career-${it.title}" }) { CareerTableView(it, tc) }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(.36f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Hero(a, tc, onBack, onTeam, vm.league) { onCompare(vm.league, vm.id) } }
                    a?.let { at -> item { Box(Modifier.padding(horizontal = 12.dp)) { SummaryTiles(at, tc) } } }
                    extras()
                    a?.let { at -> item { Box(Modifier.padding(horizontal = 12.dp)) { BioCard(at) } } }
                }
                LazyColumn(Modifier.weight(.64f).fillMaxHeight().statusBarsPadding(), contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { tabs() }
                    liveCard()
                    when (tab) { 0 -> logContent(); 1 -> splitsContent(); else -> careerContent() }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Hero(a, tc, onBack, onTeam, vm.league) { onCompare(vm.league, vm.id) } }
                liveCard()
                a?.let { at -> item { Box(Modifier.padding(horizontal = 12.dp)) { SummaryTiles(at, tc) } } }
                extras()
                item { Box(Modifier.padding(horizontal = 12.dp)) { tabs() } }
                if (tab == 0) logContent() else if (tab == 1) splitsContent() else {
                    careerContent()
                    a?.let { at -> item { Box(Modifier.padding(horizontal = 12.dp)) { BioCard(at) } } }
                }
            }
        }
    }
}

// ============================================================ header

@Composable
private fun Hero(a: Athlete?, tc: Color, onBack: () -> Unit, onTeam: (League, String) -> Unit, league: League, onCompare: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tc.darken(.05f), tc.darken(.55f), Dez.Bg)))) {
        // Big faded jersey number behind the headshot.
        a?.jersey?.let {
            Text(it, style = MaterialTheme.typography.displayLarge.copy(fontSize = MaterialTheme.typography.displayLarge.fontSize * 4.2f, fontWeight = FontWeight.Black),
                color = Color.White.copy(alpha = .08f), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
        }
        Column(Modifier.statusBarsPadding().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(8.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .25f)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Text("Compare", style = MaterialTheme.typography.labelLarge, color = Color.White,
                    modifier = Modifier.padding(end = 12.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .25f)).clickable(onClick = onCompare).padding(horizontal = 14.dp, vertical = 8.dp))
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                AsyncImage(a?.headshot, a?.name, Modifier.size(132.dp).clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = .2f)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).padding(bottom = 6.dp)) {
                    Text(a?.name ?: "", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text(listOfNotNull(a?.jersey?.let { "#$it" }, a?.position).joinToString(" · "), style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = .75f))
                    a?.team?.let { t ->
                        Row(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .25f)).clickable { onTeam(league, t.id) }.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            TeamLogo(t, 18.dp); Spacer(Modifier.width(6.dp))
                            Text(t.displayName, style = MaterialTheme.typography.labelLarge, color = Color.White)
                        }
                    }
                    a?.injury?.let { Pill(it.uppercase(), Dez.Live.copy(alpha = .25f), Dez.Live, Modifier.padding(top = 6.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SummaryTiles(a: Athlete, tc: Color) {
    if (a.summary.isEmpty()) return
    Column {
        a.summaryTitle?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = Dez.Sub, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            a.summary.take(4).forEach { s ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(listOf(tc.copy(alpha = .22f), Dez.Card))).padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.label, style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
                    Text(s.value, style = MaterialTheme.typography.headlineSmall.merge(TabularNums), color = Dez.Text)
                    s.rank?.let { Text(it, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = Dez.Faint, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun BioCard(a: Athlete) {
    val rows = listOfNotNull(
        a.height?.let { "Height" to it }, a.weight?.let { "Weight" to it }, a.age?.let { "Age" to "$it" },
        a.birthplace?.let { "Born" to it }, a.college?.let { "College" to it }, a.draft?.let { "Draft" to it }, a.experience?.let { "Experience" to it },
    )
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("BIO", style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
        rows.forEach { (k, v) ->
            Row { Text(k, style = MaterialTheme.typography.bodyMedium, color = Dez.Sub, modifier = Modifier.width(100.dp)); Text(v, style = MaterialTheme.typography.bodyMedium, color = Dez.Text) }
        }
    }
}

@Composable
private fun Pills(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dez.Card).padding(4.dp)) {
        labels.forEachIndexed { i, l ->
            val bg by animateColorAsState(if (i == selected) Dez.Card2 else Dez.Card, tween(220), label = "pp")
            Text(l, style = MaterialTheme.typography.labelLarge, color = if (i == selected) Dez.Text else Dez.Sub, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(bg).clickable { onSelect(i) }.padding(vertical = 9.dp))
        }
    }
}

// ============================================================ game log

private fun plain(s: String): Float? = s.replace(",", "").toFloatOrNull()

/** Columns that hold one plain number per game (PTS, YDS, TD…) – those can be charted and heat-coloured. */
private fun chartable(log: GameLog): List<Int> {
    val rows = log.sections.flatMap { it.blocks }.flatMap { it.rows }
    return log.labels.indices.filter { i -> rows.isNotEmpty() && rows.all { r -> r.stats.getOrNull(i)?.let { v -> v.isEmpty() || v == "-" || plain(v) != null } != false } }
}

/** "YDS" appears under both Passing and Rushing – prefix duplicates with their group ("Rush YDS"). */
private fun chipLabel(log: GameLog, i: Int): String {
    val l = log.labels[i]
    if (log.labels.count { it == l } < 2) return l
    var at = 0
    for (g in log.groups) { if (i < at + g.count) return "${g.title.take(4)} $l"; at += g.count }
    return l
}

private fun defaultChartColumn(log: GameLog): Int {
    val c = chartable(log)
    for (want in listOf("PTS", "YDS", "G", "H", "REC")) log.labels.indexOf(want).takeIf { it in c }?.let { return it }
    return c.firstOrNull() ?: -1
}

private fun LazyListScope.gameLogContent(
    log: GameLog, league: League, tc: Color, chartCol: Int, onChartCol: (Int) -> Unit,
    loading: Boolean, onSeason: (String) -> Unit, onGame: (String) -> Unit,
) {
    if (log.seasons.isNotEmpty()) item(key = "seasons") {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(log.seasons) { (value, label) ->
                val sel = value == log.selectedSeason
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (sel) Color.Black else Dez.Text,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Dez.Text else Dez.Card2).clickable(enabled = !loading) { onSeason(value) }.padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
    }
    if (log.sections.isEmpty()) { item { EmptyState(if (loading) "Loading…" else "No games this season", "Try another season above.") }; return }
    val cols = chartable(log)
    val col = if (chartCol in cols) chartCol else defaultChartColumn(log)
    val main = log.sections.first()
    if (col >= 0) item(key = "chart-$col-${log.selectedSeason}") {
        Box(Modifier.padding(horizontal = 12.dp)) { GameLogChart(main, log.labels[col], log.descriptions.getOrNull(col), col, tc) }
    }
    if (cols.size > 1) item(key = "chartpick") {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(cols) { i ->
                val sel = i == col
                Text(chipLabel(log, i), style = MaterialTheme.typography.labelMedium, color = if (sel) Dez.Text else Dez.Sub,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) tc.copy(alpha = .35f) else Dez.Card).clickable { onChartCol(i) }.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
    }
    log.sections.forEach { sec ->
        item(key = "sec-${sec.title}") {
            Box(Modifier.padding(horizontal = 12.dp)) { GameLogTable(sec, log, tc, onGame, onHeader = { if (it in cols) onChartCol(it) }) }
        }
    }
}

/** Bars for every game in the season with the season average as a dashed line – hot games pop in team colour. */
@Composable
private fun GameLogChart(sec: GameLogSection, label: String, description: String?, col: Int, tc: Color) {
    val games = sec.blocks.flatMap { it.rows }.sortedBy { it.dateIso }
    val values = games.map { plain(it.stats.getOrNull(col) ?: "") ?: 0f }
    if (values.isEmpty()) return
    val avg = values.average().toFloat()
    val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
    val best = values.indices.maxByOrNull { values[it] } ?: 0
    val anim = remember(label, sec.title) { Animatable(0f) }
    LaunchedEffect(label, sec.title) { anim.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val hot = tc.lighten(.15f)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text((description ?: label).uppercase(), style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
                Text("%.1f".format(avg) + " avg", style = MaterialTheme.typography.titleLarge.merge(TabularNums), color = Dez.Text)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("SEASON HIGH", style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
                Text("${values[best].let { if (it % 1f == 0f) it.toInt().toString() else "%.1f".format(it) }} ${games[best].atVs} ${games[best].opponent.abbreviation}",
                    style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = hot)
            }
        }
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val n = values.size
            val gap = 4f
            val bw = ((size.width - gap * (n - 1)) / n).coerceIn(2f, 26.dp.toPx())
            val start = (size.width - (bw * n + gap * (n - 1))) / 2
            values.forEachIndexed { i, v ->
                val h = size.height * (v / max) * anim.value
                val above = v >= avg
                drawRoundRect(if (above) hot else Dez.Card2.lighten(.12f), Offset(start + i * (bw + gap), size.height - h), Size(bw, h.coerceAtLeast(2f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(minOf(bw / 3, 5.dp.toPx())))
            }
            val ay = size.height * (1 - avg / max)
            drawLine(Color.White.copy(alpha = .55f), Offset(0f, ay), Offset(size.width, ay), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(games.first().dateIso.let(::shortDate), style = MaterialTheme.typography.labelSmall, color = Dez.Faint, modifier = Modifier.weight(1f))
            Text("${games.size} games", style = MaterialTheme.typography.labelSmall, color = Dez.Faint)
            Text(games.last().dateIso.let(::shortDate), style = MaterialTheme.typography.labelSmall, color = Dez.Faint, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

private val shortFmt = DateTimeFormatter.ofPattern("M/d")
private fun shortDate(iso: String) = runCatching { Instant.parse(iso.substringBefore(".") + "Z").atZone(ZoneId.systemDefault()).format(shortFmt) }
    .recoverCatching { java.time.OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(shortFmt) }.getOrDefault("")

private val dayFmt = DateTimeFormatter.ofPattern("EEE M/d")
private fun logDate(iso: String) = runCatching { java.time.OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(dayFmt) }.getOrDefault(shortDate(iso))

private val rowH = 40.dp
private val leftW = 182.dp

@Composable
private fun GameLogTable(sec: GameLogSection, log: GameLog, tc: Color, onGame: (String) -> Unit, onHeader: (Int) -> Unit) {
    val all = sec.blocks.flatMap { it.rows }
    // Season average per column, for hot/cold colouring.
    val avg = log.labels.indices.map { i -> all.mapNotNull { plain(it.stats.getOrNull(i) ?: "") }.takeIf { it.size == all.size && it.isNotEmpty() }?.average()?.toFloat() }
    val scroll = rememberScrollState()
    val widths = columnWidths(log.labels, all.map { it.stats } + listOfNotNull(sec.averages, sec.totals) + sec.blocks.mapNotNull { it.totals })
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(vertical = 10.dp)) {
        Text(sec.title, style = MaterialTheme.typography.titleMedium, color = Dez.Text, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        Row {
            // Fixed: date / opponent / result
            Column(Modifier.width(leftW)) {
                if (log.groups.size > 1) Spacer(Modifier.height(22.dp))
                Box(Modifier.height(28.dp))
                AccentRule(tc)
                var n = 0
                sec.blocks.forEach { b ->
                    if (sec.blocks.size > 1) BlockHeader(b.title)
                    b.rows.forEach { r -> GameCell(r, Modifier.stripe(n++)) { onGame(r.eventId) } }
                    if (sec.blocks.size > 1 && b.totals != null) LeftLabel("${b.title.take(3)} AVG", Dez.Sub)
                }
                sec.averages?.let { LeftLabel("SEASON AVG", Dez.Text, tc) }
                sec.totals?.let { LeftLabel("TOTALS", Dez.Text, tc) }
            }
            Column(Modifier.weight(1f).horizontalScroll(scroll)) {
                if (log.groups.size > 1) Row(Modifier.height(22.dp)) {
                    var at = 0
                    log.groups.forEach { g ->
                        val w = widths.subList(at.coerceAtMost(widths.size), (at + g.count).coerceAtMost(widths.size)).fold(0.dp) { acc, d -> acc + d }
                        at += g.count
                        Box(Modifier.width(w).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                            Text(g.title.uppercase(), style = MaterialTheme.typography.labelSmall, color = tc.lighten(.3f), maxLines = 1)
                            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(tc.copy(alpha = .5f)))
                        }
                    }
                }
                Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    log.labels.forEachIndexed { i, l -> Text(l, style = MaterialTheme.typography.labelMedium, color = Dez.Sub, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(widths[i]).clickable { onHeader(i) }) }
                }
                AccentRule(tc, Modifier.width(widths.fold(0.dp) { a, b -> a + b }))
                var n = 0
                sec.blocks.forEach { b ->
                    if (sec.blocks.size > 1) Spacer(Modifier.height(26.dp))
                    b.rows.forEach { r ->
                        Row(Modifier.height(rowH).stripe(n++).clickable { onGame(r.eventId) }, verticalAlignment = Alignment.CenterVertically) {
                            r.stats.forEachIndexed { i, v -> HeatCell(v, avg.getOrNull(i), widths.getOrElse(i) { 44.dp }, tc) }
                        }
                    }
                    if (sec.blocks.size > 1) b.totals?.let { StatsRow(it, widths, Dez.Sub, false) }
                }
                sec.averages?.let { StatsRow(it, widths, Dez.Text, true) }
                sec.totals?.let { StatsRow(it, widths, Dez.Text, true) }
            }
        }
        Text("Team colour = well above season average · tap a column header to chart it", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = Dez.Faint, modifier = Modifier.padding(start = 14.dp, top = 8.dp))
    }
}

/** Width per column sized to its longest value, so "4.8-12.0" or "41,410" never clip. */
private fun columnWidths(labels: List<String>, rows: List<List<String>>): List<Dp> = labels.indices.map { i ->
    val longest = (rows.mapNotNull { it.getOrNull(i) } + labels[i]).maxOfOrNull { it.length } ?: 3
    (longest * 8.6f + 16f).coerceAtLeast(44f).dp
}

/** Big games get a team-colour pill, good ones team-colour text, quiet ones fade. */
@Composable
private fun HeatCell(v: String, avg: Float?, w: Dp, tc: Color) {
    val n = plain(v)
    val e = when {
        n == null || avg == null || avg <= 0f -> Emphasis.NONE
        n >= avg * 1.5f && n > 0f -> Emphasis.PILL
        n >= avg * 1.15f -> Emphasis.HOT
        n <= avg * .5f -> Emphasis.COLD
        else -> Emphasis.NONE
    }
    GridValue(v, w, tc, e)
}

@Composable
private fun StatsRow(values: List<String>, widths: List<Dp>, color: Color, bold: Boolean) {
    Row(Modifier.height(rowH).then(if (bold) Modifier.background(Dez.Card2) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        values.forEachIndexed { i, v -> Text(v, style = MaterialTheme.typography.bodyMedium.merge(TabularNums).copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium), color = color, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(widths.getOrElse(i) { 44.dp })) }
    }
}

@Composable
private fun BlockHeader(t: String) {
    Box(Modifier.height(26.dp).padding(start = 14.dp), contentAlignment = Alignment.CenterStart) { Text(t.uppercase(), style = MaterialTheme.typography.labelSmall, color = Dez.Faint) }
}

@Composable
private fun LeftLabel(t: String, c: Color, accent: Color? = null) {
    Row(Modifier.height(rowH).fillMaxWidth().then(if (accent != null) Modifier.background(Dez.Card2) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent?.readable() ?: Color.Transparent))
        Text(t, style = MaterialTheme.typography.labelLarge, color = c, modifier = Modifier.padding(start = 11.dp))
    }
}

@Composable
private fun GameCell(r: GameLogRow, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.height(rowH).fillMaxWidth().clickable(onClick = onClick).padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(logDate(r.dateIso), style = MaterialTheme.typography.labelMedium.merge(TabularNums), color = Dez.Sub, modifier = Modifier.width(66.dp), maxLines = 1)
        Text(r.atVs, style = MaterialTheme.typography.labelSmall, color = Dez.Faint, modifier = Modifier.width(16.dp))
        TeamLogo(r.opponent, 18.dp); Spacer(Modifier.width(4.dp))
        Text(r.opponent.abbreviation, style = MaterialTheme.typography.labelLarge, color = Dez.Text, modifier = Modifier.width(38.dp), maxLines = 1)
        r.result?.let { res ->
            Text(res, style = MaterialTheme.typography.labelLarge, color = when (res) { "W" -> Dez.Win; "L" -> Dez.Live; else -> Dez.Sub })
        }
    }
}

// ============================================================ live

@Composable
private fun LiveNowCard(l: LiveLine, tc: Color, onClick: () -> Unit) {
    val g = l.game
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brush.horizontalGradient(listOf(Dez.Live.copy(alpha = .22f), Dez.Card))).clickable(onClick = onClick).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot(5.dp)
            Text("LIVE NOW · ${liveLabel(g)}", style = MaterialTheme.typography.labelMedium, color = Dez.Live, modifier = Modifier.weight(1f))
            TeamLogo(g.away.team, 18.dp); Text(" ${g.away.score} – ${g.home.score} ", style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = Dez.Text); TeamLogo(g.home.team, 18.dp)
        }
        l.tables.forEach { (title, cells) ->
            if (l.tables.size > 1) Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Dez.Sub, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                cells.forEach { (label, v) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, style = MaterialTheme.typography.titleMedium.merge(TabularNums), color = Dez.Text)
                        Text(label, style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
                    }
                }
            }
        }
    }
}

// ============================================================ career

@Composable
private fun CareerTableView(t: CareerTable, tc: Color) {
    val scroll = rememberScrollState()
    // Best season per column gets highlighted, like a bold career-high on a reference site.
    val best = t.labels.indices.map { i -> t.rows.mapNotNull { plain(it.stats.getOrNull(i) ?: "") }.maxOrNull() }
    val widths = columnWidths(t.labels, t.rows.map { it.stats } + listOfNotNull(t.totals))
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(vertical = 10.dp)) {
        Text(t.title, style = MaterialTheme.typography.titleMedium, color = Dez.Text, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        Row {
            Column(Modifier.width(if (t.rows.any { it.team == null }) 134.dp else 118.dp)) {
                Box(Modifier.height(28.dp))
                AccentRule(tc)
                t.rows.forEachIndexed { idx, r ->
                    Row(Modifier.height(rowH).fillMaxWidth().stripe(idx).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.season, style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = if (r.team == null) Modifier.weight(1f) else Modifier.width(64.dp))
                        r.team?.let { TeamLogo(it, 20.dp); Spacer(Modifier.width(4.dp)); Text(it.abbreviation, style = MaterialTheme.typography.labelSmall, color = Dez.Sub, maxLines = 1) }
                    }
                }
                t.totals?.let { LeftLabel(if (t.title.contains("split", true)) "TOTAL" else "CAREER", Dez.Text, tc) }
            }
            Column(Modifier.weight(1f).horizontalScroll(scroll)) {
                Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    t.labels.forEachIndexed { i, l -> Text(l, style = MaterialTheme.typography.labelMedium, color = Dez.Sub, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(widths[i])) }
                }
                AccentRule(tc, Modifier.width(widths.fold(0.dp) { a, b -> a + b }))
                t.rows.forEachIndexed { idx, r ->
                    Row(Modifier.height(rowH).stripe(idx), verticalAlignment = Alignment.CenterVertically) {
                        r.stats.forEachIndexed { i, v ->
                            val top = t.rows.size > 1 && t.labels[i] !in LowerIsBetter && best.getOrNull(i)?.let { b -> b > 0f && plain(v) == b } == true
                            GridValue(v, widths.getOrElse(i) { 44.dp }, tc, if (top) Emphasis.PILL else Emphasis.NONE)
                        }
                    }
                }
                t.totals?.let { StatsRow(it, widths, Dez.Text, true) }
            }
        }
    }
}
