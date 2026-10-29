package com.dez.sports.ui.game.arena

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.dez.sports.model.GameDetail
import com.dez.sports.model.Play
import com.dez.sports.ui.theme.darken
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/*
 * Football. World units are yards: x -10..110 (the away end zone on the left, matching the drive
 * tracker), y 0..53.3 across. The camera follows the ball like a TV broadcast.
 */

private const val MID = 26.65f
private val downRe = Regex("&\\s*(\\d+|Goal)", RegexOption.IGNORE_CASE)

internal class FootballScene(
    play: Play, side: Int, val kind: Kind, val startX: Float, val endX: Float, val dir: Int,
    val passer: Who?, val carrier: Who?, val defender: Who?, val offense: List<Who?>, val defense: List<Who?>,
    val downText: String?, override val points: Int, val good: Boolean, override val headline: String, override val pop: String?,
) : Scene(play, side) {
    enum class Kind { RUN, PASS, INCOMPLETE, SACK, INTERCEPTION, PUNT, KICKOFF, FIELD_GOAL, EXTRA_POINT, PENALTY }
    override val star: Who? get() = when (kind) { Kind.SACK, Kind.INTERCEPTION -> defender ?: passer; Kind.PASS -> carrier ?: passer; Kind.INCOMPLETE -> passer; else -> carrier ?: passer }
    override val durationMs get() = if (points >= 6) 3200 else if (kind == Kind.PENALTY) 1400 else 2500
}

internal fun footballScenes(d: GameDetail, r: Roster): List<Scene> {
    val home = d.game.home.team.id
    return d.drives.flatMap { dr -> dr.plays.map { dr to it } }.mapNotNull { (dr, p) ->
        val f = p.football ?: return@mapNotNull null
        val s0 = f.startYtg ?: return@mapNotNull null
        val s1 = f.endYtg ?: s0
        if (s0 !in 0..100 || listOf("Timeout", "Warning", "End of", "End Period", "Coin Toss").any { p.type?.contains(it, true) == true }) return@mapNotNull null
        val offHome = (f.offenseId ?: dr.teamId) == home
        val side = if (offHome) 1 else 0
        fun x(ytg: Int) = if (offHome) ytg.toFloat() else 100f - ytg
        val dir = if (offHome) -1 else 1
        val type = (p.type ?: "").lowercase(); val text = p.text
        val kind = when {
            type.contains("kickoff") -> FootballScene.Kind.KICKOFF
            type.contains("punt") -> FootballScene.Kind.PUNT
            type.contains("field goal") -> FootballScene.Kind.FIELD_GOAL
            type.contains("extra point") || text.contains("extra point", true) -> FootballScene.Kind.EXTRA_POINT
            type.contains("interception") -> FootballScene.Kind.INTERCEPTION
            type.contains("sack") -> FootballScene.Kind.SACK
            type.contains("incompletion") || text.contains("incomplete", true) -> FootballScene.Kind.INCOMPLETE
            type.contains("pass") -> FootballScene.Kind.PASS
            type.contains("penalty") && !type.contains("rush") && !type.contains("pass") -> FootballScene.Kind.PENALTY
            else -> FootballScene.Kind.RUN
        }
        val named = r.named(text)
        val offNamed = named.filter { it.second.side == side }.map { it.second }
        val defNamed = named.filter { it.second.side != side }.map { it.second }
        val passer = if (kind in setOf(FootballScene.Kind.PASS, FootballScene.Kind.INCOMPLETE, FootballScene.Kind.INTERCEPTION, FootballScene.Kind.SACK)) offNamed.firstOrNull() else null
        val carrier = if (passer != null) offNamed.getOrNull(1) else offNamed.firstOrNull()
        val qb = passer ?: r.table(side, "Passing").firstOrNull()
        val rb = (if (kind == FootballScene.Kind.RUN) carrier else null) ?: r.table(side, "Rushing").firstOrNull { it.id != qb?.id }
        val catchers = (listOfNotNull(carrier?.takeIf { kind == FootballScene.Kind.PASS || kind == FootballScene.Kind.INCOMPLETE }) + r.table(side, "Receiving")).distinctBy { it.id }.filter { it.id != rb?.id }
        // QB, RB, WR, WR, TE, then five linemen we don't have numbers for
        val offense = listOf(qb, rb, catchers.getOrNull(0), catchers.getOrNull(1), catchers.getOrNull(2), null, null, null, null, null, null)
        val defense = (defNamed + r.table(1 - side, "Defens", "Tackles")).distinctBy { it.id }.take(11).let { it + List(11 - it.size) { null } }
        val yards = abs(x(s1) - x(s0)).toInt()
        val gain = if ((x(s1) - x(s0)) * dir >= 0) yards else -yards
        val td = text.contains("touchdown", true) || (p.scoring && (type.contains("touchdown") || type.contains("rush") || type.contains("pass")))
        val good = !text.contains("no good", true) && !text.contains("missed", true) && !text.contains("blocked", true)
        val points = when {
            td -> 6
            kind == FootballScene.Kind.FIELD_GOAL && good -> 3
            kind == FootballScene.Kind.EXTRA_POINT && good -> 1
            else -> 0
        }
        val headline = when (kind) {
            FootballScene.Kind.RUN -> "RUN ${signed(gain)}"
            FootballScene.Kind.PASS -> "PASS ${signed(gain)}"
            FootballScene.Kind.INCOMPLETE -> "INCOMPLETE"
            FootballScene.Kind.SACK -> "SACK ${signed(gain)}"
            FootballScene.Kind.INTERCEPTION -> "INTERCEPTION"
            FootballScene.Kind.PUNT -> "PUNT"
            FootballScene.Kind.KICKOFF -> "KICKOFF"
            FootballScene.Kind.FIELD_GOAL -> if (good) "FIELD GOAL" else "FG MISSED"
            FootballScene.Kind.EXTRA_POINT -> "EXTRA POINT"
            FootballScene.Kind.PENALTY -> "PENALTY"
        }
        val pop = when {
            td -> "TOUCHDOWN!"
            kind == FootballScene.Kind.FIELD_GOAL -> if (good) "IT'S GOOD!" else "NO GOOD"
            kind == FootballScene.Kind.INTERCEPTION -> "PICKED OFF!"
            kind == FootballScene.Kind.SACK -> "SACK!"
            f.turnover -> "TURNOVER!"
            kind == FootballScene.Kind.INCOMPLETE -> "INCOMPLETE"
            kind == FootballScene.Kind.PENALTY -> "FLAG!"
            gain >= 20 -> "BIG PLAY!"
            else -> null
        }
        val kicker = if (kind in setOf(FootballScene.Kind.PUNT, FootballScene.Kind.KICKOFF, FootballScene.Kind.FIELD_GOAL, FootballScene.Kind.EXTRA_POINT)) offNamed.firstOrNull() else null
        FootballScene(p, side, kind, x(s0), x(s1), dir, passer ?: qb, kicker ?: carrier ?: rb.takeIf { kind == FootballScene.Kind.RUN }, defNamed.firstOrNull(), offense, defense,
            f.downText, points, good, headline, pop)
    }
}

private fun signed(n: Int) = if (n >= 0) "+$n" else "$n"

internal object FootballArena : ArenaSport {
    override val bounds = Rect(-11.5f, -1.5f, 111.5f, 54.8f)
    override val spriteH = 4.2f

    override fun focus(s: Scene, t: Float): Float {
        val f = s as FootballScene
        val end = when (f.kind) {
            FootballScene.Kind.FIELD_GOAL, FootballScene.Kind.EXTRA_POINT -> lerp(f.startX, if (f.dir > 0) 110f else -10f, .5f)
            else -> f.endX
        }
        return lerp(f.startX, end, ease(seg(t, .15f, .85f)))
    }

    override fun DrawScope.surface(c: ArenaCtx) {
        val cam = c.cam
        drawRect(Color(0xFF1F5E2A), cam.p(bounds.left, bounds.top), Size(cam.len(bounds.width), cam.len(bounds.height)))
        // mowing stripes
        for (k in 0 until 20) drawRect(if (k % 2 == 0) Color(0xFF2E7D3A) else Color(0xFF2A7335), cam.p(k * 5f, 0f), Size(cam.len(5f), cam.len(53.3f)))
        // end zones
        listOf(0, 1).forEach { s ->
            val x0 = if (s == 0) -10f else 100f
            drawRect(c.colors[s].darken(.1f), cam.p(x0, 0f), Size(cam.len(10f), cam.len(53.3f)))
            val name = (if (s == 0) c.game.away else c.game.home).team.name.uppercase()
            label(c, name, cam.p(x0 + 5f, MID), cam.len(5.5f), Color.White.copy(alpha = .9f), rotate = if (s == 0) -90f else 90f)
        }
        val line = Color.White.copy(alpha = .85f)
        for (yd in 0..100 step 5) drawLine(line, cam.p(yd.toFloat(), 0f), cam.p(yd.toFloat(), 53.3f), cam.len(if (yd % 50 == 0) .35f else .22f))
        for (yd in 1..99) if (yd % 5 != 0) listOf(.7f, 23.6f, 29.7f, 52.6f).forEach { y ->
            drawLine(line.copy(alpha = .6f), cam.p(yd.toFloat(), y), cam.p(yd.toFloat(), y + .7f), cam.len(.15f))
        }
        for (yd in 10..90 step 10) {
            val n = if (yd <= 50) yd else 100 - yd
            label(c, "$n", cam.p(yd.toFloat(), 9f), cam.len(3.4f), line, bold = true)
            label(c, "$n", cam.p(yd.toFloat(), 44.3f), cam.len(3.4f), line, rotate = 180f, bold = true)
        }
        drawRect(line, cam.p(-10f, 0f), Size(cam.len(120f), cam.len(53.3f)), style = Stroke(cam.len(.4f)))
        drawLogo(c.homeLogo, cam.p(50f, MID), cam.len(13f))
        // goalposts seen from above
        listOf(-10f, 110f).forEach { gx ->
            drawLine(Color(0xFFFFD60A), cam.p(gx, MID - 3.1f), cam.p(gx, MID + 3.1f), cam.len(.45f))
            drawCircle(Color(0xFFFFD60A), cam.len(.5f), cam.p(gx, MID))
        }
    }

    override fun DrawScope.scene(s: Scene, t: Float, c: ArenaCtx) {
        val f = s as FootballScene
        val cam = c.cam
        val dir = f.dir.toFloat()
        val los = f.startX
        val px = cam.len(spriteH)
        val off = f.side; val def = 1 - off
        // broadcast lines: line of scrimmage and the first-down marker
        drawLine(Color(0xFF3B82F6).copy(alpha = .9f), cam.p(los, 0f), cam.p(los, 53.3f), cam.len(.45f))
        f.downText?.let { downRe.find(it)?.groupValues?.get(1)?.toIntOrNull() }?.let { dist ->
            val fd = (los + dir * dist).coerceIn(0f, 100f)
            drawLine(Color(0xFFFFD60A), cam.p(fd, 0f), cam.p(fd, 53.3f), cam.len(.45f))
        }
        f.downText?.let { dt ->
            val at = cam.p(los, 1.8f)
            label(c, dt.uppercase(), Offset(at.x, at.y), cam.len(1.7f), Color.White, bold = true)
        }

        val snap = seg(t, 0f, .12f)
        val play = seg(t, .12f, .85f)
        val kick = f.kind in setOf(FootballScene.Kind.PUNT, FootballScene.Kind.KICKOFF, FootballScene.Kind.FIELD_GOAL, FootballScene.Kind.EXTRA_POINT)
        val pass = f.kind in setOf(FootballScene.Kind.PASS, FootballScene.Kind.INCOMPLETE, FootballScene.Kind.INTERCEPTION)

        // formation (yards)
        val qb0 = Offset(los - dir * (if (kick) 7f else 5f), MID)
        val rb0 = Offset(los - dir * 7.5f, MID - 3f)
        val wr = listOf(Offset(los - dir * 1.8f, 8f), Offset(los - dir * 1.8f, 45.3f), Offset(los - dir * 2.5f, MID + 9f))
        val ol = (-2..2).map { Offset(los - dir * 1.8f, MID + it * 2.9f) }
        val dl = listOf(-4.4f, -1.5f, 1.5f, 4.4f).map { Offset(los + dir * 1.8f, MID + it) }
        val lb = listOf(-7f, 0f, 7f).map { Offset(los + dir * 6f, MID + it) }
        val cb = listOf(Offset(los + dir * 6f, 8f), Offset(los + dir * 6f, 45.3f))
        val sf = listOf(Offset(los + dir * 13f, 18f), Offset(los + dir * 13f, 35f))
        val defStart = dl + lb + cb + sf

        val endX = f.endX
        val catchPt = Offset(if (f.kind == FootballScene.Kind.INCOMPLETE) los + dir * 11f else lerp(los, endX, .7f), 18f)
        val finalPt = Offset(endX, if (pass) 20f else MID - 3f)
        // who carries the ball over time
        val qbPos = when {
            f.kind == FootballScene.Kind.SACK -> lerp(qb0, Offset(endX, MID), ease(play))
            pass -> lerp(qb0, Offset(qb0.x - dir * 3f, MID), ease(seg(t, .12f, .35f)))
            else -> qb0
        }
        val receiverPos = when (f.kind) {
            FootballScene.Kind.PASS -> if (t < .6f) lerp(wr[0], catchPt, ease(seg(t, .12f, .6f))) else lerp(catchPt, finalPt, ease(seg(t, .6f, .9f)))
            FootballScene.Kind.INCOMPLETE, FootballScene.Kind.INTERCEPTION -> lerp(wr[0], catchPt, ease(seg(t, .12f, .6f)))
            else -> wr[0]
        }
        val rbPos = if (f.kind == FootballScene.Kind.RUN) (if (t < .3f) lerp(rb0, qb0, ease(seg(t, .12f, .3f))) else lerp(qb0, finalPt, ease(seg(t, .3f, .88f)))) else lerp(rb0, Offset(rb0.x - dir, rb0.y), play)
        val ballCarrierPos = when (f.kind) {
            FootballScene.Kind.RUN -> rbPos
            FootballScene.Kind.PASS -> receiverPos
            else -> qbPos
        }
        val pursuit = ease(seg(t, .3f, .95f))

        data class Spr(val at: Offset, val who: Who?, val side: Int, val face: Boolean, val star: Boolean, val run: Boolean)
        val sprites = mutableListOf<Spr>()
        val faceO = dir > 0
        if (!kick || f.kind == FootballScene.Kind.FIELD_GOAL || f.kind == FootballScene.Kind.EXTRA_POINT) {
            ol.forEachIndexed { i, o -> sprites += Spr(Offset(o.x + dir * .8f * play, o.y), f.offense.getOrNull(5 + i), off, faceO, false, false) }
            sprites += Spr(qbPos, f.offense[0], off, faceO, f.kind == FootballScene.Kind.SACK || f.kind == FootballScene.Kind.INCOMPLETE, pass && t > .12f)
            sprites += Spr(rbPos, f.offense[1], off, faceO, f.kind == FootballScene.Kind.RUN, t > .12f)
            sprites += Spr(receiverPos, f.offense[2], off, faceO, f.kind == FootballScene.Kind.PASS, t > .12f)
            sprites += Spr(Offset(lerp(wr[1].x, wr[1].x + dir * 12f, ease(play)), wr[1].y), f.offense[3], off, faceO, false, t > .12f)
            sprites += Spr(Offset(lerp(wr[2].x, wr[2].x + dir * 6f, ease(play)), wr[2].y - 4f * play), f.offense[4], off, faceO, false, t > .12f)
            defStart.forEachIndexed { i, o ->
                val chase = if (i == 0 && f.kind == FootballScene.Kind.SACK) qbPos else ballCarrierPos
                val k = if (i < 4) pursuit * .85f else pursuit * (.45f + .05f * i)
                val star = i == 0 && f.defender != null && f.kind != FootballScene.Kind.INTERCEPTION
                sprites += Spr(lerp(o, chase, k.coerceAtMost(.92f)), (if (i == 0) f.defender else null) ?: f.defense[i], def, !faceO, star, t > .12f)
            }
        } else {
            // punts/kickoffs: the kicker, a coverage line, and a returner deep
            val kicker = Offset(los - dir * 10f, MID)
            sprites += Spr(lerp(kicker, Offset(los, MID), ease(seg(t, 0f, .2f))), f.carrier, off, faceO, true, t < .2f)
            for (i in 0..5) sprites += Spr(Offset(lerp(los, endX, pursuit * .9f), 6f + i * 8.2f), null, off, faceO, false, t > .2f)
            sprites += Spr(Offset(endX + dir * 2f, MID), f.defense[0], def, !faceO, false, false)
        }
        sprites.sortedBy { it.at.y }.forEach { sp ->
            drawPlayer(cam.p(sp.at), px, c.kits[sp.side], c.kind, sp.who?.number, if (sp.run) Pose.RUN else Pose.CROUCH.takeIf { t < .12f } ?: Pose.STAND,
                sp.face, c.frame, 0f, if (sp.star) c.colors[sp.side] else null)
        }

        // the ball
        val ballAt: Offset; var lift = 0f
        when {
            f.kind == FootballScene.Kind.FIELD_GOAL || f.kind == FootballScene.Kind.EXTRA_POINT -> {
                val holder = Offset(los - dir * 7f, MID)
                val k = seg(t, .2f, .8f)
                val target = Offset(if (dir > 0) 110f else -10f, if (f.good) MID else MID + 5f)
                ballAt = if (t < .2f) lerp(Offset(los, MID), holder, seg(t, 0f, .12f)) else lerp(holder, target, ease(k))
                lift = sin(PI * k).toFloat() * 10f
            }
            kick -> {
                val k = seg(t, .2f, .85f)
                ballAt = if (t < .2f) Offset(los - dir * 10f, MID) else lerp(Offset(los - dir * 9f, MID), Offset(endX, MID), ease(k))
                lift = sin(PI * k).toFloat() * 14f
            }
            pass && t >= .35f && t < .62f -> {
                val k = seg(t, .35f, .62f)
                val target = if (f.kind == FootballScene.Kind.INTERCEPTION) Offset(catchPt.x + dir * 2f, catchPt.y + 1f) else catchPt
                ballAt = lerp(qbPos, target, k); lift = sin(PI * k).toFloat() * 6f
            }
            f.kind == FootballScene.Kind.INCOMPLETE && t >= .62f -> { ballAt = Offset(catchPt.x + dir * 2f, catchPt.y + 2f); lift = (1f - seg(t, .62f, .75f)) * 1f }
            f.kind == FootballScene.Kind.INTERCEPTION && t >= .62f -> ballAt = lerp(Offset(catchPt.x + dir * 2f, catchPt.y + 1f), Offset(catchPt.x - dir * 15f, catchPt.y + 4f), ease(seg(t, .62f, 1f)))
            t < .12f -> ballAt = lerp(Offset(los, MID), qb0, snap)
            else -> ballAt = ballCarrierPos
        }
        football(cam.p(ballAt), cam.len(.75f), cam.len(lift + 1.2f), dir > 0)
        if (f.kind == FootballScene.Kind.PENALTY) {
            val k = seg(t, .1f, .6f)
            val at = cam.p(lerp(Offset(los + dir * 3f, 50f), Offset(los + dir * 1f, MID + 4f), ease(k)))
            drawRect(Color(0xFFFFD60A), Offset(at.x, at.y - cam.len(sin(PI * k).toFloat() * 4f)), Size(cam.len(1.2f), cam.len(1.2f)))
        }
        f.star?.let { w -> sprites.firstOrNull { it.who?.id == w.id && it.who != null }?.let { nameTag(c, w, cam.p(it.at), px, c.colors[w.side]) } }
    }

    private fun DrawScope.football(at: Offset, r: Float, lift: Float, right: Boolean) {
        drawOval(Color.Black.copy(alpha = .3f), Offset(at.x - r, at.y - r * .4f), Size(r * 2, r * .8f))
        val p = Offset(at.x, at.y - lift)
        drawOval(Color(0xFF8B4513), Offset(p.x - r * 1.3f, p.y - r * .8f), Size(r * 2.6f, r * 1.6f))
        drawLine(Color.White, Offset(p.x - r * .5f, p.y), Offset(p.x + r * .5f, p.y), r * .25f)
    }
}
