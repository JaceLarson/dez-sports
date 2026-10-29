package com.dez.sports.ui.team

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dez.sports.data.TeamPlayers
import com.dez.sports.model.League
import com.dez.sports.model.StatTable
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez

/** Columns that are counts/ids rather than something worth ranking players by. */
private val skip = setOf("GP", "GS", "TGP")

/**
 * Pick a stat and every player on the team is ranked on it as team-colour bars.
 * Tapping a bar opens that player.
 */
@Composable
fun PlayerCompare(t: StatTable, league: League, tc: Color, modifier: Modifier = Modifier) {
    // one chip per stat name (football repeats YDS/AVG/TD across passing and rushing); the headline stat leads
    val usable = t.labels.indices.filter { t.labels[it] !in skip && t.rows.any { r -> TeamPlayers.num(r.stats[it]).isFinite() } }.distinctBy { t.labels[it] }
    val lead = listOf("PTS", "TOT", "YDS", "SV%", "AVG", "IP", "G").firstNotNullOfOrNull { k -> usable.firstOrNull { t.labels[it] == k } }
    val cols = listOfNotNull(lead) + usable.filter { it != lead }
    if (cols.isEmpty()) return
    var col by remember(t.title) { mutableIntStateOf(cols.first()) }
    var all by remember(t.title) { mutableStateOf(false) }
    val lower = t.labels[col] in setOf("ERA", "WHIP", "GAA", "TO", "PF", "PIM", "GA", "INT")
    val ranked = t.rows.map { it to TeamPlayers.num(it.stats[col]) }.filter { it.second.isFinite() }
        .sortedBy { if (lower) it.second else -it.second }
    val top = ranked.maxOfOrNull { kotlin.math.abs(it.second) }?.takeIf { it > 0 } ?: 1.0
    val open = LocalOpenPlayer.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(vertical = 12.dp).animateContentSize()) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("COMPARE ${t.title.uppercase()}", style = MaterialTheme.typography.labelMedium, color = Dez.Sub, modifier = Modifier.weight(1f))
            t.descriptions.getOrNull(col)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Dez.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(cols) { _, c ->
                val sel = c == col
                Text(t.labels[c], style = MaterialTheme.typography.labelMedium, color = if (sel) Color.White else Dez.Sub,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) tc.readable().copy(alpha = .55f) else Dez.Card2).clickable { col = c }.padding(horizontal = 11.dp, vertical = 6.dp))
            }
        }
        val shown = if (all) ranked else ranked.take(8)
        shown.forEachIndexed { i, (r, v) ->
            val frac by animateFloatAsState((kotlin.math.abs(v) / top).toFloat().coerceIn(0.02f, 1f), tween(450), label = "bar")
            Row(Modifier.fillMaxWidth().stripe(i).clickable { r.id?.let { open?.invoke(league, it) } }.padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.labelMedium.merge(TabularNums), color = Dez.Faint, modifier = Modifier.width(22.dp))
                Text(r.shortName, style = MaterialTheme.typography.labelLarge, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(118.dp))
                Box(Modifier.weight(1f).height(18.dp)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(RoundedCornerShape(5.dp)).background(tc.readable().copy(alpha = if (i == 0) .95f else .55f)))
                }
                Text(r.stats[col], style = MaterialTheme.typography.labelLarge.merge(TabularNums).copy(fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal),
                    color = Dez.Text, modifier = Modifier.padding(start = 10.dp).widthIn(min = 44.dp))
            }
        }
        if (ranked.size > 8) Text(if (all) "Show top 8" else "Show all ${ranked.size}", style = MaterialTheme.typography.labelLarge, color = Color(0xFF64D2FF),
            modifier = Modifier.padding(start = 14.dp, top = 8.dp).clickable { all = !all })
    }
}
