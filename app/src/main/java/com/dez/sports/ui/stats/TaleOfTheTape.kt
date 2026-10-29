package com.dez.sports.ui.stats

import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dez.sports.model.Athlete
import com.dez.sports.model.League
import com.dez.sports.ui.components.TabularNums
import com.dez.sports.ui.game.arena.Kit
import com.dez.sports.ui.game.arena.Pose
import com.dez.sports.ui.game.arena.drawPlayer
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.teamColor
import kotlinx.coroutines.delay

private val lowerBetter = setOf("TO", "PF", "ERA", "WHIP", "GAA", "INT", "FUM", "L", "PIM", "BB")

/**
 * Fighting-game style versus screen: both players as 8-bit sprites in their own uniforms slide in,
 * "VS" slams down, then the tale of the tape – bio on the sides, who wins each stat, and a final tally.
 */
@Composable
fun TaleOfTheTape(league: League, a: Athlete, b: Athlete, rows: List<Triple<String, String, String>>, ca: Color, cb: Color) {
    val slide = remember(a.id, b.id) { Animatable(0f) }
    val vs = remember(a.id, b.id) { Animatable(0f) }
    LaunchedEffect(a.id, b.id) {
        slide.snapTo(0f); vs.snapTo(0f)
        slide.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        vs.animateTo(1f, spring(dampingRatio = .35f, stiffness = 380f))
    }
    val t = rememberInfiniteTransition(label = "tape")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "bob")
    val kitA = remember(a.team) { a.team?.let { Kit.of(teamColor(it.color, it.altColor), teamColor(it.altColor, it.color), league.kind) } ?: Kit.Ref }
    val kitB = remember(b.team) { b.team?.let { Kit.of(teamColor(it.color, it.altColor), teamColor(it.altColor, it.color), league.kind) } ?: Kit.Ref }

    // Category winners for the tally.
    var aWins = 0; var bWins = 0
    rows.forEach { (label, va, vb) ->
        val na = va.replace(",", "").toFloatOrNull(); val nb = vb.replace(",", "").toFloatOrNull()
        if (na != null && nb != null && na != nb) { if ((na > nb) != (label in lowerBetter)) aWins++ else bWins++ }
    }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Dez.Card)) {
        Box(Modifier.fillMaxWidth().height(190.dp).background(Brush.horizontalGradient(listOf(ca.copy(alpha = .45f), Color(0xFF0B0B10), cb.copy(alpha = .45f))))) {
            Canvas(Modifier.fillMaxSize()) {
                // Arena floor with a centre line.
                val floor = size.height * .82f
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .06f)), startY = floor - 40f, endY = size.height), Offset(0f, floor - 40f), Size(size.width, size.height - floor + 40f))
                drawLine(Color.White.copy(alpha = .15f), Offset(0f, floor), Offset(size.width, floor), strokeWidth = 2f)
                val h = size.height * .62f
                val ax = size.width * (-.15f + .45f * slide.value)
                val bx = size.width * (1.15f - .45f * slide.value)
                val frame = if (bob > .5f) 1 else 0
                drawPlayer(Offset(ax, floor), h, kitA, league.kind, a.jersey, Pose.STAND, faceRight = true, frame = frame, lift = bob * 3f)
                drawPlayer(Offset(bx, floor), h, kitB, league.kind, b.jersey, Pose.STAND, faceRight = false, frame = 1 - frame, lift = (1 - bob) * 3f)
            }
            Text("VS", fontSize = 46.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = Color.White,
                modifier = Modifier.align(Alignment.Center).scale(vs.value).padding(bottom = 24.dp))
            Text(a.name.substringAfterLast(' ').uppercase(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black), color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
            Text(b.name.substringAfterLast(' ').uppercase(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black), color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
        }
        Text("TALE OF THE TAPE", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black), color = Dez.Sub, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp))
        val bio = listOf(
            "Team" to (a.team?.abbreviation to b.team?.abbreviation), "Position" to (a.position to b.position), "Height" to (a.height to b.height),
            "Weight" to (a.weight to b.weight), "Age" to (a.age?.toString() to b.age?.toString()), "Experience" to (a.experience to b.experience),
        ).filter { it.second.first != null || it.second.second != null }
        bio.forEachIndexed { i, (label, v) ->
            TapeRow(label, v.first ?: "–", v.second ?: "–", null, ca, cb, i)
        }
        if (aWins + bWins > 0) {
            val leader = when { aWins > bWins -> a.name to ca; bWins > aWins -> b.name to cb; else -> null }
            var show by remember(a.id, b.id) { mutableStateOf(false) }
            LaunchedEffect(a.id, b.id) { delay(900); show = true }
            Row(Modifier.fillMaxWidth().padding(14.dp).clip(RoundedCornerShape(14.dp)).background((leader?.second ?: Dez.Card2).copy(alpha = .22f)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (show) "👑" else " ", fontSize = 22.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(leader?.let { "${it.first} takes it" } ?: "Dead even", style = MaterialTheme.typography.titleSmall, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Wins ${maxOf(aWins, bWins)} of ${aWins + bWins} season-average categories", style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
                }
                Text("$aWins", style = MaterialTheme.typography.headlineSmall.merge(TabularNums).copy(fontWeight = FontWeight.Black), color = ca)
                Text(" – ", style = MaterialTheme.typography.titleMedium, color = Dez.Sub)
                Text("$bWins", style = MaterialTheme.typography.headlineSmall.merge(TabularNums).copy(fontWeight = FontWeight.Black), color = cb)
            }
        } else Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun TapeRow(label: String, va: String, vb: String, aWins: Boolean?, ca: Color, cb: Color, i: Int) {
    Row(Modifier.fillMaxWidth().background(if (i % 2 == 1) Color.White.copy(alpha = .03f) else Color.Transparent).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(va, style = MaterialTheme.typography.bodyMedium.merge(TabularNums), color = if (aWins == true) ca else Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Dez.Faint, textAlign = TextAlign.Center, modifier = Modifier.width(96.dp))
        Text(vb, style = MaterialTheme.typography.bodyMedium.merge(TabularNums), color = if (aWins == false) cb else Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}
