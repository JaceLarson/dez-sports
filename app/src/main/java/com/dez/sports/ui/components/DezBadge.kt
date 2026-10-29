package com.dez.sports.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dez.sports.data.DezPart
import com.dez.sports.data.DezScore
import com.dez.sports.data.WatchScore
import com.dez.sports.data.WatchScores
import com.dez.sports.data.DezScores
import com.dez.sports.model.Game
import com.dez.sports.ui.theme.Dez

/** Cool to hot: snoozers are slate, classics glow red-gold. */
fun dezColor(score: Int): Color = when {
    score >= 85 -> Color(0xFFFF453A)
    score >= 70 -> Color(0xFFFF9F0A)
    score >= 55 -> Color(0xFF30D158)
    score >= 35 -> Color(0xFF64D2FF)
    else -> Color(0xFF8E8E93)
}

/** The score with the pregame line folded in once it's known (fetched once per game, then kept on disk). */
@Composable
fun rememberDezScore(g: Game): DezScore? {
    val d by produceState(remember(g.id, g.away.score, g.home.score, g.state) { DezScores.of(g) }, g.id, g.away.score, g.home.score, g.state) {
        if (g.state != com.dez.sports.model.GameState.POST || g.league == com.dez.sports.model.League.PGA) return@produceState
        val line = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { com.dez.sports.data.Upsets.line(g) }.getOrNull() }
        if (line != null) value = DezScores.of(g, line)
    }
    return d
}

/** Small "DEZ 87" pill for finished games. */
@Composable
fun DezBadge(g: Game, modifier: Modifier = Modifier, compact: Boolean = false) {
    val d = rememberDezScore(g) ?: return
    val c = dezColor(d.score)
    Row(modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = .16f)).padding(horizontal = if (compact) 5.dp else 7.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("DEZ", fontSize = if (compact) 8.sp else 9.sp, fontWeight = FontWeight.Black, color = c.copy(alpha = .8f), letterSpacing = .5.sp)
        Spacer(Modifier.width(3.dp))
        Text("${d.score}", style = MaterialTheme.typography.labelMedium.merge(TabularNums).copy(fontWeight = FontWeight.Black, fontSize = if (compact) 11.sp else 12.sp), color = c)
    }
}

/** Game page: the score, its verdict, and what earned it. */
@Composable
fun DezScoreCard(g: Game, modifier: Modifier = Modifier) {
    val d: DezScore = rememberDezScore(g) ?: return
    BreakdownCard("DEZ SCORE", d.verdict, "How fun this one was to watch", d.score, d.parts, modifier)
}

/** Upcoming games: how worth watching it looks, once standings and the line are in. */
@Composable
fun rememberWatchScore(g: Game, fromScoreboard: Boolean = false): WatchScore? {
    if (g.state != com.dez.sports.model.GameState.PRE) return null
    val w by produceState<WatchScore?>(null, g.id, g.away.record, g.home.record) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            // The game page's copy comes from the summary, which lacks TV and odds; score the scoreboard's copy so it matches the card.
            val game = if (fromScoreboard) runCatching { com.dez.sports.data.EspnApi.event(g.league, g.id) }.getOrNull() ?: g else g
            runCatching { WatchScores.of(game) }.getOrNull()
        }
    }
    return w
}

/** "WATCH 78" pill on upcoming games: outlined, so it doesn't read as a finished game's Dez Score. */
@Composable
fun WatchBadge(g: Game, modifier: Modifier = Modifier, compact: Boolean = false) {
    val w = rememberWatchScore(g) ?: return
    val c = dezColor(w.score)
    Row(modifier.clip(RoundedCornerShape(50)).border(1.dp, c.copy(alpha = .7f), RoundedCornerShape(50)).padding(horizontal = if (compact) 5.dp else 7.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("WATCH", fontSize = if (compact) 8.sp else 9.sp, fontWeight = FontWeight.Black, color = c.copy(alpha = .8f), letterSpacing = .5.sp)
        Spacer(Modifier.width(3.dp))
        Text("${w.score}", style = MaterialTheme.typography.labelMedium.merge(TabularNums).copy(fontWeight = FontWeight.Black, fontSize = if (compact) 11.sp else 12.sp), color = c)
    }
}

@Composable
fun WatchScoreCard(g: Game, modifier: Modifier = Modifier) {
    val w = rememberWatchScore(g, fromScoreboard = true) ?: return
    BreakdownCard("DEZ WATCH SCORE", w.verdict, "How worth watching this one looks", w.score, w.parts, modifier)
}

@Composable
private fun BreakdownCard(label: String, verdict: String, caption: String, score: Int, parts: List<DezPart>, modifier: Modifier) {
    val c = dezColor(score)
    val fill by animateFloatAsState(score / 100f, tween(900), label = "dez")
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brush.verticalGradient(listOf(c.copy(alpha = .16f), Dez.Card))).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
                Text(verdict, style = MaterialTheme.typography.titleLarge, color = Dez.Text)
                Text(caption, style = MaterialTheme.typography.labelSmall, color = Dez.Faint)
            }
            Box(contentAlignment = Alignment.Center) {
                RingProgress(fill, c, 74.dp, 7.dp) {
                    Text("$score", style = MaterialTheme.typography.headlineSmall.merge(TabularNums).copy(fontWeight = FontWeight.Black), color = c, textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        parts.forEach { p ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.label, style = MaterialTheme.typography.labelLarge, color = Dez.Text)
                    Text(p.detail, style = MaterialTheme.typography.labelSmall, color = Dez.Sub, maxLines = 1)
                }
                val f by animateFloatAsState(if (p.max == 0) 0f else p.points.toFloat() / p.max, tween(700), label = "dp")
                Box(Modifier.width(90.dp).height(6.dp).clip(RoundedCornerShape(50)).background(Dez.Card2)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(f.coerceAtLeast(.02f)).clip(RoundedCornerShape(50)).background(c.copy(alpha = .8f)))
                }
                Text("${p.points}/${p.max}", style = MaterialTheme.typography.labelSmall.merge(TabularNums), color = Dez.Sub, textAlign = TextAlign.End, modifier = Modifier.width(42.dp))
            }
        }
    }
}
