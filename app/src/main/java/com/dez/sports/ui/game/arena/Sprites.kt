package com.dez.sports.ui.game.arena

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import com.dez.sports.model.SportKind

/*
 * 8-bit players. Each sprite is a 10x16 pixel template (facing right) whose letters are
 * swapped for the team's kit colours; the jersey number is stamped on the chest in a 3x5 pixel font.
 */

internal data class Kit(val jersey: Color, val trim: Color, val pants: Color, val number: Color) {
    companion object {
        fun of(jersey: Color, trim: Color, sport: SportKind): Kit {
            val num = if (jersey.luminance() > .55f) Color(0xFF111111) else Color.White
            val pants = when (sport) {
                SportKind.BASKETBALL -> jersey.copy(red = jersey.red * .8f, green = jersey.green * .8f, blue = jersey.blue * .8f)
                SportKind.FOOTBALL -> if (trim.luminance() > .25f) trim else Color(0xFFE8E8E8)
                SportKind.HOCKEY -> Color(0xFF1C1C1E)
                else -> Color(0xFFE9E9EC)
            }
            return Kit(jersey, trim, pants, num)
        }
        val Ref = Kit(Color(0xFF222222), Color.White, Color(0xFF333333), Color.White)
    }
}

internal enum class Pose { STAND, RUN, SHOOT, CROUCH }

private val SKIN = Color(0xFFD9A066)
private val HAIR = Color(0xFF2A1B10)
private val SHOE = Color(0xFF151515)
private val MASK = Color(0xFFB8B8B8)
private val DARK = Color(0xFF1C1C1E)

private val hoops = listOf(
    "...hhhh...", "...ssss...", "...ssss...", "....ss....", ".jjjjjjjj.", "sjjjjjjjjs", "sjjjjjjjjs", "sjjjjjjjjs",
    "sjjjjjjjjs", ".jjjjjjjj.", ".aaaaaaaa.", "..pppppp..", "..pppppp..", "..ss..ss..", "..ss..ss..", ".kkk..kkk.",
)
private val hockey = listOf(
    "..jjjjj...", "..jjjjjj..", "...ssss...", "...ssss...", ".jjjjjjjj.", "jjjjjjjjjj", "jjjjjjjjjj", "jjjjjjjjjj",
    "ajjjjjjjja", ".jjjjjjjj.", ".aaaaaaaa.", "..dddddd..", "..dddddd..", "..jj..jj..", "..aa..aa..", ".www..www.",
)
private val football = listOf(
    "...jjjj...", "..jjajjj..", "..jjjjsgg.", "..jjjjsg..", ".jjjjjjjj.", "jjjjjjjjjj", "sjjjjjjjjs", "sjjjjjjjjs",
    "sjjjjjjjjs", ".jjjjjjjj.", ".jjjjjjjj.", "..pppppp..", "..pppppp..", "..jj..jj..", "..jj..jj..", ".kkk..kkk.",
)
private val baseball = listOf(
    "...jjjj...", "...jjjjjj.", "...ssss...", "...ssss...", ".jjjjjjjj.", "sjjjjjjjjs", "sjjjjjjjjs", "sjjjjjjjjs",
    "sjjjjjjjjs", ".jjjjjjjj.", ".aaaaaaaa.", "..pppppp..", "..pppppp..", "..jj..jj..", "..jj..jj..", ".kkk..kkk.",
)
private val runLegs = listOf("..xx...xx.", ".xx....xx.", ".kk....kk.")
private val crouchLegs = listOf(".xx....xx.", "xx......xx", "kk......kk")

private val digits = listOf(
    "111101101101111", "010110010010111", "111001111100111", "111001111001111", "101101111001001",
    "111100111001111", "111100111101111", "111001010010010", "111101111101111", "111101111001111",
)

private fun template(sport: SportKind) = when (sport) {
    SportKind.HOCKEY -> hockey
    SportKind.FOOTBALL -> football
    SportKind.BASEBALL -> baseball
    else -> hoops
}

/**
 * Draws a player standing on [feet]. [height] is the sprite height in px; [lift] raises it off the
 * floor (jumps) while the shadow stays put. [glow] rings the feet of the player with the ball.
 */
internal fun DrawScope.drawPlayer(
    feet: Offset, height: Float, kit: Kit, sport: SportKind, number: String?,
    pose: Pose = Pose.STAND, faceRight: Boolean = true, frame: Int = 0, lift: Float = 0f, glow: Color? = null, alpha: Float = 1f,
) {
    val p = height / 16f
    val w = p * 10
    val shadowW = w * .9f
    glow?.let {
        drawOval(it.copy(alpha = .35f * alpha), Offset(feet.x - shadowW * .8f, feet.y - p * 2.2f), Size(shadowW * 1.6f, p * 4.4f))
        drawOval(it.copy(alpha = alpha), Offset(feet.x - shadowW * .8f, feet.y - p * 2.2f), Size(shadowW * 1.6f, p * 4.4f), style = Stroke(p * .8f))
    }
    drawOval(Color.Black.copy(alpha = .32f * alpha), Offset(feet.x - shadowW / 2, feet.y - p * 1.1f), Size(shadowW, p * 2.2f))

    val rows = template(sport).toMutableList()
    val legColor = if (sport == SportKind.BASKETBALL) 's' else 'j'
    when (pose) {
        Pose.RUN -> if (frame % 2 == 1) for (i in 0..2) rows[13 + i] = runLegs[i].replace('x', legColor)
        Pose.CROUCH -> for (i in 0..2) rows[13 + i] = crouchLegs[i].replace('x', legColor)
        Pose.SHOOT -> {
            // both arms straight up
            val arm = if (sport == SportKind.BASKETBALL || sport == SportKind.BASEBALL) 's' else 'j'
            for (r in 0..3) rows[r] = arm + rows[r].substring(1, 9) + arm
            for (r in 5..8) rows[r] = "." + rows[r].substring(1, 9) + "."
        }
        Pose.STAND -> Unit
    }
    val left = feet.x - w / 2
    val top = feet.y - height - lift
    fun color(c: Char) = when (c) {
        'j' -> kit.jersey; 'a' -> kit.trim; 'p' -> kit.pants; 's' -> SKIN; 'h' -> HAIR; 'k' -> SHOE
        'w' -> Color.White; 'g' -> MASK; 'd' -> DARK; else -> null
    }
    val px = Size(p + .6f, p + .6f)
    rows.forEachIndexed { r, line ->
        line.forEachIndexed { c, ch ->
            val col = color(ch) ?: return@forEachIndexed
            val cc = if (faceRight) c else 9 - c
            drawRect(col.copy(alpha = col.alpha * alpha), Offset(left + cc * p, top + r * p), px)
        }
    }
    // jersey number, never mirrored
    val num = number?.filter { it.isDigit() }?.take(2)
    if (!num.isNullOrEmpty()) {
        val n = num
        val startCol = if (n.length == 1) 3.5f else 1.5f
        n.forEachIndexed { i, d ->
            val bits = digits[d - '0']
            for (b in 0 until 15) if (bits[b] == '1') {
                val x = left + (startCol + i * 4 + b % 3) * p
                val y = top + (5 + b / 3) * p
                drawRect(kit.number.copy(alpha = alpha), Offset(x, y), px)
            }
        }
    }
}

/** A stick held out in front of a hockey player – raised for a wind-up. */
internal fun DrawScope.drawStick(feet: Offset, height: Float, faceRight: Boolean, raise: Float) {
    val p = height / 16f
    val dir = if (faceRight) 1 else -1
    val hand = Offset(feet.x + dir * p * 4.5f, feet.y - p * 7.5f)
    val blade = Offset(feet.x + dir * p * (7f - raise * 6f), feet.y - p * (0.5f + raise * 12f))
    drawLine(Color(0xFF8B5A2B), hand, blade, strokeWidth = p * .9f)
    drawLine(Color(0xFF111111), blade, Offset(blade.x + dir * p * 2.2f, blade.y), strokeWidth = p * 1.1f)
}
