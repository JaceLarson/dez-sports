package com.dez.sports.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dez.sports.data.SeasonShot
import com.dez.sports.data.SeasonShots
import com.dez.sports.data.ShotSet
import com.dez.sports.model.League
import com.dez.sports.model.SportKind
import com.dez.sports.ui.components.TabularNums
import com.dez.sports.ui.components.readable
import com.dez.sports.ui.theme.Dez
import kotlin.math.hypot

private val Cold = Color(0xFF3A7BD5)
private val Warm = Color(0xFFFFB340)
private val Hot = Color(0xFFFF453A)

/** Blue = cold, amber = average, red = hot – by how often shots from that spot go in. */
private fun heat(pct: Float, avg: Float): Color {
    val d = ((pct - avg) / .15f).coerceIn(-1f, 1f)
    return if (d < 0) lerp(Warm, Cold, -d) else lerp(Warm, Hot, d)
}

/**
 * A whole season of shots on one court (or one offensive zone): a heat map coloured by shooting %
 * plus a zone breakdown. Loads the most recent games first and can pull in the rest on demand.
 * [eventIds] are newest first.
 */
@Composable
fun SeasonShotMap(league: League, eventIds: List<String>, teamId: String?, athleteId: String?, accent: Color, title: String, modifier: Modifier = Modifier) {
    if (!SeasonShots.supported(league) || eventIds.isEmpty()) return
    var limit by remember(eventIds) { mutableIntStateOf(minOf(15, eventIds.size)) }
    var progress by remember { mutableIntStateOf(0) }
    val set by produceState<ShotSet?>(null, eventIds, limit) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { SeasonShots.load(league, eventIds.take(limit), teamId, athleteId) { progress = it } }
    }
    var player by remember(teamId) { mutableStateOf<String?>(null) }
    var dots by remember { mutableStateOf(false) }
    val hockey = league.kind == SportKind.HOCKEY
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = Dez.Text)
                Text(set?.let { "${it.games} games · ${it.shots.size} ${if (hockey) "shots" else "shots"}" } ?: "Loading $progress/$limit games…", style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
            }
            listOf("Heat" to false, "Dots" to true).forEach { (l, v) ->
                Text(l, style = MaterialTheme.typography.labelMedium, color = if (dots == v) Dez.Text else Dez.Sub,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (dots == v) Dez.Card2 else Color.Transparent).clickable { dots = v }.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        val s = set
        if (s == null) { Box(Modifier.fillMaxWidth().aspectRatio(if (hockey) 75f / 85f else 50f / 36f).padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(Dez.Card2)); return@Column }
        // Shooter filter for team maps: the six who shoot most.
        if (athleteId == null) {
            val top = s.shots.groupingBy { it.shooter }.eachCount().entries.filter { it.key != null && s.names[it.key] != null }.sortedByDescending { it.value }.take(6)
            if (top.size > 1) LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Chip("Everyone", player == null) { player = null } }
                items(top) { e -> Chip(s.names[e.key]!!, player == e.key) { player = if (player == e.key) null else e.key } }
            }
        }
        val shots = remember(s, player) { if (player == null) s.shots else s.shots.filter { it.shooter == player } }
        val avg = if (shots.isEmpty()) 0f else shots.count { it.made }.toFloat() / shots.size
        val anim = remember(shots) { Animatable(0f) }
        LaunchedEffect(shots) { anim.animateTo(1f, tween(900)) }
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().aspectRatio(if (hockey) 75f / 85f else 50f / 36f).clip(RoundedCornerShape(16.dp))) {
            if (hockey) { drawZone(); if (dots) shots.forEach { drawRinkDot(it, accent, anim.value) } else rinkHeat(shots, avg, anim.value) }
            else { drawHalfCourt(); if (dots) shots.forEach { drawCourtDot(it, accent, anim.value) } else courtHeat(shots, avg, anim.value) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (dots) "● made  × missed" else "cold", style = MaterialTheme.typography.labelSmall, color = if (dots) Dez.Sub else Cold)
            if (!dots) {
                Box(Modifier.padding(horizontal = 6.dp).width(80.dp).height(6.dp).clip(RoundedCornerShape(50)).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Cold, Warm, Hot))))
                Text("hot · by ${if (hockey) "shooting" else "FG"} %", style = MaterialTheme.typography.labelSmall, color = Hot)
            }
        }
        Spacer(Modifier.height(8.dp))
        Zones(if (hockey) hockeyZones(shots) else courtZones(shots), avg)
        if (limit < eventIds.size) Text("Load all ${eventIds.size} games ›", style = MaterialTheme.typography.labelLarge, color = Color(0xFF64D2FF),
            modifier = Modifier.padding(top = 10.dp).clickable { limit = eventIds.size }.padding(vertical = 4.dp))
    }
}

@Composable
private fun Chip(text: String, sel: Boolean, onClick: () -> Unit) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = if (sel) Color.Black else Dez.Text, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Dez.Text else Dez.Card2).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 6.dp))
}

private data class Zone(val name: String, val made: Int, val att: Int)

private fun courtZones(s: List<SeasonShot>): List<Zone> {
    fun zone(x: SeasonShot) = when {
        x.three && x.y < 9f -> "Corner 3"
        x.three -> "Above break 3"
        hypot(x.x - 25f, x.y) < 8f -> "Paint"
        else -> "Mid-range"
    }
    return listOf("Paint", "Mid-range", "Corner 3", "Above break 3").map { z -> s.filter { zone(it) == z }.let { Zone(z, it.count { sh -> sh.made }, it.size) } }
}

private fun hockeyZones(s: List<SeasonShot>): List<Zone> {
    fun zone(x: SeasonShot) = when {
        x.x >= 69f && kotlin.math.abs(x.y) <= 9f -> "Slot"
        x.x >= 75f -> "Close range"
        x.x < 55f -> "Point"
        else -> "Perimeter"
    }
    return listOf("Slot", "Close range", "Perimeter", "Point").map { z -> s.filter { zone(it) == z }.let { Zone(z, it.count { sh -> sh.made }, it.size) } }
}

@Composable
private fun Zones(zones: List<Zone>, avg: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        zones.forEach { z ->
            val pct = if (z.att == 0) null else z.made.toFloat() / z.att
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background((pct?.let { heat(it, avg) } ?: Dez.Card2).copy(alpha = .18f)).padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(z.name, style = MaterialTheme.typography.labelSmall, color = Dez.Sub, maxLines = 1, textAlign = TextAlign.Center)
                Text(pct?.let { "%.0f%%".format(it * 100) } ?: "–", style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = Dez.Text)
                Text("${z.made}/${z.att}", style = MaterialTheme.typography.labelSmall.merge(TabularNums), color = Dez.Faint)
            }
        }
    }
}

// ---------------------------------------------------------------- basketball

private fun DrawScope.courtHeat(shots: List<SeasonShot>, avg: Float, a: Float) {
    val cell = 2.5f
    val bins = shots.groupBy { (it.x / cell).toInt() to ((it.y + 4.75f) / cell).toInt() }
    val most = bins.values.maxOfOrNull { it.size }?.toFloat() ?: return
    bins.forEach { (k, list) ->
        val pct = list.count { it.made }.toFloat() / list.size
        val vol = (list.size / most).coerceIn(0f, 1f)
        val cx = (k.first + .5f) * cell; val cy = k.second * cell - 4.75f + cell / 2
        val p = ft(cx, cy)
        val r = size.width / 50f * cell * (.45f + .4f * kotlin.math.sqrt(vol)) * a
        drawCircle(heat(pct, avg).copy(alpha = (.12f + .78f * vol) * a), r, p)
    }
}

private fun DrawScope.drawCourtDot(s: SeasonShot, c: Color, a: Float) {
    val p = ft(s.x.coerceIn(0f, 50f), s.y.coerceIn(-4.75f, 31f))
    val r = size.width / 50f * .55f
    if (s.made) drawCircle(c.readable().copy(alpha = .85f * a), r, p)
    else {
        val col = Color.White.copy(alpha = .28f * a)
        drawLine(col, Offset(p.x - r * .6f, p.y - r * .6f), Offset(p.x + r * .6f, p.y + r * .6f), strokeWidth = 2f)
        drawLine(col, Offset(p.x - r * .6f, p.y + r * .6f), Offset(p.x + r * .6f, p.y - r * .6f), strokeWidth = 2f)
    }
}

// ---------------------------------------------------------------- hockey: one attacking zone, goal at the top

/** x (25..100, toward the goal) runs up the canvas; y (-42.5..42.5) runs across. */
private fun DrawScope.rk(x: Float, y: Float) = Offset((y + 42.5f) / 85f * size.width, size.height * (1f - (x - 25f) / 75f))

private fun DrawScope.drawZone() {
    val u = size.width / 85f
    drawRoundRect(Color(0xFFE9EEF3).copy(alpha = .08f), cornerRadius = CornerRadius(26f * u, 26f * u))
    drawRoundRect(Color.White.copy(alpha = .3f), cornerRadius = CornerRadius(26f * u, 26f * u), style = Stroke(2f))
    drawLine(Color(0xFF0A84FF).copy(alpha = .7f), rk(25.5f, -42.5f), rk(25.5f, 42.5f), strokeWidth = 3f * u)
    drawLine(Color(0xFFFF3B30).copy(alpha = .6f), rk(89f, -40f), rk(89f, 40f), strokeWidth = 1.2f * u)
    val g = rk(89f, 0f)
    drawArc(Color(0xFF0A84FF).copy(alpha = .35f), 0f, 180f, true, Offset(g.x - 6 * u, g.y - 6 * u), Size(12 * u, 12 * u))
    drawRect(Color.White.copy(alpha = .7f), Offset(g.x - 3 * u, g.y - 3.5f * u), Size(6 * u, 3.5f * u), style = Stroke(2f))
    listOf(22f, -22f).forEach { y ->
        drawCircle(Color.White.copy(alpha = .18f), 15f * u, rk(69f, y), style = Stroke(1.5f))
        drawCircle(Color(0xFFFF3B30).copy(alpha = .5f), 1f * u, rk(69f, y))
    }
}

private fun DrawScope.rinkHeat(shots: List<SeasonShot>, avg: Float, a: Float) {
    val cell = 4f
    val bins = shots.filter { it.x >= 25f }.groupBy { (it.x / cell).toInt() to ((it.y + 42.5f) / cell).toInt() }
    val most = bins.values.maxOfOrNull { it.size }?.toFloat() ?: return
    val u = size.width / 85f
    bins.forEach { (k, list) ->
        val pct = list.count { it.made }.toFloat() / list.size
        val vol = (list.size / most).coerceIn(0f, 1f)
        val p = rk((k.first + .5f) * cell, (k.second + .5f) * cell - 42.5f)
        // Rarely used spots fade out so the busy areas (the slot, the points) stand out.
        drawCircle(heat(pct, maxOf(avg, .05f)).copy(alpha = (.08f + .82f * vol) * a), cell * u * (.35f + .45f * kotlin.math.sqrt(vol)) * a, p)
    }
}

private fun DrawScope.drawRinkDot(s: SeasonShot, c: Color, a: Float) {
    if (s.x < 25f) return
    val p = rk(s.x, s.y)
    val u = size.width / 85f
    if (s.made) { drawCircle(Color(0xFFFF3B30).copy(alpha = a), 1.6f * u, p); drawCircle(Color.White.copy(alpha = a), 1.6f * u, p, style = Stroke(1.5f)) }
    else drawCircle(c.readable().copy(alpha = .45f * a), 1f * u, p)
}
