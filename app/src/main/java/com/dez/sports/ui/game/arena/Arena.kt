package com.dez.sports.ui.game.arena

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import com.dez.sports.model.*
import com.dez.sports.ui.components.Confetti
import com.dez.sports.ui.components.LiveDot
import com.dez.sports.ui.components.TabularNums
import com.dez.sports.ui.components.colors
import com.dez.sports.ui.theme.Dez
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.sin

// ============================================================ shared model

/** A player on screen. [side] 0 = away, 1 = home. */
internal data class Who(val id: String?, val number: String?, val name: String, val side: Int, val pos: String? = null)

/** Every player in the box score, looked up by id or by how ESPN writes them in play text. */
internal class Roster(d: GameDetail) {
    private val homeId = d.game.home.team.id
    val tables: List<Pair<Int, StatTable>> = d.boxes.flatMap { b -> val s = if (b.team.id == homeId) 1 else 0; b.tables.map { s to it } }
    private val jerseys = d.jerseys
    private fun who(r: StatRow, s: Int) = Who(r.id, r.jersey ?: r.id?.let(jerseys::get), r.shortName, s, r.position)
    private val everyone: List<List<Who>> = (0..1).map { s ->
        tables.filter { it.first == s }.flatMap { (_, t) -> t.rows.filter { !it.didNotPlay }.map { who(it, s) } }.distinctBy { it.id ?: it.name }
    }
    private val byId: Map<String, Who> = everyone.flatten().filter { it.id != null }.associateBy { it.id!! }

    fun id(id: String?): Who? = id?.let(byId::get)
    fun all(side: Int) = everyone[side]
    fun table(side: Int, vararg names: String): List<Who> =
        tables.filter { (s, t) -> s == side && names.any { t.title.contains(it, true) } }.flatMap { (_, t) -> t.rows.filter { !it.didNotPlay }.map { who(it, side) } }
    fun starters(side: Int): List<Who> {
        val st = tables.filter { it.first == side }.flatMap { (_, t) -> t.rows.filter { it.starter }.map { who(it, side) } }.distinctBy { it.id }
        return st.ifEmpty { all(side) }
    }

    /** NFL play text names players like "J.Goff" and "A.St. Brown"; returns (index in text, player), in reading order. */
    fun named(text: String): List<Pair<Int, Who>> = everyone.flatten().mapNotNull { w ->
        // Box scores may give "J. Bates" or "Jake Bates"; play text says "J.Bates".
        val parts = w.name.trim().split(" ", limit = 2)
        val initial = if (parts.size == 2 && parts[0].isNotEmpty()) "${parts[0].first()}.${parts[1]}" else null
        listOfNotNull(w.name.replaceFirst(". ", "."), initial).filter { it.length > 3 }
            .firstNotNullOfOrNull { key -> text.indexOf(key).takeIf { it >= 0 } }?.let { it to w }
    }.sortedBy { it.first }.distinctBy { it.first }
}

internal abstract class Scene(val play: Play, val side: Int) {
    abstract val star: Who?
    abstract val durationMs: Int
    abstract val headline: String
    open val pop: String? = null
    open val points: Int = 0
    open val popAt: Float = .78f
}

internal class Cam(val rect: Rect, val scale: Float, val left: Float, val top: Float) {
    fun p(x: Float, y: Float) = Offset(rect.left + (x - left) * scale, rect.top + (y - top) * scale)
    fun p(o: Offset) = p(o.x, o.y)
    fun len(v: Float) = v * scale
}

internal class ArenaCtx(
    val game: Game, val kind: SportKind, val kits: List<Kit>, val colors: List<Color>, val text: TextMeasurer,
    val homeLogo: Painter, val clock: Float, val cam: Cam,
) {
    val frame get() = (clock * 6).toInt()
}

internal interface ArenaSport {
    /** World area in sport units, y pointing down the screen. */
    val bounds: Rect
    /** Height of a player sprite in world units. */
    val spriteH: Float
    fun focus(s: Scene, t: Float): Float
    fun DrawScope.surface(c: ArenaCtx)
    fun DrawScope.scene(s: Scene, t: Float, c: ArenaCtx)
}

internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
internal fun lerp(a: Offset, b: Offset, t: Float) = Offset(lerp(a.x, b.x, t), lerp(a.y, b.y, t))
/** Progress of [t] through the window [a, b], clamped 0..1. */
internal fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
internal fun ease(t: Float) = FastOutSlowInEasing.transform(t.coerceIn(0f, 1f))

internal fun DrawScope.label(c: ArenaCtx, text: String, at: Offset, size: Float, color: Color, rotate: Float = 0f, bold: Boolean = true, alpha: Float = 1f) {
    val layout = c.text.measure(text, TextStyle(fontSize = (size / density).sp, fontWeight = if (bold) FontWeight.Black else FontWeight.Medium, color = color.copy(alpha = color.alpha * alpha), letterSpacing = (size / density * .08f).sp))
    val tl = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f)
    if (rotate == 0f) drawText(layout, topLeft = tl) else rotate(rotate, at) { drawText(layout, topLeft = tl) }
}

/** Name tag floating over the featured player. */
internal fun DrawScope.nameTag(c: ArenaCtx, w: Who, feetAt: Offset, spritePx: Float, color: Color) {
    val txt = (w.number?.let { "#$it " } ?: "") + w.name.substringAfterLast(' ').uppercase()
    val layout = c.text.measure(txt, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace))
    val pad = 4.dp.toPx()
    val tl = Offset(feetAt.x - layout.size.width / 2f - pad, feetAt.y - spritePx - layout.size.height - pad * 3)
    drawRoundRect(color.copy(alpha = .92f), tl, Size(layout.size.width + pad * 2, layout.size.height + pad), androidx.compose.ui.geometry.CornerRadius(pad))
    drawText(layout, topLeft = Offset(tl.x + pad, tl.y + pad / 2))
}

internal fun DrawScope.drawLogo(painter: Painter, center: Offset, size: Float, alpha: Float = 1f) {
    translate(center.x - size / 2, center.y - size / 2) { with(painter) { draw(Size(size, size), alpha = alpha) } }
}

// ============================================================ crowd

private val crowdSkin = listOf(Color(0xFFE8B98F), Color(0xFFC68B59), Color(0xFF8D5524), Color(0xFFF1C27D), Color(0xFF5C3A21))

/** Pixel fans fill the stands; the scoring team's fans jump. */
private fun DrawScope.drawCrowd(c: ArenaCtx, cheerSide: Int?, cheer: Float) {
    val cell = 13.dp.toPx()
    val palette = listOf(
        c.colors[1] to 1, c.colors[1] to 1, c.colors[1] to 1, c.kits[1].trim to 1, c.colors[1] to 1,
        c.colors[0] to 0, c.kits[0].trim to 0, Color(0xFF3A3A40) to -1, Color(0xFFDDDDDD) to -1, c.colors[1] to 1,
    )
    drawRect(Color(0xFF09090B))
    val cols = (size.width / cell).toInt() + 2
    val rows = (size.height / cell).toInt() + 1
    for (r in 0 until rows) for (k in 0 until cols) {
        val h = (r * 7919 + k * 104729) xor 0x5bd1e995.toInt()
        val pick = palette[Math.floorMod(h, palette.size)]
        if (Math.floorMod(h shr 3, 9) == 0) continue // empty seat
        val x = k * cell - (if (r % 2 == 0) 0f else cell / 2)
        var y = r * cell
        val phase = Math.floorMod(h shr 5, 100) / 100f
        if (cheerSide != null && pick.second == cheerSide) y -= abs(sin((c.clock * 9f + phase * 6f).toDouble())).toFloat() * cell * .45f * cheer
        else y -= abs(sin((c.clock * 1.3f + phase * 6f).toDouble())).toFloat() * cell * .05f
        val dim = .55f + .25f * phase
        drawRect(pick.first.copy(alpha = dim), Offset(x + cell * .14f, y + cell * .5f), Size(cell * .72f, cell * .5f))
        drawRect(crowdSkin[Math.floorMod(h shr 9, crowdSkin.size)].copy(alpha = dim), Offset(x + cell * .3f, y + cell * .14f), Size(cell * .4f, cell * .38f))
    }
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent, Color.Black.copy(alpha = .55f))))
}

private fun DrawScope.ribbon(c: ArenaCtx, area: Rect) {
    drawRect(Color(0xFF050505), area.topLeft, area.size)
    val msg = "  ${c.game.home.team.displayName.uppercase()}  ★  DEZ SPORTS  ★  ${c.game.away.team.abbreviation} vs ${c.game.home.team.abbreviation}  ★ "
    val layout = c.text.measure(msg, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Black, color = c.colors[1].copy(alpha = .95f), fontFamily = FontFamily.Monospace))
    val w = layout.size.width.toFloat()
    val off = (c.clock * 70f) % w
    clipRect(area.left, area.top, area.right, area.bottom) {
        var x = area.left - off
        while (x < area.right) { drawText(layout, topLeft = Offset(x, area.center.y - layout.size.height / 2f)); x += w }
    }
}

// ============================================================ the screen

private fun sportFor(kind: SportKind): ArenaSport? = when (kind) {
    SportKind.BASKETBALL -> HoopsArena
    SportKind.FOOTBALL -> FootballArena
    SportKind.HOCKEY -> HockeyArena
    SportKind.BASEBALL -> BaseballArena
    else -> null
}

private fun buildScenes(d: GameDetail, r: Roster): List<Scene> = when (d.game.league.kind) {
    SportKind.BASKETBALL -> hoopsScenes(d, r)
    SportKind.FOOTBALL -> footballScenes(d, r)
    SportKind.HOCKEY -> hockeyScenes(d, r)
    SportKind.BASEBALL -> baseballScenes(d, r)
    else -> emptyList()
}

/** Whether this game can be shown as an arena (has plays we know how to animate). */
fun hasArena(d: GameDetail) = sportFor(d.game.league.kind) != null && buildScenes(d, Roster(d)).isNotEmpty()

/**
 * Full-screen animated gamecast for the unfolded phone: the court/field/rink/diamond with the home
 * logo, 8-bit players wearing real jersey numbers, a pixel crowd, and a play-by-play scrubber.
 */
@Composable
fun ArenaCast(d: GameDetail, modifier: Modifier = Modifier, onCharts: () -> Unit = {}) {
    val g = d.game
    val sport = sportFor(g.league.kind) ?: return
    val roster = remember(d.boxes) { Roster(d) }
    val scenes = remember(d.plays.size, d.drives.sumOf { it.plays.size }, roster) { buildScenes(d, roster) }
    if (scenes.isEmpty()) return
    val (awayC, homeC) = g.colors()
    val colors = listOf(awayC, homeC)
    val kits = remember(g.id) {
        listOf(Kit.of(awayC, com.dez.sports.ui.theme.teamColor(g.away.team.altColor, g.away.team.color), g.league.kind),
            Kit.of(homeC, com.dez.sports.ui.theme.teamColor(g.home.team.altColor, g.home.team.color), g.league.kind))
    }
    val text = rememberTextMeasurer()
    val logo = rememberAsyncImagePainter(g.home.team.logo ?: g.home.team.darkLogo)

    // Open on the latest play. Live games keep following new plays; finished games wait for a pick.
    var idx by remember { mutableIntStateOf(scenes.lastIndex) }
    var playing by remember { mutableStateOf(g.state == GameState.LIVE) }
    var scoringOnly by remember { mutableStateOf(false) }
    val scores = remember(scenes) { runningScores(scenes) }
    var fast by remember { mutableStateOf(false) }
    var replay by remember { mutableIntStateOf(0) }
    val i = idx.coerceIn(scenes.indices)
    val sc = scenes[i]
    val t = remember { Animatable(0f) }
    val camHold = remember { floatArrayOf(Float.NaN, Float.NaN) } // [current, at scene start]
    LaunchedEffect(i, replay) {
        camHold[1] = camHold[0]
        t.snapTo(0f)
        t.animateTo(1f, tween((sc.durationMs / if (fast) 2f else 1f).toInt(), easing = LinearEasing))
    }
    LaunchedEffect(i, playing, scenes.size, fast) {
        if (!playing || i >= scenes.lastIndex) return@LaunchedEffect
        snapshotFlow { t.value }.first { it >= 1f }
        // Time to read the play card before the next snap; longer after a score.
        delay(if (fast) 300L else if (sc.points > 0) 2600L else 1600L)
        idx = i + 1
    }
    val clock by rememberInfiniteTransition(label = "clock").animateFloat(0f, 3600f, infiniteRepeatable(tween(3_600_000, easing = LinearEasing)), label = "c")

    val pick: (Int) -> Unit = { k -> if (k == idx) replay++ else idx = k }
    Column(modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(Modifier.fillMaxSize()) {
                val topPad = 34.dp.toPx(); val side = 10.dp.toPx(); val ribbonH = 14.dp.toPx()
                val avail = Rect(side, topPad, size.width - side, size.height - ribbonH - 10.dp.toPx())
                val b = sport.bounds
                var scale = avail.height / b.height
                if (b.width * scale < avail.width) scale = minOf(scale, avail.width / b.width)
                if (b.width * scale > avail.width && sport.spriteH > 20f) scale = avail.width / b.width // baseball never pans
                val fieldW = minOf(avail.width, b.width * scale); val fieldH = b.height * scale
                val rect = Rect(Offset(avail.center.x - fieldW / 2, avail.center.y - fieldH / 2), Size(fieldW, fieldH))
                val visW = fieldW / scale
                val target = sport.focus(sc, t.value)
                val start = camHold[1].takeIf { !it.isNaN() } ?: target
                val camX = lerp(start, target, ease(seg(t.value, 0f, .3f)))
                camHold[0] = camX
                val left = (camX - visW / 2).coerceIn(b.left, (b.right - visW).coerceAtLeast(b.left))
                val ctx = ArenaCtx(g, g.league.kind, kits, colors, text, logo, clock, Cam(rect, scale, left, b.top))
                val cheering = sc.points > 0 && t.value >= sc.popAt
                drawCrowd(ctx, if (cheering) sc.side else null, if (cheering) 1f - seg(t.value, .95f, 1f) * .3f else 0f)
                drawRoundRect(Color.Black.copy(alpha = .6f), Offset(rect.left - 6.dp.toPx(), rect.top - 6.dp.toPx()), Size(rect.width + 12.dp.toPx(), rect.height + 12.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
                clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                    with(sport) { surface(ctx); scene(sc, t.value, ctx) }
                }
                ribbon(ctx, Rect(rect.left, rect.bottom + 6.dp.toPx(), rect.right, rect.bottom + 6.dp.toPx() + ribbonH))
            }
            if (sc.points >= 3 && t.value >= sc.popAt) Confetti(Modifier.fillMaxSize(), listOf(colors[sc.side], Color.White, kits[sc.side].trim), i to replay)
            val showPop = sc.pop != null && t.value >= sc.popAt
            val popScale by animateFloatAsState(if (showPop) 1f else .3f, spring(dampingRatio = .4f, stiffness = 400f), label = "pop")
            if (showPop) {
                Text(sc.pop!!, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
                    color = if (sc.points > 0) Color.White else Color.White.copy(alpha = .85f),
                    modifier = Modifier.align(Alignment.Center).graphicsLayer { scaleX = popScale; scaleY = popScale }
                        .clip(RoundedCornerShape(10.dp)).background(if (sc.points > 0) colors[sc.side].copy(alpha = .9f) else Color.Black.copy(alpha = .6f)).padding(horizontal = 18.dp, vertical = 6.dp))
            }
            ScoreBug(g, sc, scores[i], colors, i, scenes.size, Modifier.align(Alignment.TopStart).padding(6.dp))
            Text("◧ CHARTS", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .8f),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .6f)).clickable(onClick = onCharts).padding(horizontal = 12.dp, vertical = 5.dp))
        }
        Column(Modifier.fillMaxWidth().background(Color(0xFF0B0B0D)).padding(horizontal = 10.dp, vertical = 6.dp)) {
            PlayCard(sc, kits, colors, g, Modifier)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Ctl(Icons.Filled.SkipPrevious, "Previous play") { idx = (i - 1).coerceAtLeast(0) }
                Ctl(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play from here") {
                    if (!playing && i == scenes.lastIndex) replay++
                    playing = !playing
                }
                Ctl(Icons.Filled.SkipNext, "Next play") { idx = (i + 1).coerceAtMost(scenes.lastIndex) }
                Text(if (fast) "2×" else "1×", style = MaterialTheme.typography.labelLarge, color = Dez.Text,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { fast = !fast }.padding(horizontal = 10.dp, vertical = 6.dp))
                Text(if (playing) "Playing through" else "Paused · tap a play", style = MaterialTheme.typography.labelMedium, color = Dez.Sub, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
                Text(if (scoringOnly) "Scoring plays" else "All plays", style = MaterialTheme.typography.labelMedium, color = if (scoringOnly) Color.White else Dez.Sub,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (scoringOnly) colors[1].copy(alpha = .5f) else Color.White.copy(alpha = .08f))
                        .clickable { scoringOnly = !scoringOnly }.padding(horizontal = 10.dp, vertical = 5.dp))
                Spacer(Modifier.width(6.dp))
                val atLive = i == scenes.lastIndex
                Row(Modifier.clip(RoundedCornerShape(50)).background(if (atLive && g.state == GameState.LIVE) Dez.Live else Color.White.copy(alpha = .12f))
                    .clickable { idx = scenes.lastIndex; playing = g.state == GameState.LIVE; replay++ }.padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (g.state == GameState.LIVE) LiveDot(3.dp, Color.White) else Spacer(Modifier.width(4.dp))
                    Text(if (g.state == GameState.LIVE) "LIVE" else "LAST", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = Color.White)
                }
            }
            PlayStrip(scenes, i, scoringOnly, colors, g.league) { pick(it); playing = false }
            Timeline(scenes, i, colors, g.league, Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 6.dp)) { pick(it); playing = false }
        }
    }
}

/** "Q4", "P2", "H1", "TOP 7" – how each sport names a period. */
internal fun periodTag(league: League, n: Int, half: String? = null): String = when (league.kind) {
    SportKind.BASKETBALL -> if (league == League.CBB) (if (n <= 2) "H$n" else "OT${if (n > 3) n - 2 else ""}") else if (n <= 4) "Q$n" else "OT${if (n > 5) n - 4 else ""}"
    SportKind.FOOTBALL -> if (n <= 4) "Q$n" else "OT"
    SportKind.HOCKEY -> if (n <= 3) "P$n" else if (n == 4) "OT" else "SO"
    SportKind.BASEBALL -> (half?.take(3)?.uppercase()?.let { "$it " } ?: "") + n
    else -> "$n"
}

private fun timeOf(league: League, p: Play) = listOfNotNull(periodTag(league, p.periodNumber, p.half).takeIf { p.periodNumber > 0 }, p.clock?.takeIf { it.isNotBlank() && league.kind != SportKind.BASEBALL }).joinToString(" ")

/** Score after each scene; football plays often omit it, so carry the last known score forward. */
private fun runningScores(scenes: List<Scene>): List<Pair<Int, Int>?> {
    var last: Pair<Int, Int>? = null
    return scenes.map { s -> val a = s.play.awayScore; val h = s.play.homeScore; if (a != null && h != null) last = a to h; last }
}

/** Broadcast-style score bug: the score and game clock at the moment of the play on the court. */
@Composable
private fun ScoreBug(g: Game, sc: Scene, score: Pair<Int, Int>?, colors: List<Color>, i: Int, n: Int, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .78f)).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        listOf(g.away, g.home).forEachIndexed { s, side ->
            Box(Modifier.width(4.dp).fillMaxHeight().background(colors[s]))
            Text(side.team.abbreviation, style = MaterialTheme.typography.labelMedium, color = Dez.Sub, modifier = Modifier.padding(start = 6.dp, end = 4.dp))
            Text(score?.let { if (s == 0) it.first else it.second }?.toString() ?: "–", style = MaterialTheme.typography.titleSmall.merge(TabularNums).copy(fontWeight = FontWeight.Black),
                color = Color.White, modifier = Modifier.padding(end = 8.dp))
        }
        Box(Modifier.background(Color.White).padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text(timeOf(g.league, sc.play).ifEmpty { "—" }, style = MaterialTheme.typography.labelLarge.merge(TabularNums).copy(fontWeight = FontWeight.Black), color = Color.Black)
        }
        Text("PLAY ${i + 1}/$n", style = MaterialTheme.typography.labelSmall, color = Dez.Sub, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/** Every play as a tappable chip: when it happened, what it was. Follows the play on the court. */
@Composable
private fun PlayStrip(scenes: List<Scene>, current: Int, scoringOnly: Boolean, colors: List<Color>, league: League, onPick: (Int) -> Unit) {
    val shown = remember(scenes, scoringOnly) { scenes.indices.filter { !scoringOnly || scenes[it].points > 0 } }
    val state = rememberLazyListState()
    val pos = shown.indexOfFirst { it >= current }.let { if (it < 0) shown.lastIndex else it }
    LaunchedEffect(current, scoringOnly) { if (pos >= 0) state.animateScrollToItem((pos - 2).coerceAtLeast(0)) }
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
        items(shown, key = { it }) { k ->
            val s = scenes[k]; val sel = k == current
            Row(Modifier.width(150.dp).clip(RoundedCornerShape(10.dp))
                .background(if (sel) colors[s.side].copy(alpha = .45f) else Color.White.copy(alpha = .06f))
                .then(if (sel) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
                .clickable { onPick(k) }.height(IntrinsicSize.Min)) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(colors[s.side]))
                Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(timeOf(league, s.play), style = MaterialTheme.typography.labelSmall.merge(TabularNums), color = if (sel) Color.White else Dez.Sub, maxLines = 1, modifier = Modifier.weight(1f))
                        if (s.points > 0) Text("+${s.points}", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black), color = Dez.Gold)
                    }
                    Text(s.headline, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.star?.name ?: "", style = MaterialTheme.typography.labelSmall, color = Dez.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Ctl(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Icon(icon, desc, tint = Dez.Text, modifier = Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClick).padding(8.dp))
}

@Composable
private fun PlayCard(sc: Scene, kits: List<Kit>, colors: List<Color>, g: Game, modifier: Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xE6141416)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val star = sc.star
        val kind = g.league.kind
        Canvas(Modifier.size(40.dp, 62.dp)) {
            drawRect(colors[sc.side].copy(alpha = .18f))
            drawPlayer(Offset(size.width / 2, size.height - 3.dp.toPx()), size.height * .88f, kits[star?.side ?: sc.side], kind, star?.number, Pose.STAND)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sc.headline, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace),
                    color = if (colors[sc.side].luminance() > .5f) Color.Black else Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(colors[sc.side]).padding(horizontal = 6.dp, vertical = 1.dp))
                Spacer(Modifier.width(8.dp))
                star?.let { Text((it.number?.let { n -> "#$n " } ?: "") + it.name, style = MaterialTheme.typography.titleSmall, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.weight(1f))
                Text(listOf(sc.play.period, sc.play.clock ?: "").filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium.merge(TabularNums), color = Dez.Sub, maxLines = 1)
            }
            Spacer(Modifier.height(3.dp))
            Text(sc.play.text, style = MaterialTheme.typography.bodyMedium, color = Dez.Text.copy(alpha = .9f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (sc.play.awayScore != null && sc.play.homeScore != null) {
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                listOf(g.away to sc.play.awayScore, g.home to sc.play.homeScore).forEachIndexed { s, (side, score) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(side.team.abbreviation, style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
                        Spacer(Modifier.width(6.dp))
                        Text("$score", style = MaterialTheme.typography.titleMedium.merge(TabularNums).copy(fontWeight = FontWeight.Black),
                            color = if (sc.points > 0 && sc.side == s) colors[s].let { if (it.luminance() < .15f) Color.White else it } else Dez.Text)
                    }
                }
            }
        }
    }
}

/**
 * The game as a bar split into periods (Q1–Q4, P1–P3, innings…). Ticks mark scoring plays in the
 * scorer's colour; the bubble shows when the play on the court happened. Tap or drag to jump.
 */
@Composable
private fun Timeline(scenes: List<Scene>, current: Int, colors: List<Color>, league: League, modifier: Modifier, onSeek: (Int) -> Unit) {
    val text = rememberTextMeasurer()
    val periods = remember(scenes) { scenes.map { it.play.periodNumber }.distinct().sorted() }
    // x position (0..1) of every scene: equal-width period segments, plays spread evenly within each
    val xs = remember(scenes) {
        val byP = scenes.indices.groupBy { scenes[it].play.periodNumber }
        val segW = 1f / periods.size.coerceAtLeast(1)
        FloatArray(scenes.size).also { out ->
            periods.forEachIndexed { pi, p -> val ks = byP[p].orEmpty(); ks.forEachIndexed { j, k -> out[k] = segW * (pi + (j + .5f) / ks.size) } }
        }
    }
    fun nearest(f: Float) = xs.indices.minByOrNull { kotlin.math.abs(xs[it] - f) } ?: 0
    Canvas(modifier
        .pointerInput(scenes.size) { detectTapGestures { onSeek(nearest(it.x / size.width)) } }
        .pointerInput(scenes.size) { detectHorizontalDragGestures { ch, _ -> onSeek(nearest(ch.position.x / size.width)) } }) {
        val barY = size.height * .48f; val barH = 5.dp.toPx(); val gap = 3.dp.toPx()
        val segW = size.width / periods.size.coerceAtLeast(1)
        val curP = scenes[current].play.periodNumber
        periods.forEachIndexed { pi, p ->
            val x0 = pi * segW + gap / 2; val w = segW - gap
            drawRoundRect(Color.White.copy(alpha = if (p == curP) .28f else .12f), Offset(x0, barY - barH / 2), Size(w, barH), androidx.compose.ui.geometry.CornerRadius(barH))
            val lab = text.measure(periodTag(league, p).uppercase(), TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (p == curP) Color.White else Dez.Faint))
            drawText(lab, topLeft = Offset(x0 + w / 2 - lab.size.width / 2f, size.height - lab.size.height))
        }
        scenes.forEachIndexed { k, s ->
            if (s.points > 0) {
                val h = (4 + s.points.coerceAtMost(6) * 1.5f).dp.toPx()
                drawLine(colors[s.side], Offset(xs[k] * size.width, barY - h), Offset(xs[k] * size.width, barY + h), strokeWidth = 2.dp.toPx())
            }
        }
        val cx = xs[current] * size.width
        drawCircle(Color.White, 7.dp.toPx(), Offset(cx, barY))
        drawCircle(colors[scenes[current].side], 4.5.dp.toPx(), Offset(cx, barY))
        val bubble = text.measure(timeOf(league, scenes[current].play).ifEmpty { "Play ${current + 1}" }, TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.Black))
        val bw = bubble.size.width + 10.dp.toPx(); val bh = bubble.size.height + 2.dp.toPx()
        val bx = (cx - bw / 2).coerceIn(0f, size.width - bw)
        drawRoundRect(Color.White, Offset(bx, 0f), Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(bh / 2))
        drawText(bubble, topLeft = Offset(bx + 5.dp.toPx(), 1.dp.toPx()))
    }
}
