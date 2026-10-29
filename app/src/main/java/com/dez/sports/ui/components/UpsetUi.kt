package com.dez.sports.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dez.sports.data.Upset
import com.dez.sports.data.Upsets
import com.dez.sports.model.Game
import com.dez.sports.model.GameDetail
import com.dez.sports.ui.theme.Dez
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val UpsetRed = Color(0xFFFF2D2D)

/** Looks the game's line up only when it's in its final stretch or over, and re-checks as the score changes. */
@Composable
fun rememberUpset(g: Game): Upset? {
    if (!Upsets.candidate(g)) return null
    val u by produceState<Upset?>(null, g.id, g.state, g.away.score, g.home.score, g.period, g.clock?.substringBefore(':')) {
        value = withContext(Dispatchers.Default) { runCatching { Upsets.of(g) }.getOrNull() }
    }
    return u
}

/** A red outline that pulses while the upset is live (read only while drawing, so the card itself isn't rebuilt). */
@Composable
fun Modifier.upsetGlow(u: Upset?, corner: Dp): Modifier {
    if (u == null) return this
    val pulse = if (!u.final) rememberInfiniteTransition(label = "upset").animateFloat(.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "up") else null
    val shape = RoundedCornerShape(corner)
    return drawWithContent {
        drawContent()
        drawOutline(shape.createOutline(size, layoutDirection, this), UpsetRed.copy(alpha = pulse?.value ?: .55f), style = Stroke((if (u.final) 2 else 3).dp.toPx()))
    }
}

/** "🚨 UPSET ALERT · UTAH +13.5 · tied with 2:00 left" across the top of a score card. */
@Composable
fun UpsetBanner(u: Upset, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Brush.horizontalGradient(listOf(UpsetRed.copy(alpha = if (u.final) .35f else .55f), UpsetRed.copy(alpha = .12f))))
        .padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!u.final) Siren(13)
        Text(if (u.final) "UPSET" else " UPSET ALERT", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = .6.sp)
        Text("  ${u.dog.team.abbreviation} ${u.odds} · ${if (u.final) "won as the underdog" else u.situation}", style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = .85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Siren(size: Int) {
    val t = rememberInfiniteTransition(label = "siren")
    val r = t.animateFloat(-14f, 14f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "r")
    Text("🚨", fontSize = size.sp, modifier = Modifier.graphicsLayer { rotationZ = r.value })
}

/** Game page: what the upset is up against, and how far the win probability has swung. */
@Composable
fun UpsetCard(u: Upset, d: GameDetail, modifier: Modifier = Modifier) {
    val dogHome = u.dog.team.id == d.game.home.team.id
    fun dogPct(homePct: Float) = ((if (dogHome) homePct else 1f - homePct) * 100).toInt()
    val wp = d.winProb
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).upsetGlow(u, 20.dp)
        .background(Brush.verticalGradient(listOf(UpsetRed.copy(alpha = .28f), Dez.Card))).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!u.final) { Siren(22); Spacer(Modifier.width(8.dp)) }
            Column(Modifier.weight(1f)) {
                Text(if (u.final) "UPSET" else "UPSET ALERT", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black), color = UpsetRed)
                Text(if (u.final) "${u.dog.team.displayName} pulled it off" else "${u.dog.team.displayName} ${u.situation}",
                    style = MaterialTheme.typography.titleMedium, color = Dez.Text)
                Text("${u.pregame} against ${u.fav.team.displayName}", style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
            }
            TeamLogo(u.dog.team, 40.dp)
        }
        if (wp.size > 2) {
            Spacer(Modifier.height(12.dp))
            val start = dogPct(wp.first().homePct)
            val low = wp.minOf { dogPct(it.homePct) }
            val now = if (u.final) 100 else dogPct(wp.last().homePct)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Pregame" to start, "Lowest" to low, if (u.final) "Final" to now else "Now" to now).forEach { (label, pct) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .25f)).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$pct%", style = MaterialTheme.typography.titleLarge.merge(TabularNums), color = if (label == "Pregame" || label == "Lowest") Dez.Sub else UpsetRed)
                        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Dez.Faint)
                    }
                }
            }
            Text("${u.dog.team.abbreviation} chance to win", style = MaterialTheme.typography.labelSmall, color = Dez.Faint, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
