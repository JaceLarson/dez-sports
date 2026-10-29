package com.dez.sports.ui.standings

import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dez.sports.data.EspnApi
import com.dez.sports.data.Favorites
import com.dez.sports.model.League
import com.dez.sports.model.StandingsGroup
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.teamColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class StandingsViewModel : ViewModel() {
    val league = MutableStateFlow(League.NBA)
    val groups = MutableStateFlow<List<StandingsGroup>?>(null)
    val error = MutableStateFlow<String?>(null)
    /** Pro leagues: show divisions instead of conferences. */
    val divisions = MutableStateFlow(true)
    /** College: the one conference being shown (null = all). */
    val conference = MutableStateFlow<String?>(null)
    private val cache = java.util.concurrent.ConcurrentHashMap<Pair<League, Boolean>, List<StandingsGroup>>()
    private val pickedConference = mutableMapOf<League, String?>()

    init { select(League.NBA) }

    fun select(l: League) {
        if (l != league.value) divisions.value = l in setOf(League.NHL, League.NFL, League.MLB)
        league.value = l
        load()
    }

    fun setDivisions(d: Boolean) { divisions.value = d; load() }

    fun setConference(name: String?) { conference.value = name; pickedConference[league.value] = name }

    private fun load() {
        val l = league.value; val div = divisions.value && !l.isCollege
        val key = l to div
        groups.value = cache[key]; error.value = null
        cache[key]?.let { applyDefaultConference(l, it) }
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { EspnApi.standings(l, div) }.onSuccess {
                cache[key] = it
                if (league.value == l && (divisions.value && !l.isCollege) == div) { applyDefaultConference(l, it); groups.value = it }
            }.onFailure { if (league.value == l) error.value = it.message }
        }
    }

    /** College opens on the conference of a favourite team (BYU → Big 12), or whatever was picked last. */
    private fun applyDefaultConference(l: League, gs: List<StandingsGroup>) {
        if (!l.isCollege) { conference.value = null; return }
        if (pickedConference.containsKey(l)) { conference.value = pickedConference[l]; return }
        val favIds = Favorites.teams.value.filter { it.league == l }.map { it.teamId }
        conference.value = gs.firstOrNull { g -> g.rows.any { it.team.id in favIds } }?.name ?: gs.firstOrNull()?.name
    }
}

private val League.isCollege get() = this == League.CFB || this == League.CBB

private val shortNames = mapOf(
    "Atlantic Coast Conference" to "ACC", "Southeastern Conference" to "SEC", "Big Ten Conference" to "Big Ten",
    "Mid-American Conference" to "MAC", "Conference USA" to "C-USA", "FBS Independents" to "Independents",
    "Big East Conference" to "Big East", "West Coast Conference" to "WCC", "Atlantic 10 Conference" to "A-10",
    "Southwestern Athletic Conference" to "SWAC", "Mid-Eastern Athletic Conference" to "MEAC",
)
fun shortConference(name: String) = shortNames[name] ?: name.removeSuffix(" Conference").removePrefix("The ")

@Composable
fun StandingsScreen(onTeam: (League, String) -> Unit, vm: StandingsViewModel = viewModel()) {
    val league by vm.league.collectAsState()
    val groups by vm.groups.collectAsState()
    val error by vm.error.collectAsState()
    val divisions by vm.divisions.collectAsState()
    val conference by vm.conference.collectAsState()
    Column(Modifier.fillMaxSize().background(Dez.Bg)) {
        ScreenTitle("Standings")
        LeaguePicker(League.entries.filter { it.hasStandings }, league, vm::select)
        if (league.isCollege) {
            groups?.let { gs -> ConferenceChips(gs.map { it.name }, conference, vm::setConference) }
        } else {
            Segmented(listOf("Division", "Conference"), if (divisions) 0 else 1) { vm.setDivisions(it == 0) }
        }
        val shown = groups?.let { gs -> if (league.isCollege && conference != null) gs.filter { it.name == conference } else gs }
        AnimatedContent(Triple(shown, league, conference to divisions), transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "st") { (g, l, _) ->
            when {
                g == null && error != null -> EmptyState("Couldn't load standings", error)
                g == null -> Column(Modifier.padding(top = 8.dp)) { SkeletonCards(4) }
                g.isEmpty() -> EmptyState("No standings yet")
                else -> LazyVerticalGrid(GridCells.Adaptive(400.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp, top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(g, key = { it.name }) { StandingsTable(it, l, onTeam) }
                }
            }
        }
    }
}

@Composable
private fun ConferenceChips(names: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    val options = listOf<String?>(null) + names
    val state = rememberLazyListState()
    LaunchedEffect(names) { options.indexOf(selected).takeIf { it > 1 }?.let { state.scrollToItem(it - 1) } }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        items(options) { n ->
            val sel = n == selected
            val bg by animateColorAsState(if (sel) Dez.Accent.copy(alpha = .22f) else Dez.Card, tween(220), label = "conf")
            val fg by animateColorAsState(if (sel) Dez.Accent else Dez.Sub, tween(220), label = "conffg")
            Text(n?.let(::shortConference) ?: "All", style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(bg).clickable { onSelect(n) }.padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(Dez.Card).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) Dez.Card2 else Dez.Card, tween(220), label = "seg")
            Text(o, style = MaterialTheme.typography.labelLarge, color = if (sel) Dez.Text else Dez.Sub,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(bg).clickable { onSelect(i) }.padding(horizontal = 16.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun StandingsTable(g: StandingsGroup, league: League, onTeam: (League, String) -> Unit) {
    val favs by Favorites.teams.collectAsState()
    // Playoff line positions for a quick visual cut.
    val cut = if (league == League.NBA && g.rows.size > 10) 6 else 0
    Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(vertical = 10.dp)) {
        Text(g.name, style = MaterialTheme.typography.titleMedium, color = Dez.Text, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        BoxWithConstraints {
        val fit = fitColumns(g.columns, maxWidth)
        val scroll = rememberScrollState()
        Row {
            Column(if (fit.fits) Modifier.weight(1f) else Modifier.width(170.dp)) {
                Box(Modifier.height(26.dp))
                g.rows.forEachIndexed { i, r ->
                    val fav = favs.any { it.league == league && it.teamId == r.team.id }
                    val tc = teamColor(r.team.color, r.team.altColor)
                    Row(Modifier.height(40.dp).fillMaxWidth().stripe(i).clickable { onTeam(league, r.team.id) }
                        .then(if (fav) Modifier.background(tc.copy(alpha = .22f)) else Modifier), verticalAlignment = Alignment.CenterVertically) {
                        // Team-colour edge makes each row easy to track across the wide table.
                        Box(Modifier.width(3.dp).fillMaxHeight().padding(vertical = 8.dp).clip(RoundedCornerShape(50)).background(tc.readable()))
                        Spacer(Modifier.width(7.dp))
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium.merge(TabularNums), color = Dez.Sub, modifier = Modifier.width(if (fit.tight) 18.dp else 22.dp))
                        TeamLogo(r.team, 22.dp); Spacer(Modifier.width(if (fit.tight) 6.dp else 8.dp))
                        Text(if (league == League.CFB || league == League.CBB) r.team.location.ifEmpty { r.team.displayName } else r.team.name,
                            style = MaterialTheme.typography.bodyMedium, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        HeatIcon(remember(r) { com.dez.sports.data.Forms.standings(g.columns, r.values) }, 12, Modifier.padding(end = 3.dp))
                        r.clincher?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Dez.Win, modifier = Modifier.padding(end = 4.dp)) }
                    }
                    if (cut > 0 && i == cut - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(Dez.Accent.copy(alpha = .5f)))
                }
            }
            Column(if (fit.fits) Modifier else Modifier.weight(1f).horizontalScroll(scroll)) {
                Row(Modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                    fit.cols.forEach { ci -> Text(g.columns[ci], style = MaterialTheme.typography.labelMedium, color = Dez.Sub, textAlign = TextAlign.Center, modifier = Modifier.width(fit.width(g.columns[ci]))) }
                }
                g.rows.forEachIndexed { i, r ->
                    val fav = favs.any { it.league == league && it.teamId == r.team.id }
                    Row(Modifier.height(40.dp).stripe(i).then(if (fav) Modifier.background(teamColor(r.team.color, r.team.altColor).copy(alpha = .22f)) else Modifier), verticalAlignment = Alignment.CenterVertically) {
                        fit.cols.forEach { ci ->
                            val v = r.values.getOrElse(ci) { "" }
                            val col = g.columns[ci]
                            val c = when {
                                col == "STRK" && v.startsWith("W") -> Dez.Win
                                col == "STRK" && v.startsWith("L") -> Dez.Live
                                col == "DIFF" && v.startsWith("+") -> Dez.Win
                                col == "DIFF" && v.startsWith("-") -> Dez.Live.copy(alpha = .85f)
                                v == "0" || v == "0-0" -> Dez.Faint
                                col == "W" || col == "PTS" -> Dez.Text
                                else -> Dez.Text.copy(alpha = .85f)
                            }
                            Text(v, style = MaterialTheme.typography.bodyMedium.merge(TabularNums).copy(fontWeight = if (col == "W" || col == "PTS") FontWeight.Bold else FontWeight.Normal), color = c, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(fit.width(col)))
                        }
                    }
                    if (cut > 0 && i == cut - 1) Box(Modifier.width(fit.total).height(1.dp).background(Dez.Accent.copy(alpha = .5f)))
                }
            }
        }
        }
    }
}

private fun colWidth(c: String) = when (c) { "CONF", "OVR", "HOME", "AWAY", "L10", "DIV" -> 58.dp; "PCT", "STRK", "DIFF", "PF", "PA" -> 52.dp; else -> 40.dp }

/** Snug widths for a narrow (folded) screen. */
private fun tightWidth(c: String) = when (c) {
    "CONF", "OVR", "HOME", "AWAY", "L10" -> 44.dp; "PCT" -> 42.dp; "DIFF", "DIV" -> 40.dp; "STRK" -> 38.dp
    "PF", "PA" -> 36.dp; "GB", "OTL", "PTS" -> 34.dp; "GP" -> 32.dp; "T" -> 26.dp; else -> 30.dp
}

/** Columns given up first when even snug widths don't fit; Home/Away go as a pair. */
private val dropOrder = listOf(setOf("HOME", "AWAY"), setOf("L10"), setOf("DIV"), setOf("GP"), setOf("PF", "PA"))

/** Leave the team column room for names like "Maple Leafs". */
private val minTeam = 150.dp

private class Fit(val cols: List<Int>, val tight: Boolean, val fits: Boolean, val total: Dp) {
    fun width(c: String) = if (tight) tightWidth(c) else colWidth(c)
}

/**
 * Fit the stat columns beside the team names without sideways scrolling: natural widths if there's room,
 * then snug widths, then dropping the least useful columns. Only falls back to scrolling if nothing works.
 */
private fun fitColumns(columns: List<String>, available: Dp): Fit {
    val all = columns.indices.toList()
    fun sum(cols: List<Int>, w: (String) -> Dp) = cols.fold(0.dp) { acc, i -> acc + w(columns[i]) }
    sum(all, ::colWidth).let { if (it + 170.dp <= available) return Fit(all, false, true, it) }
    var cols = all
    for (drop in listOf(emptySet<String>()) + dropOrder) {
        cols = cols.filter { columns[it] !in drop }
        val t = sum(cols, ::tightWidth)
        if (t + minTeam <= available) return Fit(cols, true, true, t)
    }
    return Fit(all, false, false, sum(all, ::colWidth))
}
