package com.dez.sports.ui.game.arena

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import com.dez.sports.model.GameDetail
import com.dez.sports.model.Play
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Baseball. World units are feet with home plate at (0, 0) and centre field up the screen; the stored
 * y is flipped (y' = 280 - y) so it points down like the other arenas. Not to scale past the infield.
 */

private const val FENCE = 268f
private val resultWords = listOf("homered", "tripled", "doubled", "singled", "walked", "hit by pitch", "struck out", "grounded", "flied", "popped",
    "lined", "fouled out", "reached", "sacrifice", "double play", "out at")
private fun w(x: Float, y: Float) = Offset(x, 280f - y)
private val bases = listOf(w(0f, 0f), w(63.6f, 63.6f), w(0f, 127.3f), w(-63.6f, 63.6f), w(0f, 0f))
private val fieldSpots = mapOf(
    "P" to w(0f, 60.5f), "C" to w(0f, -8f), "1B" to w(70f, 88f), "2B" to w(33f, 128f), "SS" to w(-33f, 128f),
    "3B" to w(-70f, 88f), "LF" to w(-130f, 205f), "CF" to w(0f, 238f), "RF" to w(130f, 205f),
)

internal class BaseballScene(
    play: Play, side: Int, val result: Result, val pitches: List<Play>, val target: Offset?, val batter: Who?, val pitcher: Who?,
    val fielders: Map<String, Who>, override val points: Int, override val headline: String, override val pop: String?,
) : Scene(play, side) {
    enum class Result(val bases: Int, val contact: Boolean, val fly: Boolean) {
        SINGLE(1, true, false), DOUBLE(2, true, true), TRIPLE(3, true, true), HOMER(4, true, true),
        WALK(1, false, false), STRIKEOUT(0, false, false), GROUNDOUT(0, true, false), FLYOUT(0, true, true), LINEOUT(0, true, true), OTHER(0, false, false)
    }
    override val star: Who? get() = if (result == Result.STRIKEOUT) pitcher ?: batter else batter
    val shown get() = pitches.takeLast(5)
    val pitchMs get() = 520
    val resultMs get() = when (result) { Result.HOMER -> 3000; Result.STRIKEOUT, Result.WALK, Result.OTHER -> 1500; else -> 2300 }
    override val durationMs get() = shown.size * pitchMs + resultMs
    override val popAt get() = (shown.size * pitchMs).toFloat() / durationMs + (if (result.contact) .35f else .05f) * resultMs / durationMs
}

internal fun baseballScenes(d: GameDetail, r: Roster): List<Scene> {
    val home = d.game.home.team.id
    var runs = 0 to 0
    return d.plays.filter { it.atBatId != null }.groupBy { it.atBatId!! }.values.mapNotNull { ps ->
        val last = ps.last { it.text.isNotBlank() }
        val pitches = ps.filter { it.pitch != null }
        val first = ps.first()
        val fieldSide = if (first.half.equals("Top", true)) 1 else 0
        val batSide = 1 - fieldSide
        fun role(name: String) = ps.firstNotNullOfOrNull { p -> p.athletes.indices.firstOrNull { p.roles.getOrNull(it) == name }?.let { r.id(p.athletes[it]) } }
        val batter = role("batter")
        val pitcher = role("pitcher") ?: r.table(fieldSide, "Pitch").firstOrNull()
        val resultPlay = ps.lastOrNull { p -> resultWords.any { p.text.contains(it, true) } } ?: last
        val result = resultPlay.text
        val t = result.lowercase().substringBefore(",").substringBefore(";")
        val res = when {
            t.contains("homered") -> BaseballScene.Result.HOMER
            t.contains("tripled") -> BaseballScene.Result.TRIPLE
            t.contains("doubled") -> BaseballScene.Result.DOUBLE
            t.contains("singled") -> BaseballScene.Result.SINGLE
            t.contains("walked") || t.contains("hit by pitch") -> BaseballScene.Result.WALK
            t.contains("struck out") -> BaseballScene.Result.STRIKEOUT
            t.contains("grounded") || t.contains("double play") || t.contains("reached on") -> BaseballScene.Result.GROUNDOUT
            t.contains("flied") || t.contains("popped") || t.contains("fouled out") || t.contains("sacrifice fly") -> BaseballScene.Result.FLYOUT
            t.contains("lined") -> BaseballScene.Result.LINEOUT
            else -> BaseballScene.Result.OTHER
        }
        if (res == BaseballScene.Result.OTHER) return@mapNotNull null
        val target = direction(t, res)
        // fielders by the position they started at
        val fielders = r.table(fieldSide, "Batt").filter { it.pos != null }.distinctBy { it.pos }.associateBy { it.pos!! } +
            listOfNotNull(pitcher?.let { "P" to it })
        val score = (ps.mapNotNull { it.awayScore }.lastOrNull() ?: runs.first) to (ps.mapNotNull { it.homeScore }.lastOrNull() ?: runs.second)
        val scored = if (batSide == 1) score.second - runs.second else score.first - runs.first
        runs = score
        val headline = when (res) {
            BaseballScene.Result.HOMER -> "HOME RUN"; BaseballScene.Result.TRIPLE -> "TRIPLE"; BaseballScene.Result.DOUBLE -> "DOUBLE"
            BaseballScene.Result.SINGLE -> "SINGLE"; BaseballScene.Result.WALK -> if (t.contains("hit by")) "HIT BY PITCH" else "WALK"
            BaseballScene.Result.STRIKEOUT -> "STRIKEOUT"; BaseballScene.Result.GROUNDOUT -> if (t.contains("double play")) "DOUBLE PLAY" else "GROUNDOUT"
            BaseballScene.Result.FLYOUT -> "FLYOUT"; BaseballScene.Result.LINEOUT -> "LINEOUT"; else -> "AT-BAT"
        }
        val pop = when (res) {
            BaseballScene.Result.HOMER -> "HOME RUN!"; BaseballScene.Result.TRIPLE -> "TRIPLE!"; BaseballScene.Result.DOUBLE -> "DOUBLE!"
            BaseballScene.Result.SINGLE -> "BASE HIT"; BaseballScene.Result.STRIKEOUT -> if (t.contains("looking")) "K LOOKING" else "K!"
            BaseballScene.Result.WALK -> "TAKE YOUR BASE"; BaseballScene.Result.OTHER -> null
            else -> if (t.contains("double play")) "DOUBLE PLAY!" else "OUT"
        }
        BaseballScene(resultPlay, batSide, res, pitches, target, batter, pitcher, fielders, scored.coerceAtLeast(0), headline, pop)
    }
}

private fun direction(t: String, res: BaseballScene.Result): Offset? {
    if (!res.contact) return null
    var (x, y) = when {
        t.contains("left center") -> -78f to 232f
        t.contains("right center") -> 78f to 232f
        t.contains("left field line") || t.contains("down the left") -> -150f to 165f
        t.contains("right field line") || t.contains("down the right") -> 150f to 165f
        t.contains("to left") -> -125f to 200f
        t.contains("to center") -> 0f to 238f
        t.contains("to right") -> 125f to 200f
        t.contains("shortstop") -> -36f to 122f
        t.contains("to second") -> 34f to 124f
        t.contains("to third") -> -66f to 86f
        t.contains("to first") -> 66f to 86f
        t.contains("to pitcher") -> 0f to 62f
        t.contains("to catcher") -> 0f to -8f
        res == BaseballScene.Result.GROUNDOUT -> -36f to 122f
        else -> 0f to 220f
    }
    if (t.contains("deep")) { x *= 1.1f; y *= 1.1f }
    if (t.contains("shallow")) { x *= .8f; y *= .8f }
    if (res == BaseballScene.Result.HOMER) { val k = (FENCE + 30f) / hypot(x, y); x *= k; y *= k }
    return w(x, y)
}

internal object BaseballArena : ArenaSport {
    override val bounds = Rect(-238f, 0f, 238f, 322f)
    override val spriteH = 30f
    override fun focus(s: Scene, t: Float) = 0f

    private fun shape(c: ArenaCtx, radius: Float, foul: Float): Path = Path().apply {
        val cam = c.cam
        val a = cam.p(w(-foul, -foul)); moveTo(a.x, a.y)
        val b = cam.p(w(foul, -foul)); lineTo(b.x, b.y)
        for (k in 0..48) {
            val ang = (38f + (142f - 38f) * k / 48f) * PI.toFloat() / 180f
            val p = cam.p(w(radius * cos(ang), radius * sin(ang))); lineTo(p.x, p.y)
        }
        close()
    }

    override fun DrawScope.surface(c: ArenaCtx) {
        val cam = c.cam
        val dirt = Color(0xFFB7794A); val grass = Color(0xFF2F8A3E)
        val outer = shape(c, FENCE, 46f)
        drawPath(outer, dirt)
        val inner = shape(c, FENCE - 14f, 40f)
        drawPath(inner, grass)
        clipPath(inner) {
            var rr = 30f; var k = 0
            while (rr < FENCE) { drawCircle(if (k % 2 == 0) Color(0x12FFFFFF) else Color(0x0E000000), cam.len(rr), cam.p(w(0f, 0f)), style = Stroke(cam.len(18f))); rr += 18f; k++ }
        }
        drawPath(outer, Color(0xFF16361D), style = Stroke(cam.len(4f)))
        drawPath(outer, Color(0xFFFFD60A).copy(alpha = .7f), style = Stroke(cam.len(1f)))
        label(c, c.game.home.team.name.uppercase(), cam.p(w(0f, 178f)), cam.len(22f), Color.White.copy(alpha = .16f))
        // infield
        drawCircle(dirt, cam.len(95f), cam.p(w(0f, 62f)))
        val diamond = Path().apply {
            listOf(w(0f, 10f), w(55f, 63.6f), w(0f, 117f), w(-55f, 63.6f)).forEachIndexed { i, o -> val p = cam.p(o); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
        drawPath(diamond, grass)
        drawCircle(dirt, cam.len(9f), cam.p(w(0f, 60.5f)))
        drawRect(Color.White, cam.p(w(-1.5f, 61f)), Size(cam.len(3f), cam.len(1f)))
        drawCircle(dirt, cam.len(13f), cam.p(w(0f, 0f)))
        // foul lines
        drawLine(Color.White, cam.p(w(0f, 0f)), cam.p(w(-189f, 189f)), cam.len(.9f))
        drawLine(Color.White, cam.p(w(0f, 0f)), cam.p(w(189f, 189f)), cam.len(.9f))
        bases.drop(1).dropLast(1).forEach { b -> drawRect(Color.White, Offset(cam.p(b).x - cam.len(2.5f), cam.p(b).y - cam.len(2.5f)), Size(cam.len(5f), cam.len(5f))) }
        val hp = cam.p(w(0f, 0f))
        drawRect(Color.White, Offset(hp.x - cam.len(2f), hp.y - cam.len(2f)), Size(cam.len(4f), cam.len(4f)))
        drawRect(Color.White.copy(alpha = .8f), cam.p(w(-9f, 3f)), Size(cam.len(5f), cam.len(7f)), style = Stroke(cam.len(.5f)))
        drawRect(Color.White.copy(alpha = .8f), cam.p(w(4f, 3f)), Size(cam.len(5f), cam.len(7f)), style = Stroke(cam.len(.5f)))
        drawLogo(c.homeLogo, cam.p(w(0f, -28f)), cam.len(20f))
    }

    override fun DrawScope.scene(s: Scene, t: Float, c: ArenaCtx) {
        val b = s as BaseballScene
        val cam = c.cam
        val px = cam.len(spriteH)
        val fieldSide = 1 - b.side
        val ms = t * b.durationMs
        val pitchPhase = b.shown.size * b.pitchMs
        val rt = if (ms < pitchPhase) 0f else ((ms - pitchPhase) / b.resultMs).coerceIn(0f, 1f)
        val mound = w(0f, 60.5f); val plate = w(0f, 1f)

        // fielders; the closest one chases a ball in play
        val chaser = b.target?.let { tg -> fieldSpots.minByOrNull { (pos, at) -> if (pos == "C") 1e9f else hypot(at.x - tg.x, at.y - tg.y) }?.key }
        data class Spr(val at: Offset, val who: Who?, val side: Int, val pose: Pose, val face: Boolean, val star: Boolean, val kit: Kit? = null)
        val sprites = mutableListOf<Spr>()
        fieldSpots.forEach { (pos, at) ->
            var p = at
            if (pos == chaser && b.target != null && b.result != BaseballScene.Result.HOMER) p = lerp(at, b.target, ease(seg(rt, .05f, .55f)) * .92f)
            val pose = when {
                pos == "P" && ms < pitchPhase -> if ((ms % b.pitchMs) / b.pitchMs < .3f) Pose.SHOOT else Pose.STAND
                pos == "C" -> Pose.CROUCH
                pos == chaser && rt > .05f -> Pose.RUN
                else -> Pose.STAND
            }
            sprites += Spr(p, b.fielders[pos], fieldSide, pose, pos != "1B" && pos != "RF", pos == "P" && b.result == BaseballScene.Result.STRIKEOUT)
        }
        sprites += Spr(w(0f, -14f), null, fieldSide, Pose.CROUCH, true, false, Kit.Ref)
        // the batter, then running the bases
        val reached = when (b.result) { BaseballScene.Result.WALK -> 1; else -> b.result.bases }
        val batterAt = when {
            rt <= 0f || b.result == BaseballScene.Result.STRIKEOUT -> w(-6f, 1f)
            reached > 0 -> {
                val path = bases.take(reached + 1)
                val k = ease(seg(rt, .1f, .92f)) * reached
                val leg = k.toInt().coerceAtMost(reached - 1)
                lerp(path[leg], path[leg + 1], k - leg)
            }
            else -> lerp(w(0f, 0f), bases[1], ease(seg(rt, .1f, .6f)) * .55f)
        }
        val swung = b.result.contact && ms >= pitchPhase - b.pitchMs * .35f && rt < .08f
        sprites += Spr(batterAt, b.batter, b.side, if (swung) Pose.SHOOT else if (rt > .1f && reached > 0) Pose.RUN else Pose.STAND, true, b.result != BaseballScene.Result.STRIKEOUT)
        sprites.sortedBy { it.at.y }.forEach { sp ->
            drawPlayer(cam.p(sp.at), px, sp.kit ?: c.kits[sp.side], c.kind, sp.who?.number, sp.pose, sp.face, c.frame, 0f, if (sp.star) c.colors[sp.side] else null)
        }
        // the ball
        if (ms < pitchPhase) {
            val k = (ms % b.pitchMs) / b.pitchMs
            val f = seg(k, .25f, .85f)
            if (f > 0f) baseball(cam.p(lerp(mound, plate, f)), cam.len(1.6f), cam.len(4f + sin(PI * f).toFloat() * 2f))
            val idx = (ms / b.pitchMs).toInt().coerceAtMost(b.shown.lastIndex)
            b.shown.getOrNull(idx)?.pitch?.let { pt ->
                val txt = listOfNotNull(pt.mph?.let { "$it MPH" }, pt.type?.uppercase()).joinToString(" ")
                if (txt.isNotEmpty() && k > .7f) label(c, txt, cam.p(w(70f, 20f)), cam.len(7f), Color.White, bold = true)
                if (k > .7f) label(c, "${pt.balls}-${pt.strikes}", cam.p(w(70f, 5f)), cam.len(9f), Color(0xFFFFD60A))
            }
        } else if (b.target != null) {
            val f = seg(rt, 0f, if (b.result == BaseballScene.Result.HOMER) .6f else .5f)
            val hit = lerp(plate, b.target, ease(f))
            val lift = if (b.result.fly) sin(PI * f).toFloat() * (if (b.result == BaseballScene.Result.HOMER) 90f else 55f) else sin(PI * f * 3).toFloat().let { kotlin.math.abs(it) } * 4f
            val endAt = if (b.result.bases == 0 && f >= 1f) lerp(b.target, bases[1], ease(seg(rt, .55f, .8f))) else hit
            baseball(cam.p(endAt), cam.len(1.6f + lift * .02f), cam.len(lift + 2f))
            if (b.result == BaseballScene.Result.HOMER && rt > .6f) fireworks(c, rt)
        }
        b.star?.let { who -> sprites.firstOrNull { it.who?.id == who.id }?.let { nameTag(c, who, cam.p(it.at), px, c.colors[who.side]) } }
    }

    private fun DrawScope.baseball(at: Offset, r: Float, lift: Float) {
        drawCircle(Color.Black.copy(alpha = .3f), r, Offset(at.x + lift * .1f, at.y))
        drawCircle(Color.White, r, Offset(at.x, at.y - lift))
        drawCircle(Color(0xFFD7262E), r, Offset(at.x, at.y - lift), style = Stroke(r * .25f))
    }

    private fun DrawScope.fireworks(c: ArenaCtx, rt: Float) {
        val cam = c.cam
        listOf(w(-120f, 250f), w(0f, 266f), w(120f, 250f)).forEachIndexed { i, o ->
            val k = seg(rt, .6f + i * .08f, .95f)
            if (k <= 0f) return@forEachIndexed
            val center = cam.p(o)
            for (a in 0 until 14) {
                val ang = a * 2 * PI / 14
                val d = cam.len(10f + 45f * k)
                val p = Offset(center.x + (cos(ang) * d).toFloat(), center.y + (sin(ang) * d).toFloat())
                drawRect((if (a % 2 == 0) c.colors[1] else Color(0xFFFFD60A)).copy(alpha = 1f - k), p, Size(cam.len(3f), cam.len(3f)))
            }
        }
    }
}
