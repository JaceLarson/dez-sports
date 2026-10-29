package com.dez.sports.ui.game.arena

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.dez.sports.model.GameDetail
import com.dez.sports.model.Play
import com.dez.sports.ui.theme.darken
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Basketball. World units are feet on a 94 x 50 court, x along the length. ESPN shot coordinates are
 * half-court: x 0..50 across, y = feet from the rim toward half court. Home attacks the right basket.
 */

private const val RIM_IN = 5.25f
private val offenseSpots = listOf(Offset(3f, 2f), Offset(47f, 2f), Offset(8f, 17f), Offset(42f, 17f), Offset(25f, 25f), Offset(15f, 8f), Offset(35f, 8f))

internal class HoopScene(
    play: Play, side: Int, val kind: Kind, val spot: Offset, val made: Boolean, override val points: Int,
    val shooter: Who?, val helper: Who?, val helperRole: Role, val mates: List<Who>, val defense: List<Who>,
    override val headline: String, override val pop: String?,
) : Scene(play, side) {
    enum class Kind { SHOT, FREE_THROW, REBOUND, STEAL }
    enum class Role { NONE, ASSIST, BLOCK }
    override val star get() = shooter
    override val durationMs get() = when (kind) { Kind.SHOT -> if (made) 2200 else 1900; Kind.FREE_THROW -> 1500; Kind.REBOUND -> 1100; Kind.STEAL -> 1500 }
    /** Which basket the play is at: the shooter's team attacks right when home. Rebounds/steals happen at the other team's basket when defensive. */
    val attackRight get() = (if (kind == Kind.REBOUND && play.text.contains("defensive", true) || kind == Kind.STEAL) 1 - side else side) == 1
}

/** ESPN half-court coordinate → court feet, for the team attacking right/left. */
private fun court(right: Boolean, o: Offset) = if (right) Offset(94f - RIM_IN - o.y, 50f - o.x) else Offset(RIM_IN + o.y, o.x)
private fun rim(right: Boolean) = Offset(if (right) 94f - RIM_IN else RIM_IN, 25f)

internal fun hoopsScenes(d: GameDetail, r: Roster): List<Scene> {
    val home = d.game.home.team.id
    return d.plays.mapNotNull { p ->
        val side = when (p.teamId) { home -> 1; null -> return@mapNotNull null; else -> 0 }
        val t = p.text.lowercase()
        val first = r.id(p.athletes.getOrNull(0))
        val second = r.id(p.athletes.getOrNull(1))
        fun lineup(off: Int, star: Who?, extra: Who?) =
            r.starters(off).filter { it.id != star?.id }.take(4) to (listOfNotNull(extra?.takeIf { it.side != off }) + r.starters(1 - off)).distinctBy { it.id }.take(5)
        when {
            p.shot != null -> {
                val three = t.contains("three point")
                val role = when { t.contains("assist") -> HoopScene.Role.ASSIST; t.contains("block") -> HoopScene.Role.BLOCK; else -> HoopScene.Role.NONE }
                val (mates, def) = lineup(side, first, second.takeIf { role == HoopScene.Role.BLOCK })
                val made = p.shot.made
                val kindWord = when { t.contains("dunk") -> "DUNK"; t.contains("layup") -> "LAYUP"; t.contains("hook") -> "HOOK"; three -> "3-POINTER"; t.contains("tip") -> "TIP-IN"; else -> "JUMPER" }
                val pop = when {
                    role == HoopScene.Role.BLOCK -> "REJECTED!"
                    !made -> null
                    t.contains("dunk") -> "SLAM!"
                    three -> "SPLASH!"
                    else -> "+2"
                }
                HoopScene(p, side, HoopScene.Kind.SHOT, Offset(p.shot.x.coerceIn(1f, 49f), p.shot.y.coerceIn(-3f, 40f)), made, if (made) (if (three) 3 else 2) else 0,
                    first, second.takeIf { role != HoopScene.Role.NONE }, role, mates, def, if (made) kindWord else "MISSED $kindWord", pop)
            }
            t.contains("free throw") -> {
                val made = !t.contains("miss")
                val (mates, def) = lineup(side, first, null)
                HoopScene(p, side, HoopScene.Kind.FREE_THROW, Offset(25f, 14f), made, if (made) 1 else 0, first, null, HoopScene.Role.NONE, mates, def,
                    if (made) "FREE THROW" else "MISSED FT", if (made) "+1" else null)
            }
            t.contains("rebound") && first != null -> {
                val (mates, def) = lineup(side, first, null)
                HoopScene(p, side, HoopScene.Kind.REBOUND, Offset(25f + (first.hashCode() % 7), 3f), false, 0, first, null, HoopScene.Role.NONE, mates, def,
                    if (t.contains("offensive")) "OFF. REBOUND" else "REBOUND", null)
            }
            t.contains("steal") && second != null -> {
                // ESPN credits the turnover to the team that lost it; the stealer is the second participant.
                val thief = second
                val (mates, def) = lineup(thief.side, thief, first)
                HoopScene(p, thief.side, HoopScene.Kind.STEAL, Offset(30f, 20f), false, 0, thief, first, HoopScene.Role.NONE, mates, def, "STEAL", "PICKED!")
            }
            else -> null
        }
    }
}

internal object HoopsArena : ArenaSport {
    override val bounds = Rect(-5f, -4f, 99f, 54f)
    override val spriteH = 7.6f

    override fun focus(s: Scene, t: Float): Float {
        val h = s as HoopScene
        val r = rim(h.attackRight)
        return if (h.kind == HoopScene.Kind.STEAL) lerp(court(h.attackRight, h.spot).x, rim(!h.attackRight).x, ease(seg(t, .3f, 1f)) * .6f)
        else lerp(court(h.attackRight, h.spot).x, r.x, .35f)
    }

    override fun DrawScope.surface(c: ArenaCtx) {
        val cam = c.cam
        val home = c.colors[1]
        drawRect(home.darken(.55f), cam.p(bounds.left, bounds.top), Size(cam.len(bounds.width), cam.len(bounds.height)))
        val tl = cam.p(0f, 0f)
        drawRect(Color(0xFFC8955E), tl, Size(cam.len(94f), cam.len(50f)))
        // planks
        var y = 0f; var k = 0
        while (y < 50f) {
            drawRect(Color(if (k % 3 == 0) 0x10000000 else if (k % 3 == 1) 0x0AFFFFFF else 0x06000000), cam.p(0f, y), Size(cam.len(94f), cam.len(1.25f)))
            y += 1.25f; k++
        }
        val line = Color.White.copy(alpha = .9f); val sw = cam.len(.22f)
        // painted keys, the arcs and lines
        listOf(false, true).forEach { right ->
            val keyX = if (right) 94f - 19f else 0f
            drawRect(home.copy(alpha = .85f), cam.p(keyX, 17f), Size(cam.len(19f), cam.len(16f)))
            drawRect(line, cam.p(keyX, 17f), Size(cam.len(19f), cam.len(16f)), style = Stroke(sw))
            val ftc = cam.p(if (right) 94f - 19f else 19f, 25f)
            drawCircle(line, cam.len(6f), ftc, style = Stroke(sw))
            val rc = cam.p(rim(right))
            val r3 = cam.len(23.75f)
            val sweep = Math.toDegrees(kotlin.math.asin((22f / 23.75f).toDouble())).toFloat()
            drawArc(line, if (right) 180f - sweep else -sweep, 2 * sweep, false, Offset(rc.x - r3, rc.y - r3), Size(2 * r3, 2 * r3), style = Stroke(sw))
            val cornerX = if (right) 94f - 14f else 14f
            drawLine(line, cam.p(if (right) 94f else 0f, 3f), cam.p(cornerX, 3f), sw)
            drawLine(line, cam.p(if (right) 94f else 0f, 47f), cam.p(cornerX, 47f), sw)
            // backboard, rim and net
            val bx = if (right) 94f - 4f else 4f
            drawLine(Color.White, cam.p(bx, 22f), cam.p(bx, 28f), cam.len(.45f))
            drawCircle(Color.White.copy(alpha = .8f), cam.len(.95f), rc)
            drawCircle(Color(0xFFFF6A1A), cam.len(.85f), rc, style = Stroke(cam.len(.28f)))
        }
        drawRect(line, tl, Size(cam.len(94f), cam.len(50f)), style = Stroke(sw * 1.3f))
        drawLine(line, cam.p(47f, 0f), cam.p(47f, 50f), sw)
        drawCircle(home, cam.len(6f), cam.p(47f, 25f))
        drawCircle(line, cam.len(6f), cam.p(47f, 25f), style = Stroke(sw))
        drawLogo(c.homeLogo, cam.p(47f, 25f), cam.len(10.5f))
        // team name on both baselines' aprons
        label(c, c.game.home.team.name.uppercase(), cam.p(-2.5f, 25f), cam.len(2.6f), Color.White.copy(alpha = .85f), rotate = -90f)
        label(c, c.game.home.team.name.uppercase(), cam.p(96.5f, 25f), cam.len(2.6f), Color.White.copy(alpha = .85f), rotate = 90f)
    }

    override fun DrawScope.scene(s: Scene, t: Float, c: ArenaCtx) {
        val h = s as HoopScene
        val cam = c.cam
        val right = h.attackRight
        val rimW = rim(right)
        val px = cam.len(spriteH)
        val off = h.side; val def = 1 - off
        val ball = Color(0xFFFF8A2B)
        fun spot(o: Offset) = court(right, o)

        // where everyone stands (court feet)
        val shooterAt = when (h.kind) {
            HoopScene.Kind.SHOT -> spot(h.spot)
            HoopScene.Kind.FREE_THROW -> spot(Offset(25f, 14f))
            HoopScene.Kind.REBOUND -> spot(Offset(h.spot.x, 4f + (h.spot.x - 25f) * .1f))
            HoopScene.Kind.STEAL -> spot(h.spot)
        }
        val mateSpots = if (h.kind == HoopScene.Kind.FREE_THROW) listOf(Offset(16f, 5.5f), Offset(34f, 5.5f), Offset(10f, 25f), Offset(40f, 25f))
            else offenseSpots.filter { hypot(it.x - h.spot.x, it.y - h.spot.y) > 9f }.take(4)
        val mates = h.mates.zip(mateSpots.map(::spot))
        val defSpots = if (h.kind == HoopScene.Kind.FREE_THROW) listOf(Offset(16f, 1f), Offset(34f, 1f), Offset(16f, 10f), Offset(34f, 10f), Offset(25f, 26f)).map(::spot)
            else (listOf(h.spot) + mateSpots).map { o -> spot(Offset(lerp(o.x, 25f, .25f), lerp(o.y, 0f, .25f))) }
        val defenders = h.defense.zip(defSpots)
        val drift = sin(c.clock * 2.2f) * .35f

        // closeout: nearest defender flies at the shooter
        val close = ease(seg(t, .1f, .4f))
        val blocker = if (h.helperRole == HoopScene.Role.BLOCK) h.helper else null

        // draw back-to-front by y so sprites overlap correctly
        data class Spr(val at: Offset, val who: Who?, val side: Int, val pose: Pose, val lift: Float, val face: Boolean, val star: Boolean)
        val sprites = mutableListOf<Spr>()
        val shooterJump = if (h.kind == HoopScene.Kind.SHOT || h.kind == HoopScene.Kind.REBOUND) sin(PI * seg(t, .25f, .55f)).toFloat() else 0f
        var shooterPos = shooterAt
        if (h.kind == HoopScene.Kind.STEAL) shooterPos = lerp(shooterAt, rim(!right), ease(seg(t, .35f, 1f)) * .55f)
        sprites += Spr(shooterPos, h.shooter, off, if (shooterJump > .05f) Pose.SHOOT else if (h.kind == HoopScene.Kind.STEAL && t > .35f) Pose.RUN else Pose.STAND,
            shooterJump * 2.2f, if (h.kind == HoopScene.Kind.STEAL) !right else right, true)
        mates.forEach { (w, at) -> sprites += Spr(Offset(at.x + drift * (if (right) 1 else -1), at.y), w, off, Pose.STAND, 0f, right, false) }
        defenders.forEachIndexed { i, (w, at) ->
            var p = at
            if (i == 0) p = lerp(at, Offset(lerp(shooterAt.x, rimW.x, .18f), shooterAt.y), close * .75f)
            val isBlocker = blocker != null && w.id == blocker.id
            if (isBlocker) p = lerp(at, Offset(lerp(shooterAt.x, rimW.x, .12f), shooterAt.y + 1.2f), ease(seg(t, .1f, .4f)))
            val jump = if (isBlocker) sin(PI * seg(t, .3f, .6f)).toFloat() * 2.6f else 0f
            sprites += Spr(Offset(p.x - drift, p.y), w, def, if (jump > .1f) Pose.SHOOT else Pose.STAND, jump, !right, false)
        }
        sprites.sortedBy { it.at.y }.forEach { sp ->
            drawPlayer(cam.p(sp.at), px, c.kits[sp.side], c.kind, sp.who?.number, sp.pose, sp.face, c.frame, cam.len(sp.lift), if (sp.star) c.colors[sp.side] else null)
        }

        // the ball
        val hand = Offset(shooterPos.x, shooterPos.y)
        when (h.kind) {
            HoopScene.Kind.SHOT, HoopScene.Kind.FREE_THROW -> {
                val release = if (h.kind == HoopScene.Kind.FREE_THROW) .2f else .4f
                val arrive = if (h.kind == HoopScene.Kind.FREE_THROW) .65f else .76f
                if (t < release) {
                    val passT = if (h.helperRole == HoopScene.Role.ASSIST) seg(t, 0f, .25f) else 1f
                    val from = mates.firstOrNull { it.first.id == h.helper?.id }?.second ?: hand
                    val at = if (passT < 1f) lerp(from, hand, passT) else Offset(hand.x, hand.y + abs01(sin(c.clock * 14f)) * .8f)
                    ball(cam.p(at), cam.len(.85f), ball, lift = if (passT < 1f) cam.len(3.5f) else cam.len(1.5f + shooterJump * 4f))
                } else {
                    val f = seg(t, release, arrive)
                    val blocked = h.helperRole == HoopScene.Role.BLOCK && f > .35f
                    val target = when {
                        blocked -> Offset(hand.x + (if (right) -6f else 6f), hand.y + 7f)
                        !h.made && f > .9f -> Offset(rimW.x + (if (right) -3.5f else 3.5f), rimW.y + 3f)
                        else -> rimW
                    }
                    val pos = lerp(hand, target, ease(f))
                    val arc = sin(PI * f).toFloat() * (if (blocked) 4f else 9f)
                    ball(cam.p(pos), cam.len(.85f + arc * .05f), ball, lift = cam.len(arc + 2f))
                    if (t >= arrive && h.made) {
                        val k = seg(t, arrive, 1f)
                        for (n in 1..3) drawCircle(c.colors[off].copy(alpha = (1f - k) * .7f / n), cam.len(1.2f + n * 1.5f * k + k * 2f), cam.p(rimW), style = Stroke(cam.len(.35f)))
                        drawCircle(Color.White.copy(alpha = (1f - k)), cam.len(1.1f), cam.p(rimW), style = Stroke(cam.len(.25f)))
                    }
                }
            }
            HoopScene.Kind.REBOUND -> {
                val f = ease(seg(t, 0f, .45f))
                ball(cam.p(lerp(rimW, hand, f)), cam.len(.85f), ball, lift = cam.len(4f + shooterJump * 3f))
            }
            HoopScene.Kind.STEAL -> {
                val loser = defenders.firstOrNull { it.first.id == h.helper?.id }?.second ?: Offset(hand.x - 3f, hand.y)
                val at = if (t < .3f) lerp(loser, hand, ease(seg(t, 0f, .3f))) else Offset(shooterPos.x + (if (right) -1f else 1f), shooterPos.y + abs01(sin(c.clock * 14f)) * .6f)
                ball(cam.p(at), cam.len(.85f), ball, lift = cam.len(1.5f))
            }
        }
        h.shooter?.let { nameTag(c, it, cam.p(shooterPos), px + cam.len(shooterJump * 2.2f), c.colors[off]) }
    }

    private fun DrawScope.ball(at: Offset, r: Float, color: Color, lift: Float) {
        drawCircle(Color.Black.copy(alpha = .3f), r * .9f, Offset(at.x + lift * .15f, at.y))
        val p = Offset(at.x, at.y - lift)
        drawCircle(color, r, p)
        drawLine(Color.Black.copy(alpha = .5f), Offset(p.x - r, p.y), Offset(p.x + r, p.y), r * .18f)
        drawLine(Color.Black.copy(alpha = .5f), Offset(p.x, p.y - r), Offset(p.x, p.y + r), r * .18f)
    }
}

internal fun abs01(v: Float) = kotlin.math.abs(v)
