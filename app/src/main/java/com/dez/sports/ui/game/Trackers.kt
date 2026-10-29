package com.dez.sports.ui.game

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dez.sports.model.*
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Sport-specific animated tracker shown at the top of the Gamecast tab. */
@Composable
fun Tracker(d: GameDetail) {
    when (d.game.league.kind) {
        SportKind.BASKETBALL -> CourtTracker(d)
        SportKind.FOOTBALL -> DriveTracker(d)
        SportKind.BASEBALL -> PitchTracker(d)
        SportKind.HOCKEY -> RinkTracker(d)
        else -> Unit
    }
}

@Composable
private fun TrackerCard(title: String, onReplay: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = Dez.Sub, modifier = Modifier.weight(1f))
            onReplay?.let {
                Row(Modifier.clip(RoundedCornerShape(50)).background(Dez.Card2).clickable(onClick = it).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Replay, null, tint = Dez.Text, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                    Text("Replay", style = MaterialTheme.typography.labelMedium, color = Dez.Text)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun Caption(text: String?, sub: String? = null) {
    if (text == null) return
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Dez.Sub) }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Dez.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Steps 0..count, one item every [stepMs]; restarted by bumping [key]. */
@Composable
private fun rememberSequence(count: Int, key: Any?, stepMs: Long): State<Int> {
    val step = remember { mutableIntStateOf(0) }
    LaunchedEffect(count, key) {
        step.intValue = 0
        while (step.intValue < count) { delay(stepMs); step.intValue++ }
    }
    return step
}

// ============================================================ basketball

@Composable
private fun CourtTracker(d: GameDetail) {
    val shots = remember(d.plays.size) { d.plays.filter { it.shot != null } }
    if (shots.isEmpty()) return
    val recent = shots.takeLast(12)
    var replay by remember { mutableIntStateOf(0) }
    val idx by rememberSequence(recent.size, replay to shots.size, 1300)
    val cur = recent.getOrNull((idx - 1).coerceAtLeast(0)) ?: return
    val (awayC, homeC) = d.game.colors()
    fun color(p: Play) = if (p.teamId == d.game.home.team.id) homeC else awayC
    val flight = remember { Animatable(0f) }
    LaunchedEffect(idx) { flight.snapTo(0f); flight.animateTo(1f, tween(700, easing = LinearOutSlowInEasing)) }
    val made = cur.shot!!.made
    val pop = cur.text.let { t -> when { !made -> "MISS"; t.contains("three point", true) -> "SPLASH!"; t.contains("dunk", true) -> "SLAM!"; t.contains("layup", true) -> "BUCKET"; else -> "GOOD!" } }

    TrackerCard("COURTCAST", onReplay = { replay++ }) {
        Box(Modifier.fillMaxWidth().aspectRatio(50f / 36f)) {
            Canvas(Modifier.fillMaxSize()) {
                drawHalfCourt()
                recent.take((idx - 1).coerceAtLeast(0)).forEachIndexed { i, p ->
                    val s = p.shot!!; val pt = ft(s.x, s.y); val fade = 0.25f + 0.5f * (i + 1) / recent.size
                    if (s.made) drawCircle(color(p).copy(alpha = fade), size.width / 50f * .7f, pt)
                    else drawCircle(Color.White.copy(alpha = fade * .5f), size.width / 50f * .5f, pt, style = Stroke(2f))
                }
                val s = cur.shot
                val from = ft(s.x, s.y); val rim = ft(25f, 0f)
                val t = flight.value
                val target = if (made || t < .8f) rim else Offset(rim.x + (if (s.x < 25) 1 else -1) * size.width * .08f, rim.y + size.height * .08f)
                val pos = Offset(from.x + (target.x - from.x) * t, from.y + (target.y - from.y) * t)
                val r = size.width / 50f * (0.9f + 0.9f * sin(PI * t).toFloat())
                drawCircle(Color.Black.copy(alpha = .35f), r * .8f, Offset(pos.x + r * .4f, pos.y + r * .9f))
                drawCircle(Color(0xFFFF8A2B), r, pos)
                drawLine(Color.Black.copy(alpha = .45f), Offset(pos.x - r, pos.y), Offset(pos.x + r, pos.y), strokeWidth = 1.5f)
                if (t >= 1f && made) {
                    // swish ripples
                    for (k in 1..3) drawCircle(color(cur).copy(alpha = .5f / k), size.width / 50f * (2f + k * 1.6f), rim, style = Stroke(3f))
                }
            }
            if (flight.value >= .95f) {
                val sc by animateFloatAsState(if (flight.value >= .99f) 1f else .4f, spring(dampingRatio = .35f), label = "pop")
                Text(pop, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black), color = if (made) color(cur) else Dez.Sub,
                    modifier = Modifier.align(Alignment.Center).graphicsLayer { scaleX = sc; scaleY = sc })
            }
        }
        Caption(cur.text, listOfNotNull(cur.period, cur.clock).joinToString(" · ") + "  ·  ${idx.coerceAtLeast(1)}/${recent.size}")
    }
}

// ============================================================ football

private val downRe = Regex("&\\s*(\\d+|Goal)", RegexOption.IGNORE_CASE)

@Composable
private fun DriveTracker(d: GameDetail) {
    val drives = d.drives.filter { dr -> dr.plays.any { it.football?.startYtg != null } }
    if (drives.isEmpty()) return
    var sel by remember(drives.size) { mutableIntStateOf(drives.lastIndex) }
    var replay by remember { mutableIntStateOf(0) }
    val drive = drives[sel.coerceIn(0, drives.lastIndex)]
    // Timeouts, the two-minute warning and period/game ends come through as "plays" from the 0 – skip them.
    val plays = drive.plays.filter { p ->
        val f = p.football
        f?.startYtg != null && f.endYtg != null && f.startYtg in 1..99 &&
            listOf("Timeout", "Warning", "End of", "End Period").none { p.type?.contains(it, true) == true }
    }
    val step by rememberSequence(plays.size, Triple(sel, replay, plays.size), 650)
    val g = d.game
    val (awayC, homeC) = g.colors()
    val offenseHome = drive.teamId == g.home.team.id
    val offC = if (offenseHome) homeC else awayC
    // x from 0 (away end zone, left) to 100 (home end zone, right). Away attacks right.
    fun x(ytg: Int) = if (offenseHome) ytg.toFloat() else 100f - ytg
    val td = drive.result.contains("Touchdown", true) || drive.result.equals("TD", true)
    val done = step >= plays.size

    TrackerCard("DRIVECAST", onReplay = { replay++ }) {
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (drives.lastIndex - 2).coerceAtLeast(0))
        LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(drives) { i, dr ->
                val t = if (dr.teamId == g.home.team.id) g.home.team else g.away.team
                Row(Modifier.clip(RoundedCornerShape(10.dp)).background(if (i == sel) Dez.Card2 else Color.Transparent).clickable { sel = i }.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TeamLogo(t, 16.dp); Spacer(Modifier.width(4.dp))
                    Text(dr.result.ifEmpty { "Drive" }, style = MaterialTheme.typography.labelMedium, color = if (i == sel) Dez.Text else Dez.Sub, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val rows = plays.size.coerceAtLeast(1)
        Box(Modifier.fillMaxWidth().height((70 + rows * 16).coerceAtMost(260).dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val ez = size.width * 0.08f; val field = size.width - 2 * ez; val h = size.height
                fun px(yd: Float) = ez + field * yd / 100f
                drawRoundRect(Color(0xFF1C4A2A), cornerRadius = CornerRadius(14f))
                drawRect(awayC.copy(alpha = .85f), Offset(0f, 0f), Size(ez, h))
                drawRect(homeC.copy(alpha = .85f), Offset(size.width - ez, 0f), Size(ez, h))
                for (i in 1..19) {
                    val xx = px(i * 5f)
                    drawLine(Color.White.copy(alpha = if (i % 2 == 0) .3f else .12f), Offset(xx, 0f), Offset(xx, h), strokeWidth = if (i == 10) 3f else 1.5f)
                }
                val last = plays.getOrNull((step - 1).coerceAtLeast(0))
                last?.football?.let { f ->
                    val los = x(f.endYtg ?: f.startYtg!!)
                    drawLine(Color(0xFF3B82F6), Offset(px(los), 0f), Offset(px(los), h), strokeWidth = 4f)
                    // first-down marker from the next down text, if we have it
                    plays.getOrNull(step)?.football?.downText?.let { downRe.find(it)?.groupValues?.get(1)?.toIntOrNull() }?.let { dist ->
                        val fd = if (offenseHome) los - dist else los + dist
                        drawLine(Color(0xFFFFD60A), Offset(px(fd.coerceIn(0f, 100f)), 0f), Offset(px(fd.coerceIn(0f, 100f)), h), strokeWidth = 4f)
                    }
                }
                val rowH = ((h - 20f) / rows).coerceAtMost(16.dp.toPx())
                plays.take(step).forEachIndexed { i, p ->
                    val f = p.football!!
                    val y = 10f + rowH * (i + .5f)
                    val x0 = px(x(f.startYtg!!)); val x1 = px(x(f.endYtg!!))
                    val gain = if (offenseHome) x1 < x0 else x1 > x0
                    val c = when { f.turnover -> Dez.Live; abs(x1 - x0) < 2f -> Color.White.copy(alpha = .4f); gain -> offC; else -> Dez.Live.copy(alpha = .8f) }
                    drawLine(c, Offset(x0, y), Offset(x1, y), strokeWidth = rowH * .45f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    val dir = if (x1 >= x0) 1 else -1
                    drawPath(Path().apply { moveTo(x1 + dir * rowH * .45f, y); lineTo(x1 - dir * rowH * .1f, y - rowH * .4f); lineTo(x1 - dir * rowH * .1f, y + rowH * .4f); close() }, c)
                }
                last?.football?.let { f ->
                    val bx = px(x(f.endYtg ?: f.startYtg!!)); val by = 10f + rowH * ((step - 1).coerceAtLeast(0) + .5f)
                    drawOval(Color(0xFFB5651D), Offset(bx - 9f, by - 6f), Size(18f, 12f))
                }
            }
            if (done && td) {
                Confetti(Modifier.matchParentSize(), listOf(offC, Color.White), Triple(sel, replay, "td"))
                Text("TOUCHDOWN!", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black), color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        }
        val cur = plays.getOrNull((step - 1).coerceAtLeast(0))
        Caption(cur?.text, listOfNotNull(drive.result.takeIf { done }, drive.description, cur?.football?.downText).joinToString(" · "))
    }
}

// ============================================================ baseball

private class AtBat(val id: String, val pitches: List<Play>, val result: String?, val half: String?, val inning: Int)

@Composable
private fun PitchTracker(d: GameDetail) {
    val atBats = remember(d.plays.size) {
        d.plays.filter { it.atBatId != null }.groupBy { it.atBatId!! }.map { (id, ps) ->
            AtBat(id, ps.filter { it.pitch != null && it.type !in setOf("Play Result", "End Batter/Pitcher") }, ps.lastOrNull { it.pitch == null && it.text.isNotEmpty() && !it.text.startsWith("Pitch") }?.text ?: ps.lastOrNull()?.text, ps.first().half, ps.first().periodNumber)
        }.filter { it.pitches.isNotEmpty() }
    }
    if (atBats.isEmpty()) return
    var sel by remember(atBats.size) { mutableIntStateOf(atBats.lastIndex) }
    var replay by remember { mutableIntStateOf(0) }
    val ab = atBats[sel.coerceIn(0, atBats.lastIndex)]
    val step by rememberSequence(ab.pitches.size, Triple(sel, replay, ab.pitches.size), 800)
    val fly = remember { Animatable(1f) }
    LaunchedEffect(step) { fly.snapTo(0f); fly.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }

    fun pitchColor(r: String) = when {
        r.startsWith("Ball") || r.startsWith("Hit By") -> Color(0xFF34C759)
        r.startsWith("Strike") || r.startsWith("Foul") -> Color(0xFFFF453A)
        else -> Color(0xFF0A84FF)
    }

    TrackerCard("PITCHCAST", onReplay = { replay++ }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("◀", style = MaterialTheme.typography.titleMedium, color = if (sel > 0) Dez.Text else Dez.Faint, modifier = Modifier.clip(CircleShape).clickable(enabled = sel > 0) { sel-- }.padding(8.dp))
            Text("${ab.half ?: ""} ${ab.inning}${ordinalSuffix(ab.inning)} · at-bat ${sel + 1} of ${atBats.size}", style = MaterialTheme.typography.labelLarge, color = Dez.Text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text("▶", style = MaterialTheme.typography.titleMedium, color = if (sel < atBats.lastIndex) Dez.Text else Dez.Faint, modifier = Modifier.clip(CircleShape).clickable(enabled = sel < atBats.lastIndex) { sel++ }.padding(8.dp))
        }
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f).aspectRatio(.85f)) {
                Canvas(Modifier.fillMaxSize()) {
                    // view window in ESPN pitch coordinates
                    val x0 = 20f; val x1 = 210f; val y0 = 85f; val y1 = 245f
                    fun map(x: Float, y: Float) = Offset((x - x0) / (x1 - x0) * size.width, (y - y0) / (y1 - y0) * size.height)
                    drawRoundRect(Color(0xFF151A22), cornerRadius = CornerRadius(18f))
                    val tl = map(87f, 134f); val br = map(143f, 196f)
                    drawRect(Color.White.copy(alpha = .05f), tl, Size(br.x - tl.x, br.y - tl.y))
                    drawRect(Color.White.copy(alpha = .55f), tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(3f))
                    for (k in 1..2) {
                        val gx = tl.x + (br.x - tl.x) * k / 3; val gy = tl.y + (br.y - tl.y) * k / 3
                        drawLine(Color.White.copy(alpha = .15f), Offset(gx, tl.y), Offset(gx, br.y), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                        drawLine(Color.White.copy(alpha = .15f), Offset(tl.x, gy), Offset(br.x, gy), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    }
                    // home plate
                    val pc = map(115f, 222f); val pw = (br.x - tl.x) * .55f
                    drawPath(Path().apply { moveTo(pc.x - pw, pc.y); lineTo(pc.x + pw, pc.y); lineTo(pc.x + pw, pc.y + pw * .35f); lineTo(pc.x, pc.y + pw * .7f); lineTo(pc.x - pw, pc.y + pw * .35f); close() }, Color.White.copy(alpha = .8f))
                    ab.pitches.take(step).forEachIndexed { i, p ->
                        val pt = p.pitch!!
                        val target = map(pt.x, pt.y)
                        val isLatest = i == step - 1
                        val t = if (isLatest) fly.value else 1f
                        val center = Offset(size.width / 2, size.height * .1f)
                        val pos = Offset(center.x + (target.x - center.x) * t, center.y + (target.y - center.y) * t)
                        val r = 15f * (.35f + .65f * t)
                        if (isLatest && t < 1f) drawLine(Color.White.copy(alpha = .25f), center, pos, strokeWidth = r * .6f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawCircle(pitchColor(pt.result), r, pos)
                        drawCircle(Color.White.copy(alpha = .9f), r, pos, style = Stroke(2f))
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                ab.pitches.take(step).forEachIndexed { i, p ->
                    val pt = p.pitch!!
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(pitchColor(pt.result)), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(listOfNotNull(pt.type, pt.mph?.let { "$it mph" }).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Dez.Text, maxLines = 1)
                            Text("${pt.result}  ${pt.balls}-${pt.strikes}", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = Dez.Sub, maxLines = 1)
                        }
                    }
                }
            }
        }
        val live = d.game.baseball
        if (live != null && sel == atBats.lastIndex) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BaseDiamond(live.onFirst, live.onSecond, live.onThird, 36.dp); Spacer(Modifier.width(10.dp))
                Column { CountDots("B", live.balls, 3, Dez.Win); CountDots("S", live.strikes, 2, Dez.Gold); CountDots("O", live.outs, 2, Dez.Live) }
                Spacer(Modifier.width(10.dp))
                Text(listOfNotNull(live.batter?.let { "AB $it" }, live.pitcher?.let { "P $it" }).joinToString("\n"), style = MaterialTheme.typography.labelMedium, color = Dez.Text)
            }
        }
        Caption(if (step >= ab.pitches.size) ab.result else null)
    }
}

private fun ordinalSuffix(n: Int) = when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }

// ============================================================ hockey

private enum class RinkFilter(val label: String) { ALL("All"), SHOTS("On goal"), GOALS("Goals") }

/** Full-rink shot map (doubles as the NHL shot chart). Each team's attempts are drawn toward the net they attacked. */
@Composable
fun RinkTracker(d: GameDetail) {
    val events = remember(d.plays.size) { d.plays.filter { it.rink != null } }
    if (events.isEmpty()) return
    var filter by remember { mutableStateOf(RinkFilter.ALL) }
    var replay by remember { mutableIntStateOf(0) }
    val g = d.game
    val (awayC, homeC) = g.colors()
    val shown = events.filter { when (filter) { RinkFilter.ALL -> true; RinkFilter.SHOTS -> it.rink!!.kind == RinkKind.SHOT || it.rink.kind == RinkKind.GOAL; RinkFilter.GOALS -> it.rink!!.kind == RinkKind.GOAL } }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(filter, replay, events.size) { reveal.snapTo(0f); reveal.animateTo(1f, tween(1600, easing = LinearEasing)) }
    val latest = events.last()
    val puck = remember { Animatable(0f) }
    LaunchedEffect(events.size, replay) { puck.snapTo(0f); delay(1600); puck.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    fun home(p: Play) = p.teamId == g.home.team.id
    // away attacks left net (x = -89), home attacks right net (x = +89)
    fun nx(p: Play) = if (home(p)) abs(p.rink!!.x) else -abs(p.rink!!.x)
    fun ny(p: Play) = if (home(p) == (p.rink!!.x >= 0)) p.rink.y else -p.rink.y

    TrackerCard("RINKCAST · SHOT MAP", onReplay = { replay++ }) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RinkFilter.entries.forEach { f ->
                Text(f.label, style = MaterialTheme.typography.labelMedium, color = if (f == filter) Dez.Text else Dez.Sub,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (f == filter) Dez.Card2 else Color.Transparent).clickable { filter = f }.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(200f / 85f)) {
            Canvas(Modifier.fillMaxSize()) {
                fun map(x: Float, y: Float) = Offset((x + 100f) / 200f * size.width, (42.5f - y) / 85f * size.height)
                drawRink(awayC, homeC)
                val n = (shown.size * reveal.value).toInt()
                shown.take(n).forEach { p ->
                    val pt = map(nx(p), ny(p)); val c = if (home(p)) homeC else awayC
                    val u = size.width / 200f
                    when (p.rink!!.kind) {
                        RinkKind.GOAL -> { drawCircle(c.copy(alpha = .35f), u * 5.5f, pt); drawCircle(c, u * 3.4f, pt); drawCircle(Color.White, u * 3.4f, pt, style = Stroke(2.5f)) }
                        RinkKind.SHOT -> drawCircle(c, u * 2.3f, pt, style = Stroke(2.5f))
                        RinkKind.MISS -> { drawLine(c.copy(alpha = .5f), Offset(pt.x - u * 1.6f, pt.y - u * 1.6f), Offset(pt.x + u * 1.6f, pt.y + u * 1.6f), 2f); drawLine(c.copy(alpha = .5f), Offset(pt.x - u * 1.6f, pt.y + u * 1.6f), Offset(pt.x + u * 1.6f, pt.y - u * 1.6f), 2f) }
                        RinkKind.BLOCK -> drawRect(c.copy(alpha = .45f), Offset(pt.x - u * 1.3f, pt.y - u * 1.3f), Size(u * 2.6f, u * 2.6f))
                    }
                }
                // latest attempt: puck slides to the net
                if (puck.value > 0f) {
                    val from = map(nx(latest), ny(latest)); val net = map(if (home(latest)) 89f else -89f, 0f)
                    val pos = Offset(from.x + (net.x - from.x) * puck.value, from.y + (net.y - from.y) * puck.value)
                    drawLine(Color.White.copy(alpha = .3f), from, pos, strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                    drawCircle(Color.Black, size.width / 200f * 2.2f, pos); drawCircle(Color.White, size.width / 200f * 2.2f, pos, style = Stroke(1.5f))
                }
            }
            if (puck.value >= 1f && latest.rink?.kind == RinkKind.GOAL) {
                Text("GOAL!", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black, fontSize = 30.sp), color = Dez.Live, modifier = Modifier.align(Alignment.Center))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            listOf(g.away to awayC, g.home to homeC).forEach { (side, c) ->
                val mine = events.filter { it.teamId == side.team.id }
                val sog = mine.count { it.rink!!.kind == RinkKind.SHOT || it.rink.kind == RinkKind.GOAL }
                val goals = mine.count { it.rink!!.kind == RinkKind.GOAL }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(6.dp))
                    Text("${side.team.abbreviation}  $goals G · $sog SOG · ${mine.size} att", style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = Dez.Text)
                }
            }
        }
        Caption(latest.text, listOfNotNull(latest.period, latest.clock).joinToString(" · "))
    }
}

private fun DrawScope.drawRink(awayC: Color, homeC: Color) {
    val w = size.width; val h = size.height; val u = w / 200f
    fun map(x: Float, y: Float) = Offset((x + 100f) * u, (42.5f - y) / 85f * h)
    drawRoundRect(Color(0xFFE9EEF3).copy(alpha = .1f), cornerRadius = CornerRadius(28f * u, 28f * u))
    drawRoundRect(Color.White.copy(alpha = .35f), cornerRadius = CornerRadius(28f * u, 28f * u), style = Stroke(2f))
    drawLine(Color(0xFFFF3B30).copy(alpha = .7f), map(0f, 42.5f), map(0f, -42.5f), strokeWidth = 3f * u)
    drawLine(Color(0xFF0A84FF).copy(alpha = .7f), map(-25f, 42.5f), map(-25f, -42.5f), strokeWidth = 2.5f * u)
    drawLine(Color(0xFF0A84FF).copy(alpha = .7f), map(25f, 42.5f), map(25f, -42.5f), strokeWidth = 2.5f * u)
    listOf(-89f, 89f).forEach { gx ->
        drawLine(Color(0xFFFF3B30).copy(alpha = .5f), map(gx, 40f), map(gx, -40f), strokeWidth = 1.2f * u)
        val c = map(gx, 0f)
        val sweep = if (gx < 0) -90f else 90f
        drawArc(Color(0xFF0A84FF).copy(alpha = .35f), sweep, 180f, true, Offset(c.x - 6 * u, c.y - 6 * u), Size(12 * u, 12 * u))
        drawRect((if (gx < 0) awayC else homeC).copy(alpha = .8f), Offset(c.x + (if (gx < 0) -4 * u else 0f), c.y - 3 * u), Size(4 * u, 6 * u))
    }
    listOf(-69f to 22f, -69f to -22f, 69f to 22f, 69f to -22f, 0f to 0f).forEach { (x, y) ->
        drawCircle(Color.White.copy(alpha = .18f), 15f * u, map(x, y), style = Stroke(1.5f))
    }
}
