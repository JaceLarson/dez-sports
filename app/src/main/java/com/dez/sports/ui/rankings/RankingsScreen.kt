package com.dez.sports.ui.rankings

import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dez.sports.data.EspnApi
import com.dez.sports.data.Favorites
import com.dez.sports.model.League
import com.dez.sports.model.Poll
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.darken
import com.dez.sports.ui.theme.teamColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class RankingsViewModel : ViewModel() {
    val league = MutableStateFlow(League.CFB)
    val polls = MutableStateFlow<List<Poll>?>(null)
    val pollIndex = MutableStateFlow(0)
    private val cache = java.util.concurrent.ConcurrentHashMap<League, List<Poll>>()
    init { select(League.CFB) }
    fun select(l: League) {
        league.value = l; polls.value = cache[l]; pollIndex.value = 0
        viewModelScope.launch(Dispatchers.Default) { runCatching { EspnApi.rankings(l) }.onSuccess { cache[l] = it; if (league.value == l) polls.value = it } }
    }
}

@Composable
fun RankingsScreen(onTeam: (League, String) -> Unit, vm: RankingsViewModel = viewModel()) {
    val league by vm.league.collectAsState()
    val polls by vm.polls.collectAsState()
    val idx by vm.pollIndex.collectAsState()
    val favs by Favorites.teams.collectAsState()
    Column(Modifier.fillMaxSize().background(Dez.Bg)) {
        ScreenTitle("Rankings")
        LeaguePicker(League.entries.filter { it.hasRankings }, league, vm::select)
        val p = polls
        if (p == null) { Column(Modifier.padding(top = 8.dp)) { SkeletonCards(5) }; return@Column }
        if (p.isEmpty()) { EmptyState("No polls released yet"); return@Column }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(p.size) { i ->
                val sel = i == idx
                val c by animateColorAsState(if (sel) Dez.Accent else Dez.Sub, label = "poll")
                Text(p[i].name, style = MaterialTheme.typography.labelLarge, color = c, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { vm.pollIndex.value = i }.padding(horizontal = 8.dp, vertical = 6.dp))
            }
        }
        val poll = p.getOrNull(idx) ?: return@Column
        AnimatedContent(poll, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "poll") { pl ->
            LazyVerticalGrid(GridCells.Adaptive(360.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pl.headline?.let { h -> item(span = { GridItemSpan(maxLineSpan) }) { Text(h, style = MaterialTheme.typography.bodySmall, color = Dez.Sub, modifier = Modifier.padding(horizontal = 6.dp)) } }
                items(pl.entries, key = { "${pl.name}-${it.rank}-${it.team.id}" }) { e ->
                    val tc = teamColor(e.team.color, e.team.altColor)
                    val fav = favs.any { it.league == league && it.teamId == e.team.id }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(Brush.horizontalGradient(listOf(tc.darken(.35f).copy(alpha = if (fav) 1f else .55f), Dez.Card)))
                        .clickable { onTeam(league, e.team.id) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${e.rank}", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black, fontSize = 24.sp).merge(TabularNums), color = Color.White, modifier = Modifier.width(38.dp))
                        TeamLogo(e.team, 36.dp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.team.location.ifEmpty { e.team.displayName }, style = MaterialTheme.typography.titleSmall, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(e.record, e.points?.let { "$it pts" }, e.firstPlaceVotes.takeIf { it > 0 }?.let { "($it)" }).joinToString(" · "), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium), color = Dez.Sub)
                        }
                        Trend(e.rank, e.previous)
                    }
                }
                pl.others?.let { o -> item(span = { GridItemSpan(maxLineSpan) }) { Text("Others receiving votes: $o", style = MaterialTheme.typography.bodySmall, color = Dez.Faint, modifier = Modifier.padding(8.dp)) } }
            }
        }
    }
}

@Composable
private fun Trend(rank: Int, prev: Int) {
    val (t, c) = when {
        prev == 0 -> "NEW" to Dez.Gold
        prev > rank -> "▲ ${prev - rank}" to Dez.Win
        prev < rank -> "▼ ${rank - prev}" to Dez.Live
        else -> "—" to Dez.Faint
    }
    Text(t, style = MaterialTheme.typography.labelLarge, color = c)
}
