package com.dez.sports.ui.team

import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dez.sports.data.EspnApi
import com.dez.sports.data.Favorites
import com.dez.sports.model.*
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.darken
import com.dez.sports.ui.theme.teamColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TeamViewModel(handle: SavedStateHandle) : ViewModel() {
    val league = League.valueOf(handle["league"]!!)
    val id: String = handle["id"]!!
    val info = MutableStateFlow<TeamInfo?>(null)
    val roster = MutableStateFlow<List<RosterGroup>?>(null)
    val stats = MutableStateFlow<List<com.dez.sports.data.TeamStatCategory>?>(null)
    /** Every team in the league, so this one can be ranked against them. */
    val leagueStats = MutableStateFlow<com.dez.sports.data.LeagueTeamStats?>(null)
    /** Every player's season line; loaded the first time the Player Stats tab opens (one request per player). */
    val players = MutableStateFlow<List<com.dez.sports.model.StatTable>?>(null)
    private var playersRequested = false
    /** Hot/cold badges from the last five box scores. */
    val heat = MutableStateFlow<Map<String, com.dez.sports.data.Heat>>(emptyMap())
    val story = MutableStateFlow<com.dez.sports.data.SeasonStory?>(null)
    private var storyRequested = false
    private var leagueRequested = false
    /** Loaded when the Team Stats tab first opens, so the rest of the page doesn't wait behind it. */
    fun loadLeagueStats() {
        if (leagueRequested || league == League.PGA) return
        leagueRequested = true
        viewModelScope.launch(Dispatchers.Default) { runCatching { com.dez.sports.data.LeagueStats.teams(league) }.onSuccess { leagueStats.value = it } }
    }
    fun loadStory() {
        if (storyRequested) return
        storyRequested = true
        viewModelScope.launch(Dispatchers.Default) {
            val i = info.first { it != null }!!
            story.value = runCatching { com.dez.sports.data.SeasonStories.build(league, i, id) }.getOrNull()
        }
    }
    /** Set when ESPN couldn't be reached; the page offers a retry. */
    val error = MutableStateFlow<String?>(null)
    fun loadPlayers() {
        if (playersRequested) return
        playersRequested = true
        viewModelScope.launch(Dispatchers.Default) {
            val groups = roster.first { it != null }!!
            players.value = runCatching { com.dez.sports.data.TeamPlayers.load(league, groups) }
                .onFailure { error.value = it.message; playersRequested = false }.getOrNull() ?: return@launch
            val i = info.first { it != null }!!
            heat.value = runCatching { com.dez.sports.data.Forms.roster(league, id, i.schedule, players.value.orEmpty()) }.getOrDefault(emptyMap())
        }
    }
    init { load() }

    /** The page's own data. Anything that failed is fetched again; the rest is kept. */
    val attempt = MutableStateFlow(0)
    fun retry() {
        error.value = null
        if (roster.value.isNullOrEmpty()) roster.value = null
        if (players.value.isNullOrEmpty()) { players.value = null; playersRequested = false }
        load()
        attempt.value++
        if (story.value == null) storyRequested = false
        if (leagueStats.value == null) leagueRequested = false
    }

    private fun load() {
        if (info.value == null) viewModelScope.launch(Dispatchers.Default) { runCatching { EspnApi.team(league, id) }.onSuccess { info.value = it }.onFailure { error.value = it.message } }
        if (stats.value.isNullOrEmpty()) viewModelScope.launch(Dispatchers.Default) { stats.value = runCatching { com.dez.sports.data.StatsApi.teamStats(league, id) }.getOrDefault(emptyList()) }
        if (roster.value.isNullOrEmpty()) viewModelScope.launch(Dispatchers.Default) { runCatching { com.dez.sports.data.PlayerApi.roster(league, id) }.onSuccess { roster.value = it }.onFailure { roster.value = emptyList(); error.value = it.message } }
    }
}

@Composable
fun TeamScreen(onBack: () -> Unit, onGame: (Game) -> Unit, onH2H: (League, String) -> Unit = { _, _ -> }, vm: TeamViewModel = viewModel()) {
    val info by vm.info.collectAsState()
    val roster by vm.roster.collectAsState()
    val teamStats by vm.stats.collectAsState()
    val leagueStats by vm.leagueStats.collectAsState()
    val heat by vm.heat.collectAsState()
    val story by vm.story.collectAsState()
    var ctx by remember { mutableStateOf<(suspend () -> com.dez.sports.data.Distribution?)?>(null) }
    val form = remember(info) { info?.let { com.dez.sports.data.Forms.team(it, vm.id) } }
    val playerTables by vm.players.collectAsState()
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 700
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val openPlayer = LocalOpenPlayer.current
    val favs by Favorites.teams.collectAsState()
    val fav = favs.any { it.league == vm.league && it.teamId == vm.id }
    val t = info?.team
    val tc = t?.let { teamColor(it.color, it.altColor) } ?: Dez.Card2
    val error by vm.error.collectAsState()
    val attempt by vm.attempt.collectAsState()
    if (tab == 2) LaunchedEffect(attempt) { vm.loadPlayers() }
    if (tab == 4) LaunchedEffect(attempt) { vm.loadStory() }
    if (tab == 1) LaunchedEffect(attempt) { vm.loadLeagueStats() }
    LazyVerticalGrid(GridCells.Adaptive(360.dp), Modifier.fillMaxSize().background(Dez.Bg), contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tc.darken(.1f), tc.darken(.6f), Dez.Bg)))) {
                Column(Modifier.statusBarsPadding().padding(bottom = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(8.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .25f)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        val starScale by animateFloatAsState(if (fav) 1.15f else 1f, spring(dampingRatio = .35f, stiffness = 500f), label = "star")
                        val starC by animateColorAsState(if (fav) Dez.Gold else Color.White, label = "starc")
                        Box(Modifier.padding(8.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .25f)).clickable { Favorites.toggle(vm.league, vm.id) }, contentAlignment = Alignment.Center) {
                            Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, "Favorite", tint = starC, modifier = Modifier.scale(starScale))
                        }
                    }
                    if (t != null) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            TeamLogo(t, 96.dp)
                            Text(t.displayName, style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(top = 10.dp))
                            Text(listOfNotNull(vm.league.label, info?.record, info?.standingSummary).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .7f))
                            Text("Head-to-head history ›", style = MaterialTheme.typography.labelLarge, color = Color.White,
                                modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .25f)).clickable { onH2H(vm.league, vm.id) }.padding(horizontal = 14.dp, vertical = 7.dp))
                        }
                    }
                }
            }
        }
        form?.let { f -> item(span = { GridItemSpan(maxLineSpan) }, key = "form") { FormCard(f, Modifier.padding(horizontal = 12.dp)) } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dez.Card).padding(4.dp)) {
                listOf(0 to "Schedule", 4 to "Story", 1 to "Team Stats", 2 to "Player Stats", 3 to "Roster").forEach { (i, l) ->
                    val bg by animateColorAsState(if (i == tab) Dez.Card2 else Dez.Card, label = "tt")
                    Text(l, style = if (wide) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium, color = if (i == tab) Dez.Text else Dez.Sub, textAlign = TextAlign.Center, maxLines = 1,
                        modifier = Modifier.weight(if (l.length > 8) 1.25f else 1f).clip(RoundedCornerShape(12.dp)).background(bg).clickable { tab = i }.padding(vertical = 9.dp))
                }
            }
        }
        if (tab == 1) {
            val sched = info?.schedule
            if (sched != null) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(horizontal = 12.dp)) { SeasonChart(sched, vm.id, teamColor(info!!.team.color, info!!.team.altColor)) } }
            leagueStats?.let { ls -> item(span = { GridItemSpan(maxLineSpan) }, key = "ranks") { Box(Modifier.padding(horizontal = 12.dp)) { com.dez.sports.ui.stats.TeamRanksCard(ls, vm.id) } } }
            val ts = teamStats
            if (ts == null) item(span = { GridItemSpan(maxLineSpan) }) { SkeletonCards(2) }
            val sched2 = info?.schedule
            if (sched2 != null && com.dez.sports.data.SeasonShots.supported(vm.league)) item(span = { GridItemSpan(maxLineSpan) }, key = "shotmap") {
                val ids = remember(sched2) { sched2.filter { it.game.state == com.dez.sports.model.GameState.POST }.sortedByDescending { it.game.dateIso }.map { it.game.id } }
                com.dez.sports.ui.game.SeasonShotMap(vm.league, ids, vm.id, null, tc, "SEASON SHOT MAP", Modifier.padding(horizontal = 12.dp))
            }
            if (ts != null) ts.forEach { c -> item(key = "ts-${c.title}") {
                TeamStatGrid(c, tc, leagueStats, vm.id) { name, value -> leagueStats?.let { ls -> ctx = { com.dez.sports.data.StatContext.team(ls, name, vm.id, value) } } }
            } }
            return@LazyVerticalGrid
        }
        error?.let { e -> item(span = { GridItemSpan(maxLineSpan) }, key = "error") { ErrorState("Couldn't reach ESPN", e, vm::retry) } }
        if (tab == 4) {
            val st = story
            if (st == null) item(span = { GridItemSpan(maxLineSpan) }) { SkeletonCards(4) }
            else storyItems(st, vm.id, tc, onGame)
            return@LazyVerticalGrid
        }
        if (tab == 2) {
            val tables = playerTables
            val team = info?.team
            if (tables == null || team == null) { if (error == null) item(span = { GridItemSpan(maxLineSpan) }) { SkeletonCards(3) }; return@LazyVerticalGrid }
            if (tables.isEmpty()) { item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("No player stats yet", "Check back once the season starts.") }; return@LazyVerticalGrid }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Tap a column to sort · hold a header for what it means · hold a number to see where it ranks league-wide · 🔥/🧊 = last 5 games", style = MaterialTheme.typography.labelSmall, color = Dez.Faint,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            tables.forEach { t ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "pc-${t.title}") { PlayerCompare(t, vm.league, tc, Modifier.padding(horizontal = 12.dp)) }
                item(span = { GridItemSpan(maxLineSpan) }, key = "pt-${t.title}") {
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        com.dez.sports.ui.game.StatTableView(t, team, vm.league, wide, heat = heat, onCellLongPress = { r, col ->
                            val id = r.id
                            if (id != null) ctx = { com.dez.sports.data.StatContext.player(vm.league, t.title, t.labels[col], id, r.name, r.stats[col]) }
                        })
                    }
                }
            }
            return@LazyVerticalGrid
        }
        if (tab == 3) {
            val groups = roster
            if (groups == null) { item(span = { GridItemSpan(maxLineSpan) }) { SkeletonCards(4) }; return@LazyVerticalGrid }
            groups.forEach { g ->
                g.title?.let { t -> item(span = { GridItemSpan(maxLineSpan) }, key = "rg-$t") { Box(Modifier.padding(horizontal = 16.dp)) { SectionTitle(t) } } }
                items(g.players, key = { "rp-${g.title}-${it.id}" }) { p -> RosterRow(p) { openPlayer?.invoke(vm.league, p.id) } }
            }
            return@LazyVerticalGrid
        }
        val sched = info?.schedule
        if (sched == null) { item(span = { GridItemSpan(maxLineSpan) }) { SkeletonCards(4) }; return@LazyVerticalGrid }
        items(sched, key = { it.game.id }) { s -> ScheduleRow(s, vm.id) { onGame(s.game) } }
    }
    ctx?.let { loader -> StatContextDialog(tc, loader) { ctx = null } }
}

@Composable
private fun ScheduleRow(s: TeamScheduleItem, teamId: String, onClick: () -> Unit) {
    val g = s.game
    val home = g.home.team.id == teamId
    val opp = if (home) g.away else g.home
    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dez.Card).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(78.dp)) {
            Text(localDay(g.dateIso), style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
            if (g.state == GameState.PRE) Text(localTime(g.dateIso), style = MaterialTheme.typography.labelSmall, color = Dez.Faint)
        }
        Text(if (home) "vs" else "@", style = MaterialTheme.typography.labelMedium, color = Dez.Sub, modifier = Modifier.width(22.dp))
        TeamLogo(opp.team, 26.dp); Spacer(Modifier.width(8.dp))
        Text((opp.rank?.let { "$it " } ?: "") + opp.team.displayName, style = MaterialTheme.typography.bodyMedium, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        when {
            s.result != null -> Text(s.result, style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = if (s.result.startsWith("W")) Dez.Win else Dez.Live)
            g.state == GameState.LIVE -> Row(verticalAlignment = Alignment.CenterVertically) { LiveDot(4.dp); Text("${g.away.score}-${g.home.score}", style = MaterialTheme.typography.titleSmall, color = Dez.Live) }
            else -> g.broadcasts.firstOrNull()?.let { Pill(it) }
        }
    }
}

@Composable
private fun RosterRow(p: RosterPlayer, onClick: () -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dez.Card).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        coil3.compose.AsyncImage(p.headshot, p.name, Modifier.size(44.dp).clip(CircleShape).background(Dez.Card2), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.injured) { Spacer(Modifier.width(6.dp)); Pill("INJ", Dez.Live.copy(alpha = .22f), Dez.Live) }
            }
            p.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Dez.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text(listOfNotNull(p.jersey?.let { "#$it" }, p.position).joinToString("  "), style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = Dez.Sub)
    }
}

/** Game-by-game margin bars with the running record drawn on top – a team "game log" at a glance. */
@Composable
private fun SeasonChart(sched: List<TeamScheduleItem>, teamId: String, tc: androidx.compose.ui.graphics.Color) {
    val played = sched.filter { it.game.state == GameState.POST }
    if (played.isEmpty()) return
    val margins = played.map { s ->
        val me = if (s.game.home.team.id == teamId) s.game.home else s.game.away
        val opp = if (s.game.home.team.id == teamId) s.game.away else s.game.home
        ((me.score?.toDoubleOrNull() ?: 0.0) - (opp.score?.toDoubleOrNull() ?: 0.0)).toFloat()
    }
    val max = margins.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1f)
    var w = 0; var l = 0
    val running = margins.map { if (it > 0) w++ else if (it < 0) l++; w - l }
    val anim = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, androidx.compose.animation.core.tween(1000)) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("SEASON SO FAR", style = MaterialTheme.typography.labelMedium, color = Dez.Sub, modifier = Modifier.weight(1f))
            Text("$w-$l · ${"%+.1f".format(margins.average())} avg margin", style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = Dez.Text)
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val mid = size.height / 2; val n = margins.size
            val gap = 3f; val bw = ((size.width - gap * (n - 1)) / n).coerceIn(2f, 22.dp.toPx())
            val start = (size.width - (bw * n + gap * (n - 1))) / 2
            drawLine(Dez.Hairline, androidx.compose.ui.geometry.Offset(0f, mid), androidx.compose.ui.geometry.Offset(size.width, mid), 2f)
            margins.forEachIndexed { i, m ->
                val h = (mid - 6) * kotlin.math.abs(m) / max * anim.value
                val x = start + i * (bw + gap)
                drawRoundRect(if (m >= 0) Dez.Win else Dez.Live, androidx.compose.ui.geometry.Offset(x, if (m >= 0) mid - h else mid), androidx.compose.ui.geometry.Size(bw, h.coerceAtLeast(2f)), androidx.compose.ui.geometry.CornerRadius(4f))
            }
            // running games-over-.500 line
            val span = running.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1)
            val path = androidx.compose.ui.graphics.Path()
            running.forEachIndexed { i, r ->
                val p = androidx.compose.ui.geometry.Offset(start + i * (bw + gap) + bw / 2, mid - (mid - 6) * r / span * anim.value)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, tc, style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
        }
        Text("Bars = margin each game · line = games over .500", style = MaterialTheme.typography.labelSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = Dez.Faint, modifier = Modifier.padding(top = 6.dp))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TeamStatGrid(c: com.dez.sports.data.TeamStatCategory, tc: androidx.compose.ui.graphics.Color, league: com.dez.sports.data.LeagueTeamStats?, teamId: String,
                         onContext: (String, String) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(vertical = 12.dp)) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(tc.readable())); Spacer(Modifier.width(7.dp))
            Text(c.title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Dez.Text)
        }
        AccentRule(tc, Modifier.padding(top = 8.dp))
        c.stats.chunked(2).forEachIndexed { idx, pair ->
            Row(Modifier.fillMaxWidth().stripe(idx).padding(horizontal = 14.dp, vertical = 7.dp)) {
                pair.forEach { (k, v) ->
                    // League rank under each number ("3rd most"); hold for the whole league's spread.
                    val rank = remember(league, k) { league?.let { com.dez.sports.data.StatContext.teamRank(it, k, teamId) } }
                    Row(Modifier.weight(1f).padding(end = 12.dp).combinedClickable(onClick = {}, onLongClick = { onContext(k, v) }), verticalAlignment = Alignment.CenterVertically) {
                        Text(k, style = MaterialTheme.typography.bodySmall, color = Dez.Sub, modifier = Modifier.weight(1f), maxLines = 2)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(v, style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = if (isZeroStat(v)) Dez.Faint else Dez.Text)
                            rank?.let { (r, desc) -> Text("${com.dez.sports.data.ordinal(r)} ${if (desc) "most" else "fewest"}", style = MaterialTheme.typography.labelSmall, color = Dez.Faint, maxLines = 1) }
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
