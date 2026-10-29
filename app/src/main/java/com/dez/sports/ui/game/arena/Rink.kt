package com.dez.sports.ui.game.arena

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.dez.sports.model.GameDetail
import com.dez.sports.model.Play
import com.dez.sports.model.RinkKind
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Hockey. World units are feet: x -100..100 between the end boards, y 0..85 down the screen
 * (ESPN's y is flipped). Home attacks the right net, like the shot map.
 */

private const val NET = 89f

internal class HockeyScene(
    play: Play, side: Int, val kind: RinkKind, val at: Offset, val shooter: Who?, val goalie: Who?, val blocker: Who?,
    val mates: List<Who>, val defense: List<Who>, override val headline: String, override val pop: String?,
) : Scene(play, side) {
    override val star: Who? get() = shooter
    override val points get() = if (kind == RinkKind.GOAL) 1 else 0
    override val durationMs get() = if (kind == RinkKind.GOAL) 2800 else 1700
    override val popAt get() = .62f
    val right get() = side == 1
}

internal fun hockeyScenes(d: GameDetail, r: Roster): List<Scene> {
    val home = d.game.home.team.id
    return d.plays.mapNotNull { p ->
        val ev = p.rink ?: return@mapNotNull null
        fun role(vararg names: String) = p.athletes.indices.firstOrNull { p.roles.getOrNull(it) in names }?.let { r.id(p.athletes[it]) }
        val shooter = role("shooter", "scorer", "winner") ?: r.id(p.athletes.getOrNull(0))
        // ESPN files blocked shots under the blocking team, so trust the shooter's own team when we know it
        val side = shooter?.side ?: if (p.teamId == home) 1 else 0
        val right = side == 1
        val x = if (right) abs(ev.x) else -abs(ev.x)
        val y = if (right == (ev.x >= 0)) ev.y else -ev.y
        val goalie = role("goalie") ?: r.table(1 - side, "Goal").firstOrNull()
        val blocker = role("blocker")
        val assists = p.athletes.indices.filter { p.roles.getOrNull(it) == "assister" }.mapNotNull { r.id(p.athletes[it]) }
        val skaters = { s: Int -> r.all(s).filter { it.pos != "G" && it.id != goalie?.id && !r.table(s, "Goal").any { g -> g.id == it.id } } }
        val mates = (assists + skaters(side)).filter { it.id != shooter?.id }.distinctBy { it.id }.take(4)
        val defense = (listOfNotNull(blocker) + skaters(1 - side)).distinctBy { it.id }.take(5)
        val (headline, pop) = when (ev.kind) {
            RinkKind.GOAL -> "GOAL" to "GOAL!"
            RinkKind.SHOT -> "SHOT ON GOAL" to "SAVE!"
            RinkKind.MISS -> "MISSED SHOT" to "WIDE"
            RinkKind.BLOCK -> "BLOCKED SHOT" to "BLOCKED"
        }
        HockeyScene(p, side, ev.kind, Offset(x, 42.5f - y), shooter, goalie, blocker, mates, defense, headline, pop)
    }
}

internal object HockeyArena : ArenaSport {
    override val bounds = Rect(-102f, -2f, 102f, 87f)
    override val spriteH = 10.5f

    override fun focus(s: Scene, t: Float): Float {
        val h = s as HockeyScene
        return lerp(h.at.x, if (h.right) NET else -NET, .35f)
    }

    override fun DrawScope.surface(c: ArenaCtx) {
        val cam = c.cam
        val tl = cam.p(-100f, 0f); val sz = Size(cam.len(200f), cam.len(85f))
        val corner = CornerRadius(cam.len(28f))
        drawRoundRect(Color(0xFFDDE7EF), tl, sz, corner)
        // faint skate scratches
        for (k in 0 until 40) {
            val y = (k * 37 % 85).toFloat(); val x = (k * 53 % 190 - 95).toFloat()
            drawLine(Color(0x14000000), cam.p(x, y), cam.p(x + 9f, y + 1.5f), cam.len(.3f))
        }
        val red = Color(0xFFD7262E); val blue = Color(0xFF1E5BC6)
        drawLine(red, cam.p(0f, 0f), cam.p(0f, 85f), cam.len(1f))
        drawLine(blue, cam.p(-25f, 0f), cam.p(-25f, 85f), cam.len(1f))
        drawLine(blue, cam.p(25f, 0f), cam.p(25f, 85f), cam.len(1f))
        drawCircle(blue, cam.len(15f), cam.p(0f, 42.5f), style = Stroke(cam.len(.5f)))
        drawLogo(c.homeLogo, cam.p(0f, 42.5f), cam.len(24f), alpha = .9f)
        listOf(-1f, 1f).forEach { s ->
            val gx = s * NET
            drawLine(red, cam.p(gx, 4f), cam.p(gx, 81f), cam.len(.4f))
            // crease and net
            val cc = cam.p(gx, 42.5f)
            drawArc(blue.copy(alpha = .35f), if (s < 0) -90f else 90f, 180f, true, Offset(cc.x - cam.len(6f), cc.y - cam.len(6f)), Size(cam.len(12f), cam.len(12f)))
            val netX = if (s < 0) gx - 4f else gx
            drawRect(Color.White, cam.p(netX, 39.5f), Size(cam.len(4f), cam.len(6f)))
            drawRect(red, cam.p(netX, 39.5f), Size(cam.len(4f), cam.len(6f)), style = Stroke(cam.len(.5f)))
            listOf(20.5f, 64.5f).forEach { y ->
                drawCircle(red, cam.len(15f), cam.p(s * 69f, y), style = Stroke(cam.len(.35f)))
                drawCircle(red, cam.len(1f), cam.p(s * 69f, y))
            }
            label(c, c.game.home.team.name.uppercase(), cam.p(s * 95f, 42.5f), cam.len(3.2f), c.colors[1].copy(alpha = .5f), rotate = if (s < 0) -90f else 90f)
        }
        drawRoundRect(Color(0xFFF7F7F7), tl, sz, corner, style = Stroke(cam.len(1.2f)))
        drawRoundRect(Color(0xFFFFD60A).copy(alpha = .8f), tl, sz, corner, style = Stroke(cam.len(.4f)))
    }

    override fun DrawScope.scene(s: Scene, t: Float, c: ArenaCtx) {
        val h = s as HockeyScene
        val cam = c.cam
        val sgn = if (h.right) 1f else -1f
        val net = Offset(sgn * NET, 42.5f)
        val px = cam.len(spriteH)
        val off = h.side; val def = 1 - off
        val shotT = seg(t, .3f, .62f)
        val wind = if (t < .3f) seg(t, .05f, .3f) else 1f - seg(t, .3f, .38f)

        val spots = listOf(Offset(45f, 18f), Offset(45f, 67f), Offset(70f, 20f), Offset(70f, 65f), Offset(80f, 33f), Offset(80f, 52f))
            .map { Offset(sgn * it.x, it.y) }.filter { hypot(it.x - h.at.x, it.y - h.at.y) > 15f }.take(4)
        val glide = sin(c.clock * 1.6f) * 1.2f
        val puckEnd = when (h.kind) {
            RinkKind.GOAL -> Offset(net.x + sgn * 2f, net.y + (h.at.y - 42.5f) * .04f)
            RinkKind.SHOT -> Offset(net.x - sgn * 3f, lerp(42.5f, h.at.y, .08f))
            RinkKind.MISS -> Offset(net.x + sgn * 6f, if (h.at.y < 42.5f) 36f else 49f)
            RinkKind.BLOCK -> lerp(h.at, net, .3f)
        }
        val puck = if (t < .3f) Offset(h.at.x + sgn * 1.5f, h.at.y + 1f) else lerp(Offset(h.at.x + sgn * 1.5f, h.at.y + 1f), puckEnd, ease(shotT))

        data class Spr(val at: Offset, val who: Who?, val side: Int, val face: Boolean, val star: Boolean, val stick: Float, val goalie: Boolean = false)
        val sprites = mutableListOf<Spr>()
        sprites += Spr(h.at, h.shooter, off, h.right, true, wind)
        h.mates.zip(spots).forEach { (w, p) -> sprites += Spr(Offset(p.x + glide, p.y), w, off, h.right, false, 0f) }
        val defSpots = (listOf(lerp(h.at, net, .35f)) + spots).map { lerp(it, net, .3f) }
        h.defense.zip(defSpots).forEachIndexed { i, (w, p) ->
            val at = if (i == 0 && h.kind == RinkKind.BLOCK) lerp(h.at, net, .3f) else Offset(p.x - glide, p.y)
            sprites += Spr(at, w, def, !h.right, h.kind == RinkKind.BLOCK && i == 0, 0f)
        }
        // goalie squares up to the puck, lunges on a save
        val gy = (42.5f + (puck.y - 42.5f) * .35f).coerceIn(38f, 47f)
        val goalieAt = Offset(net.x - sgn * (3.5f + if (h.kind == RinkKind.SHOT) 1.5f * ease(shotT) else 0f), gy)
        sprites += Spr(goalieAt, h.goalie, def, !h.right, false, 0f, goalie = true)

        sprites.sortedBy { it.at.y }.forEach { sp ->
            val feet = cam.p(sp.at)
            drawPlayer(feet, if (sp.goalie) px * 1.08f else px, c.kits[sp.side], c.kind, sp.who?.number, if (sp.goalie) Pose.CROUCH else Pose.RUN, sp.face, c.frame,
                glow = if (sp.star) c.colors[sp.side] else null)
            drawStick(feet, px, sp.face, sp.stick)
        }
        // puck + streak
        val pp = cam.p(puck)
        if (t >= .3f && shotT < 1f) drawLine(Color.Black.copy(alpha = .25f), cam.p(h.at), pp, cam.len(.7f))
        drawOval(Color(0xFF111111), Offset(pp.x - cam.len(1f), pp.y - cam.len(.55f)), Size(cam.len(2f), cam.len(1.1f)))

        if (h.kind == RinkKind.GOAL && t >= .62f) {
            // goal lamp
            val k = seg(t, .62f, 1f)
            val pulse = .5f + .5f * sin(c.clock * 18f)
            val lamp = cam.p(net.x + sgn * 6f, net.y)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFF2D2D).copy(alpha = .75f * pulse * (1f - k * .4f)), Color.Transparent), lamp, cam.len(26f)), cam.len(26f), lamp)
            drawCircle(Color(0xFFFF2D2D), cam.len(1.6f), lamp)
        }
        h.shooter?.let { nameTag(c, it, cam.p(h.at), px, c.colors[off]) }
    }
}
