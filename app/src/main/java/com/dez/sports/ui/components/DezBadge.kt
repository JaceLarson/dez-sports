package com.dez.sports.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import com.dez.sports.data.DezScore
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

/** Small "DEZ 87" pill for finished games. */
@Composable
fun DezBadge(g: Game, modifier: Modifier = Modifier, compact: Boolean = false) {
    val d = remember(g.id, g.away.score, g.home.score, g.state) { DezScores.of(g) } ?: return
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
    val d: DezScore = remember(g.id, g.away.score, g.home.score) { DezScores.of(g) } ?: return
    val c = dezColor(d.score)
    val fill by animateFloatAsState(d.score / 100f, tween(900), label = "dez")
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brush.verticalGradient(listOf(c.copy(alpha = .16f), Dez.Card))).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DEZ SCORE", style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
                Text(d.verdict, style = MaterialTheme.typography.titleLarge, color = Dez.Text)
                Text("How fun this one was to watch", style = MaterialTheme.typography.labelSmall, color = Dez.Faint)
            }
            Box(contentAlignment = Alignment.Center) {
                RingProgress(fill, c, 74.dp, 7.dp) {
                    Text("${d.score}", style = MaterialTheme.typography.headlineSmall.merge(TabularNums).copy(fontWeight = FontWeight.Black), color = c, textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        d.parts.forEach { p ->
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
