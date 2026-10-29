package com.dez.sports.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.dez.sports.model.GameDetail
import com.dez.sports.model.Shot
import com.dez.sports.ui.components.TeamLogo
import com.dez.sports.ui.components.colors
import com.dez.sports.ui.components.colorOf
import com.dez.sports.ui.theme.Dez

@Composable
fun WinProbabilityChart(d: GameDetail, modifier: Modifier = Modifier) {
    val g = d.game
    val final = g.state.name == "POST"
    val homeWon = (g.home.score?.toDoubleOrNull() ?: 0.0) > (g.away.score?.toDoubleOrNull() ?: 0.0)
    // ESPN's trailing points after the buzzer are sometimes junk; pin a final game to its winner.
    val pts = if (final && d.winProb.size > 2) d.winProb.dropLast(1) + com.dez.sports.model.WinProbPoint(if (homeWon) 1f else 0f) else d.winProb
    if (pts.size < 2) return
    val (awayC, homeC) = g.colors()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (final) {
                val loser = if (homeWon) g.away else g.home
                val loserPeak = (if (homeWon) pts.maxOf { 1 - it.homePct } else pts.maxOf { it.homePct }) * 100
                TeamLogo(loser.team, 22.dp); Spacer(Modifier.width(8.dp))
                Text("${loser.team.name} peaked at %.0f%%".format(loserPeak), style = MaterialTheme.typography.titleMedium, color = Dez.Text)
                Spacer(Modifier.width(8.dp))
                Text(if (loserPeak > 75) "· comeback!" else if (loserPeak < 25) "· never close" else "· win probability", style = MaterialTheme.typography.bodySmall, color = Dez.Sub)
            } else {
                val last = pts.last().homePct
                val leaderHome = last >= 0.5f
                TeamLogo(if (leaderHome) g.home.team else g.away.team, 22.dp)
                Spacer(Modifier.width(8.dp))
                Text("%.1f%%".format((if (leaderHome) last else 1 - last) * 100), style = MaterialTheme.typography.titleLarge, color = Dez.Text)
                Spacer(Modifier.width(6.dp))
                Text("win probability", style = MaterialTheme.typography.bodySmall, color = Dez.Sub)
            }
        }
        Spacer(Modifier.height(10.dp))
        Box {
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                val w = size.width; val h = size.height
                // 50% line + 25/75 guides
                listOf(0.25f, 0.5f, 0.75f).forEach { y -> drawLine(Dez.Hairline, Offset(0f, h * y), Offset(w, h * y), strokeWidth = if (y == .5f) 2f else 1f) }
                val n = pts.size
                fun xy(i: Int) = Offset(w * i / (n - 1), h * (1 - pts[i].homePct)) // home on top
                val line = Path().apply { moveTo(xy(0).x, xy(0).y); for (i in 1 until n) lineTo(xy(i).x, xy(i).y) }
                val area = Path().apply { addPath(line); lineTo(w, h / 2); lineTo(0f, h / 2); close() }
                val drawW = w * progress.value
                clipRect(right = drawW) {
                    clipRect(bottom = h / 2) { drawPath(area, homeC.copy(alpha = .35f)) }
                    clipRect(top = h / 2) { drawPath(area, awayC.copy(alpha = .35f)) }
                    clipRect(bottom = h / 2) { drawPath(line, homeC, style = Stroke(4f)) }
                    clipRect(top = h / 2) { drawPath(line, awayC, style = Stroke(4f)) }
                }
            }
            TeamLogo(d.game.home.team, 18.dp, Modifier.align(Alignment.TopStart).padding(4.dp))
            TeamLogo(d.game.away.team, 18.dp, Modifier.align(Alignment.BottomStart).padding(4.dp))
        }
    }
}

/** Two half-courts side by side: away team's shots on the left, home on the right. ESPN coords: x 0..50 ft, y = ft from the rim line. */
@Composable
fun ShotChart(d: GameDetail, modifier: Modifier = Modifier) {
    val shots = d.shots
    if (shots.size < 4) return
    val (awayC, homeC) = d.game.colors()
    val anim = remember { Animatable(0f) }
    LaunchedEffect(shots.size) { anim.animateTo(1f, tween(1200)) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            listOf(d.game.away, d.game.home).forEach { side ->
                val s = shots.filter { it.teamId == side.team.id }
                val made = s.count { it.made }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    TeamLogo(side.team, 18.dp); Spacer(Modifier.width(6.dp))
                    Text("$made/${s.size} FG · ${if (s.isEmpty()) 0 else made * 100 / s.size}%", style = MaterialTheme.typography.labelLarge, color = Dez.Sub)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(d.game.away.team.id to awayC, d.game.home.team.id to homeC).forEach { (tid, c) ->
                Canvas(Modifier.weight(1f).aspectRatio(50f / 36f)) {
                    drawHalfCourt()
                    val list = shots.filter { it.teamId == tid }
                    val shown = (list.size * anim.value).toInt()
                    list.take(shown).forEach { drawShot(it, c) }
                }
            }
        }
    }
}

private const val BASELINE = -4.75f
private const val VISIBLE_LEN = 36f

internal fun DrawScope.ft(x: Float, y: Float) = Offset(size.width * x / 50f, size.height * (y - BASELINE) / VISIBLE_LEN)

internal fun DrawScope.drawHalfCourt() {
    val line = Color.White.copy(alpha = .16f); val sw = 2.5f
    drawRoundRect(Color(0xFF17171A), cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f))
    val k = size.width / 50f
    // paint
    val tl = ft(17f, BASELINE); drawRect(Color.White.copy(alpha = .04f), tl, Size(16 * k, 19 * k))
    drawRect(line, tl, Size(16 * k, 19 * k), style = Stroke(sw))
    // free throw circle
    drawCircle(line, 6 * k, ft(25f, 14.25f), style = Stroke(sw))
    // rim + backboard
    drawCircle(Color(0xFFFF7A2F).copy(alpha = .8f), 0.75f * k, ft(25f, 0f), style = Stroke(sw))
    drawLine(line, ft(22f, -1f), ft(28f, -1f), strokeWidth = sw * 1.4f)
    // three point line
    val r = 23.75f * k; val c = ft(25f, 0f)
    val cornerY = 8.95f
    drawLine(line, ft(3f, BASELINE), ft(3f, cornerY), strokeWidth = sw)
    drawLine(line, ft(47f, BASELINE), ft(47f, cornerY), strokeWidth = sw)
    val ang = Math.toDegrees(kotlin.math.asin((cornerY / 23.75f).toDouble())).toFloat()
    drawArc(line, ang, 180f - 2 * ang, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(sw))
    // half court line at bottom
    drawLine(line, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), strokeWidth = sw)
}

private fun DrawScope.drawShot(s: Shot, c: Color) {
    val p = ft(s.x.coerceIn(0f, 50f), s.y.coerceIn(BASELINE, BASELINE + VISIBLE_LEN))
    val r = size.width / 50f * 0.75f
    if (s.made) { drawCircle(c, r, p); drawCircle(Color.White.copy(alpha = .7f), r, p, style = Stroke(1.5f)) }
    else {
        val col = Color.White.copy(alpha = .35f)
        drawLine(col, Offset(p.x - r * .7f, p.y - r * .7f), Offset(p.x + r * .7f, p.y + r * .7f), strokeWidth = 2.5f)
        drawLine(col, Offset(p.x - r * .7f, p.y + r * .7f), Offset(p.x + r * .7f, p.y - r * .7f), strokeWidth = 2.5f)
    }
}

