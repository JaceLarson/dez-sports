package com.dez.sports.ui.scores

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dez.sports.model.Game
import com.dez.sports.model.GameState
import com.dez.sports.model.Side
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.lighten

/** "Detailed View" / "Compact View" switch shown above a league's scores. */
@Composable
fun ViewToggle(compact: Boolean, onChange: (Boolean) -> Unit, summary: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(summary, style = MaterialTheme.typography.labelMedium, color = Dez.Sub, modifier = Modifier.weight(1f))
        Row(Modifier.clip(RoundedCornerShape(50)).background(Dez.Card).padding(3.dp)) {
            listOf("Detailed View" to false, "Compact View" to true).forEach { (label, v) ->
                val sel = v == compact
                val bg by animateColorAsState(if (sel) Dez.Card2 else Color.Transparent, tween(200), label = "vt")
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (sel) Dez.Text else Dez.Sub,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).clickable { onChange(v) }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

/** One small tile per game: two team lines and the status, for scanning a whole slate at once. */
@Composable
fun CompactScore(g: Game, fav: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (awayC, homeC) = g.colors()
    val upset = rememberUpset(g)
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).upsetGlow(upset, 16.dp).background(Dez.Card).clickable(onClick = onClick).height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (upset != null) Box(Modifier.width(3.dp).fillMaxHeight().background(UpsetRed))
        else if (fav) Box(Modifier.width(3.dp).fillMaxHeight().background(Dez.Gold))
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            CompactTeam(g, g.away, g.home, awayC)
            CompactTeam(g, g.home, g.away, homeC)
        }
        Column(Modifier.width(78.dp).padding(end = 10.dp), horizontalAlignment = Alignment.End) {
            when (g.state) {
                GameState.LIVE -> {
                    if (upset != null) Text("UPSET ALERT", fontSize = 9.sp, fontWeight = FontWeight.Black, color = UpsetRed, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) { LiveDot(3.dp); Text(liveLabel(g), style = MaterialTheme.typography.labelMedium.merge(TabularNums), color = Dez.Live, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                GameState.PRE -> {
                    Text(if (g.isTbd) "TBD" else localTime(g.dateIso), style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = Dez.Text, maxLines = 1)
                    WatchBadge(g, Modifier.padding(vertical = 2.dp), compact = true)
                }
                GameState.POST -> Column(horizontalAlignment = Alignment.End) {
                    Text(g.statusShort.replace("Final/", "F/").let { if (it.startsWith("Final")) "Final" + it.removePrefix("Final") else it },
                    style = MaterialTheme.typography.labelMedium, color = Dez.Sub, maxLines = 1, textAlign = TextAlign.End)
                    DezBadge(g, Modifier.padding(top = 3.dp), compact = true)
                    if (upset != null) Text("UPSET", fontSize = 9.sp, fontWeight = FontWeight.Black, color = UpsetRed, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (g.state != GameState.POST) g.mainChannel()?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Dez.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun CompactTeam(g: Game, s: Side, other: Side, color: Color) {
    val my = s.score?.toIntOrNull(); val opp = other.score?.toIntOrNull()
    val trailing = g.state == GameState.POST && my != null && opp != null && my < opp
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 16.dp).clip(RoundedCornerShape(2.dp)).background(color.lighten(.2f)))
        Spacer(Modifier.width(6.dp))
        TeamLogo(s.team, 20.dp)
        Spacer(Modifier.width(8.dp))
        s.rank?.let { Text("$it ", style = MaterialTheme.typography.labelSmall, color = Dez.Sub) }
        Text(s.team.abbreviation, style = MaterialTheme.typography.titleSmall, color = if (trailing) Dez.Sub else Dez.Text, maxLines = 1)
        s.record?.let { Text("  $it", style = MaterialTheme.typography.labelSmall, color = Dez.Faint, maxLines = 1, overflow = TextOverflow.Clip) }
        Spacer(Modifier.weight(1f))
        if (g.state != GameState.PRE) {
            Text(s.score ?: "", style = MaterialTheme.typography.titleMedium.merge(TabularNums).copy(fontWeight = if (trailing) FontWeight.Medium else FontWeight.Bold),
                color = if (trailing) Dez.Sub else Dez.Text, modifier = Modifier.padding(end = 8.dp))
        }
    }
}
