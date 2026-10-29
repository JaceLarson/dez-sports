package com.dez.sports.ui.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dez.sports.model.*
import com.dez.sports.ui.components.Flames
import com.dez.sports.ui.components.TabularNums
import com.dez.sports.ui.components.TeamLogo
import com.dez.sports.ui.components.colors
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.lighten
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.tanh

/** One scoring event: who scored and how much (from consecutive play scores). */
data class ScoreEvent(val home: Boolean, val pts: Int, val period: String, val clock: String?, val awayScore: Int, val homeScore: Int)

data class Run(val home: Boolean, val forPts: Int, val againstPts: Int, val period: String, val clock: String?)

data class MomentumData(
    val events: List<ScoreEvent>,
    val series: List<Float>,          // -1 (away) .. +1 (home) after each event
    val current: Float,
    val liveRun: Run?,                // run still going at the end of the feed
    val biggestRun: Run?,
    val leadChanges: Int,
    val ties: Int,
    val largestLeadAway: Int,
    val largestLeadHome: Int,
    val threshold: Int,
    val periodBreaks: List<Int>,      // event indices where a new period starts
)

private fun runThreshold(kind: SportKind) = when (kind) { SportKind.BASKETBALL -> 8; SportKind.FOOTBALL -> 14; SportKind.HOCKEY -> 2; SportKind.BASEBALL -> 3; else -> 99 }

fun momentumOf(d: GameDetail): MomentumData? {
    val kind = d.game.league.kind
    var a = 0; var h = 0
    val events = mutableListOf<ScoreEvent>()
    val periodBreaks = mutableListOf<Int>()
    var lastPeriod: String? = null
    for (p in d.plays) {
        val pa = p.awayScore ?: continue; val ph = p.homeScore ?: continue
        if (pa > a) { if (p.period != lastPeriod && lastPeriod != null) periodBreaks += events.size; events += ScoreEvent(false, pa - a, p.period, p.clock, pa, ph); lastPeriod = p.period }
        if (ph > h) { if (p.period != lastPeriod && lastPeriod != null) periodBreaks += events.size; events += ScoreEvent(true, ph - h, p.period, p.clock, pa, ph); lastPeriod = p.period }
        if (pa >= a) a = pa
        if (ph >= h) h = ph
    }
    if (events.size < 2) return null
    val threshold = runThreshold(kind)
    // Momentum: exponentially-decayed scoring margin, squashed to -1..1.
    val decay = when (kind) { SportKind.BASKETBALL -> 0.82f; SportKind.FOOTBALL -> 0.6f; else -> 0.55f }
    val scale = threshold * 0.9f
    var m = 0f
    val series = events.map { e -> m = m * decay + if (e.home) e.pts.toFloat() else -e.pts.toFloat(); tanh(m / scale) }

    fun runFrom(start: Int, end: Int): Run? {
        var hp = 0; var ap = 0
        for (i in start..end) if (events[i].home) hp += events[i].pts else ap += events[i].pts
        val home = hp >= ap
        val f = if (home) hp else ap; val ag = if (home) ap else hp
        return if (f >= threshold && ag * 4 <= f && events[start].home == home) Run(home, f, ag, events[start].period, events[start].clock) else null
    }
    // Live run: longest window ending at the last event that still qualifies.
    var liveRun: Run? = null
    for (s in events.indices.reversed()) {
        val r = runFrom(s, events.lastIndex)
        if (r != null && (liveRun == null || r.forPts - r.againstPts >= liveRun.forPts - liveRun.againstPts)) liveRun = r
        val part = events.subList(s, events.size)
        val hp = part.filter { it.home }.sumOf { it.pts }; val ap = part.filter { !it.home }.sumOf { it.pts }
        if (minOf(hp, ap) * 2 > maxOf(hp, ap) && maxOf(hp, ap) > threshold * 2) break
    }
    var biggest: Run? = null
    for (s in events.indices) for (e in s + 1..events.lastIndex) {
        val r = runFrom(s, e) ?: continue
        if (biggest == null || r.forPts - r.againstPts > biggest.forPts - biggest.againstPts) biggest = r
    }
    var leadChanges = 0; var ties = 0; var lastLeader = 0; var maxA = 0; var maxH = 0
    for (e in events) {
        val diff = e.homeScore - e.awayScore
        val leader = diff.compareTo(0)
        if (leader == 0) ties++ else { if (lastLeader != 0 && leader != lastLeader) leadChanges++; lastLeader = leader }
        if (-diff > maxA) maxA = -diff; if (diff > maxH) maxH = diff
    }
    return MomentumData(events, series, series.last(), liveRun, biggest, leadChanges, ties, maxA, maxH, threshold, periodBreaks)
}

fun runLabel(kind: SportKind, r: Run, big: Boolean): String = when (kind) {
    SportKind.BASKETBALL -> if (big) "TAKEOVER" else "ON FIRE"
    SportKind.FOOTBALL -> if (big) "STEAMROLLING" else "ROLLING"
    SportKind.HOCKEY -> if (big) "HAT TRICK HEAT" else "HOT STICK"
    SportKind.BASEBALL -> if (big) "RALLY CAPS ON" else "RALLY"
    else -> "HOT"
}

@Composable
fun MomentumCard(d: GameDetail) {
    val m = remember(d.plays.size, d.game.away.score, d.game.home.score) { momentumOf(d) } ?: return
    val g = d.game
    val (awayC, homeC) = g.colors()
    val live = g.state == GameState.LIVE
    val run = m.liveRun?.takeIf { live || g.state == GameState.POST }
    val hotSide = run?.let { if (it.home) g.home else g.away }
    val hotColor = (if (run?.home == true) homeC else awayC).let { if (it.luminance() < .12f) it.lighten(.45f) else it }
    val big = run != null && run.forPts - run.againstPts >= m.threshold * 1.5f
    val knot by animateFloatAsState(m.current, spring(dampingRatio = .5f, stiffness = 60f), label = "knot")
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(1f, 1.06f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "p")

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card)) {
        Box {
            if (run != null && live) Flames(Modifier.matchParentSize(), if (big) 1f else .55f)
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MOMENTUM", style = MaterialTheme.typography.labelMedium, color = Dez.Sub, modifier = Modifier.weight(1f))
                    Text(when { abs(m.current) < .15f -> "Even"; m.current > 0 -> "${g.home.team.abbreviation} pushing"; else -> "${g.away.team.abbreviation} pushing" },
                        style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
                }
                Spacer(Modifier.height(12.dp))
                // Tug-of-war bar: the knot slides toward whoever owns the moment.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamLogo(g.away.team, 26.dp)
                    Box(Modifier.weight(1f).padding(horizontal = 10.dp).height(18.dp)) {
                        Canvas(Modifier.fillMaxSize()) {
                            val w = size.width; val h = size.height
                            val cx = w / 2 + (w / 2) * knot
                            drawRoundRect(Brush.horizontalGradient(listOf(awayC, awayC.copy(alpha = .25f), homeC.copy(alpha = .25f), homeC)),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
                            drawLine(Color.White.copy(alpha = .25f), Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = 2f)
                            drawCircle(Color.White, radius = h * .75f, center = Offset(cx, h / 2))
                            drawCircle(if (knot >= 0) homeC else awayC, radius = h * .5f, center = Offset(cx, h / 2))
                        }
                    }
                    TeamLogo(g.home.team, 26.dp)
                }
                AnimatedVisibility(run != null, enter = scaleIn(spring(dampingRatio = .4f)) + fadeIn(), exit = fadeOut()) {
                    run?.let { r ->
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Text("🔥", fontSize = 22.sp, modifier = Modifier.graphicsLayer { scaleX = pulse.value; scaleY = pulse.value })
                            Spacer(Modifier.width(6.dp))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (live) runLabel(g.league.kind, r, big) else "CLOSED ON A RUN", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, letterSpacing = 2.sp), color = hotColor)
                                Text("${hotSide?.team?.name ?: ""} on a ${r.forPts}-${r.againstPts} run", style = MaterialTheme.typography.titleMedium, color = Dez.Text,
                                    modifier = Modifier.graphicsLayer { scaleX = pulse.value; scaleY = pulse.value })
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("🔥", fontSize = 22.sp, modifier = Modifier.graphicsLayer { scaleX = pulse.value; scaleY = pulse.value })
                        }
                    }
                }
            }
        }
        MomentumWave(m, awayC, homeC, Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(90.dp))
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("LEAD CHANGES", "${m.leadChanges}")
            Stat("TIES", "${m.ties}")
            Stat("BIG LEAD", "${g.away.team.abbreviation} ${m.largestLeadAway} · ${g.home.team.abbreviation} ${m.largestLeadHome}")
        }
        m.biggestRun?.let { r ->
            val t = if (r.home) g.home.team else g.away.team
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .2f)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("BIGGEST RUN", style = MaterialTheme.typography.labelSmall, color = Dez.Sub, modifier = Modifier.weight(1f))
                TeamLogo(t, 18.dp); Spacer(Modifier.width(6.dp))
                Text("${r.forPts}-${r.againstPts}", style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = Dez.Text)
                Text("  · ${r.period}${r.clock?.let { " $it" } ?: ""}", style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
        Text(value, style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = Dez.Text)
    }
}

/** Momentum over the game as a mirrored wave: above the line = home pushing, below = away. */
@Composable
private fun MomentumWave(m: MomentumData, awayC: Color, homeC: Color, modifier: Modifier) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(m.series.size) { anim.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    Canvas(modifier) {
        val w = size.width; val h = size.height; val mid = h / 2
        val n = m.series.size
        m.periodBreaks.forEach { i -> val x = w * i / (n - 1).coerceAtLeast(1); drawLine(Dez.Hairline, Offset(x, 0f), Offset(x, h), strokeWidth = 1.5f) }
        drawLine(Dez.Hairline, Offset(0f, mid), Offset(w, mid), strokeWidth = 1.5f)
        val pts = m.series.mapIndexed { i, v -> Offset(w * i / (n - 1).coerceAtLeast(1), mid - v * (h / 2 - 4)) }
        val line = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) { val p0 = pts[i - 1]; val p1 = pts[i]; val cx = (p0.x + p1.x) / 2; cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y) }
        }
        val area = Path().apply { addPath(line); lineTo(pts.last().x, mid); lineTo(pts[0].x, mid); close() }
        clipRect(right = w * anim.value) {
            clipRect(bottom = mid) { drawPath(area, Brush.verticalGradient(listOf(homeC.copy(alpha = .7f), homeC.copy(alpha = .1f)), 0f, mid)); drawPath(line, homeC, style = Stroke(3f)) }
            clipRect(top = mid) { drawPath(area, Brush.verticalGradient(listOf(awayC.copy(alpha = .1f), awayC.copy(alpha = .7f)), mid, h)); drawPath(line, awayC, style = Stroke(3f)) }
        }
    }
}

