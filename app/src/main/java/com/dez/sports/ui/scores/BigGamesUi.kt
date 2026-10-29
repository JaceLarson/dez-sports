package com.dez.sports.ui.scores

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dez.sports.data.BigGame
import com.dez.sports.model.Game
import com.dez.sports.model.GameState
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez

private fun tagColor(tag: String) = when (tag) {
    "Close & Late", "Overtime" -> Dez.Live
    "Upset Alert" -> Color(0xFFFF9F0A)
    "Primetime", "Top-10 Clash", "Unbeatens Collide" -> Dez.Gold
    else -> Color(0xFF64D2FF)
}

/** A featured national-TV game: why it matters, where to watch, then the usual score card. */
@Composable
fun BigGameCard(b: BigGame, onGame: (Game) -> Unit, modifier: Modifier = Modifier) {
    val g = b.game
    // Read only while drawing, so the pulse doesn't rebuild the whole card every frame.
    val glow = if (b.mustWatch) {
        val t = rememberInfiniteTransition(label = "must")
        t.animateFloat(.25f, .8f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "glow")
    } else null
    Column(
        modifier.clip(RoundedCornerShape(26.dp)).background(Dez.Card)
            .then(if (glow != null) Modifier.drawWithContent {
                drawContent()
                // The clip trims the outer half of the stroke, leaving a 1.5dp ring like a border.
                drawOutline(RoundedCornerShape(26.dp).createOutline(size, layoutDirection, this), Dez.Live.copy(alpha = glow.value), style = Stroke(3.dp.toPx()))
            } else Modifier)
            .padding(bottom = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(g.league.shortLabel, style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (b.mustWatch) Row(Modifier.clip(RoundedCornerShape(50)).background(Dez.Live).padding(start = 2.dp, end = 8.dp, top = 1.dp, bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(3.dp, Color.White)
                    Text("MUST WATCH", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = Color.White)
                }
                b.tags.filter { !b.mustWatch || it !in setOf("Close & Late", "Overtime") }.forEach { tag ->
                    val c = tagColor(tag)
                    Text(tag.uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = c, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = .16f)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            b.channel?.let { ChannelBadge(it.name, 22.dp) }
        }
        ScoreCard(g, onClick = { onGame(g) }, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/** Every national-TV game today in start-time order, like a TV guide. Collapses to the next few. */
@Composable
fun TvGuideCard(guide: List<BigGame>, onGame: (Game) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val upcoming = guide.count { it.game.state != GameState.POST }
    val shown = if (expanded) guide else guide.filter { it.game.state != GameState.POST }.take(6).ifEmpty { guide.take(6) }
    Column(modifier.clip(RoundedCornerShape(26.dp)).background(Dez.Card).animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)).padding(vertical = 6.dp)) {
        shown.forEachIndexed { i, b -> GuideRow(b, onGame, Modifier.stripe(i)) }
        if (guide.size > shown.size || expanded) {
            Text(if (expanded) "Show less" else "Show all ${guide.size} on national TV" + if (upcoming < guide.size) " · incl. finals" else "",
                style = MaterialTheme.typography.labelLarge, color = Color(0xFF64D2FF),
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun GuideRow(b: BigGame, onGame: (Game) -> Unit, modifier: Modifier) {
    val g = b.game
    val done = g.state == GameState.POST
    Row(modifier.fillMaxWidth().clickable { onGame(g) }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(74.dp)) {
            when (g.state) {
                GameState.LIVE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(3.dp); Text(liveLabel(g), style = MaterialTheme.typography.labelMedium.merge(TabularNums), color = Dez.Live, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                GameState.PRE -> Text(if (g.isTbd) "TBD" else localTime(g.dateIso), style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = Dez.Text, maxLines = 1)
                GameState.POST -> Text("Final", style = MaterialTheme.typography.labelMedium, color = Dez.Faint)
            }
        }
        Text(g.league.shortLabel, style = MaterialTheme.typography.labelSmall, color = Dez.Faint, modifier = Modifier.width(34.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            GuideTeam(g, g.away, done)
            Text(if (g.state == GameState.PRE) "  @  " else "  ", style = MaterialTheme.typography.labelMedium, color = Dez.Faint)
            GuideTeam(g, g.home, done)
        }
        if (b.hype >= 40 && !done) Text("★", color = Dez.Gold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
        b.channel?.let { ChannelBadge(it.name, 18.dp) }
    }
}

@Composable
private fun GuideTeam(g: Game, s: com.dez.sports.model.Side, done: Boolean) {
    val other = if (s == g.away) g.home else g.away
    val lost = g.state != GameState.PRE && (s.score?.toIntOrNull() ?: 0) < (other.score?.toIntOrNull() ?: 0)
    TeamLogo(s.team, 18.dp)
    Spacer(Modifier.width(5.dp))
    val label = (s.rank?.let { "$it " } ?: "") + s.team.abbreviation + if (g.state != GameState.PRE) " ${s.score ?: ""}" else ""
    Text(label, style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = if (done || lost) Dez.Sub else Dez.Text, maxLines = 1)
}
