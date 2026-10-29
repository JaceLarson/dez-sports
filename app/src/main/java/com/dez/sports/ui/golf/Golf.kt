package com.dez.sports.ui.golf

import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.dez.sports.data.EspnApi
import com.dez.sports.model.GameState
import com.dez.sports.model.GolfEvent
import com.dez.sports.model.GolfPlayer
import com.dez.sports.model.League
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val Fairway = Color(0xFF1F6F43)

private fun parColor(s: String) = when {
    s.startsWith("-") -> Color(0xFFFF453A)    // under par is red, like a real leaderboard
    s == "E" -> Dez.Text
    else -> Dez.Text.copy(alpha = .8f)
}

@Composable
fun GolfCard(e: GolfEvent, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.verticalGradient(listOf(Fairway.copy(alpha = .55f), Dez.Card))).clickable(onClick = onClick).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.titleMedium, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(e.dateRange, e.broadcasts.firstOrNull()).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
            }
            if (e.state == GameState.LIVE) { LiveDot(4.dp); Text(e.detail, style = MaterialTheme.typography.labelMedium, color = Dez.Live) }
            else Pill(e.detail.uppercase(), Color.White.copy(alpha = .1f), Color.White.copy(alpha = .8f))
        }
        Spacer(Modifier.height(10.dp))
        e.players.take(5).forEach { p -> PlayerLine(p, compact = true) }
    }
}

@Composable
private fun PlayerLine(p: GolfPlayer, compact: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 4.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.position, style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = Dez.Sub, modifier = Modifier.width(36.dp))
        p.flag?.let { AsyncImage(it, null, Modifier.size(width = 18.dp, height = 12.dp).clip(RoundedCornerShape(2.dp))); Spacer(Modifier.width(8.dp)) }
        Text(p.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (!compact) {
            p.rounds.forEach { r -> Text(r, style = MaterialTheme.typography.bodySmall.merge(TabularNums), color = Dez.Sub, textAlign = TextAlign.Center, modifier = Modifier.width(30.dp)) }
        }
        p.thru?.let { Text(it, style = MaterialTheme.typography.labelMedium.merge(TabularNums), color = Dez.Faint, textAlign = TextAlign.Center, modifier = Modifier.width(30.dp)) }
        Text(p.toPar, style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = parColor(p.toPar), textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
    }
}

class GolfViewModel(handle: SavedStateHandle) : ViewModel() {
    private val id: String = handle["id"]!!
    val event = MutableStateFlow<GolfEvent?>(null)
    init {
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                runCatching { EspnApi.scoreboard(League.PGA) }.onSuccess { b -> event.value = b.golf.firstOrNull { it.id == id } ?: b.golf.firstOrNull() }
                if (event.value?.state != GameState.LIVE) break
                delay(30_000)
            }
        }
    }
}

@Composable
fun GolfScreen(onBack: () -> Unit, vm: GolfViewModel = viewModel()) {
    val e by vm.event.collectAsState()
    Column(Modifier.fillMaxSize().background(Dez.Bg)) {
        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Fairway, Dez.Bg)))) {
            Column(Modifier.statusBarsPadding().padding(bottom = 16.dp)) {
                Box(Modifier.padding(8.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .25f)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                e?.let {
                    Text(it.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(horizontal = 20.dp))
                    Text(listOfNotNull(it.venue, it.dateRange, it.detail).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .75f), modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
        val ev = e ?: run { SkeletonCards(6); return@Column }
        val maxRounds = ev.players.maxOfOrNull { it.rounds.size } ?: 0
        Row(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
            Text("POS", style = MaterialTheme.typography.labelSmall, color = Dez.Sub, modifier = Modifier.width(36.dp))
            Text("PLAYER", style = MaterialTheme.typography.labelSmall, color = Dez.Sub, modifier = Modifier.weight(1f))
            (1..maxRounds).forEach { Text("R$it", style = MaterialTheme.typography.labelSmall, color = Dez.Sub, textAlign = TextAlign.Center, modifier = Modifier.width(30.dp)) }
            Text("THRU", style = MaterialTheme.typography.labelSmall, color = Dez.Sub, textAlign = TextAlign.Center, modifier = Modifier.width(30.dp))
            Text("TOT", style = MaterialTheme.typography.labelSmall, color = Dez.Sub, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
        }
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            itemsIndexed(ev.players, key = { i, p -> "$i-${p.name}" }) { i, p ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (i % 2 == 0) Dez.Card else Color.Transparent).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    PlayerLine(p, compact = false)
                }
            }
        }
    }
}
