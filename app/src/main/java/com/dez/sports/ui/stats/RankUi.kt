package com.dez.sports.ui.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dez.sports.data.LeagueTeamStats
import com.dez.sports.data.TeamRank
import com.dez.sports.data.TeamStatSpec
import com.dez.sports.model.League
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.teamColor

/** Opens a full ranked list: `statlist/{league}?...` routes, provided by the nav host. */
val LocalOpenRoute = staticCompositionLocalOf<(String) -> Unit> { {} }

fun teamListRoute(league: League, specIndex: Int, highlight: String? = null) = "statlist/${league.name}?team=$specIndex&hl=${highlight ?: ""}"
fun playerListRoute(league: League, leaderName: String, title: String) =
    "statlist/${league.name}?player=${android.net.Uri.encode(leaderName)}&title=${android.net.Uri.encode(title)}"

/** Green for the top third of the league, red for the bottom third, amber in between. */
fun rankColor(percentile: Float): Color = when {
    percentile >= .67f -> Dez.Win
    percentile <= .33f -> Dez.Live
    else -> Color(0xFFFFB340)
}

@Composable
fun RankPill(text: String, percentile: Float, modifier: Modifier = Modifier) {
    val c = rankColor(percentile)
    Text(text, style = MaterialTheme.typography.labelMedium.merge(TabularNums).copy(fontWeight = FontWeight.Bold), color = c, textAlign = TextAlign.Center, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = .16f)).padding(horizontal = 8.dp, vertical = 3.dp))
}

/** Where a value sits between the league's worst (left) and best (right), with the league average marked. */
@Composable
fun RangeBar(r: TeamRank, modifier: Modifier = Modifier) {
    val span = (r.best - r.worst).takeIf { kotlin.math.abs(it) > 1e-9 } ?: 1.0
    val pos by animateFloatAsState(((r.value - r.worst) / span).toFloat().coerceIn(0f, 1f), tween(500), label = "rb")
    val avg = ((r.average - r.worst) / span).toFloat().coerceIn(0f, 1f)
    val c = rankColor(r.percentile)
    BoxWithConstraints(modifier.fillMaxWidth().height(10.dp)) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Dez.Card2))
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(pos.coerceAtLeast(.02f)).height(4.dp).clip(RoundedCornerShape(50)).background(c.copy(alpha = .55f)))
        Box(Modifier.offset(x = maxWidth * avg - 1.dp).width(2.dp).fillMaxHeight().background(Dez.Sub))
        Box(Modifier.align(Alignment.CenterStart).offset(x = (maxWidth - 10.dp) * pos).size(10.dp).clip(CircleShape).background(c))
    }
}

/**
 * Team page: how this team stacks up against the rest of the league on the stats that matter,
 * with its best and worst areas called out up top. Tapping a stat opens the full league table.
 */
@Composable
fun TeamRanksCard(stats: LeagueTeamStats, teamId: String, modifier: Modifier = Modifier) {
    val ranks = remember(stats, teamId) { stats.ranks(teamId) }
    if (ranks.isEmpty()) return
    val open = LocalOpenRoute.current
    val team = stats.teams[teamId]
    val n = ranks.maxOf { it.of }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(vertical = 12.dp)) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            team?.let { TeamLogo(it, 20.dp); Spacer(Modifier.width(8.dp)) }
            Text(listOfNotNull(team?.name?.uppercase(), "LEAGUE RANKS").joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Dez.Text, modifier = Modifier.weight(1f))
            Text("vs all $n ${stats.league.shortLabel} teams", style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
        }
        val sorted = ranks.sortedByDescending { it.percentile }
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Highlights("BEST AT", sorted.take(3), Modifier.weight(1f))
            Highlights("WORST AT", sorted.takeLast(3).reversed(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        ranks.forEachIndexed { i, r ->
            Column(Modifier.fillMaxWidth().stripe(i).clickable { open(teamListRoute(stats.league, stats.specs.indexOf(r.spec), teamId)) }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.spec.label, style = MaterialTheme.typography.bodyMedium, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(r.display, style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = Dez.Text)
                    Spacer(Modifier.width(8.dp))
                    RankPill(r.ordinal, r.percentile, Modifier.widthIn(min = 52.dp))
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RangeBar(r, Modifier.weight(1f))
                    Text("avg ${r.spec.format(r.average)}", style = MaterialTheme.typography.labelSmall.merge(TabularNums), color = Dez.Faint, textAlign = TextAlign.End, modifier = Modifier.width(76.dp))
                }
            }
        }
        Text("Tap a stat to see where every team ranks · ▏= league average", style = MaterialTheme.typography.labelSmall, color = Dez.Faint, modifier = Modifier.padding(start = 14.dp, top = 8.dp))
    }
}

@Composable
private fun Highlights(title: String, rs: List<TeamRank>, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Dez.Card2).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
        rs.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.spec.label, style = MaterialTheme.typography.labelMedium, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(r.ordinal, style = MaterialTheme.typography.labelMedium.merge(TabularNums).copy(fontWeight = FontWeight.Bold), color = rankColor(r.percentile))
            }
        }
    }
}

/** Stats tab: the top teams in one stat as bars, with favourites called out and a way into the full table. */
@Composable
fun TeamBoard(stats: LeagueTeamStats, spec: TeamStatSpec, favIds: Set<String>, onTeam: (String) -> Unit) {
    val open = LocalOpenRoute.current
    val rows = remember(stats, spec) { stats.table(spec) }
    if (rows.isEmpty()) { EmptyState("No team stats yet"); return }
    val top = rows.take(10)
    val mine = rows.filter { it.team.id in favIds && it !in top }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Dez.Card).padding(vertical = 10.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = Dez.Text, modifier = Modifier.weight(1f))
            Text(if (spec.higherIsBetter) "Higher is better" else "Lower is better", style = MaterialTheme.typography.labelSmall, color = Dez.Faint)
        }
        val avg = rows.map { it.value }.average()
        top.forEachIndexed { i, r -> TeamBarRow(r, rows, spec, r.team.id in favIds, i, avg) { onTeam(r.team.id) } }
        if (mine.isNotEmpty()) {
            Text("YOUR TEAMS", style = MaterialTheme.typography.labelSmall, color = Dez.Gold, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp))
            mine.forEachIndexed { i, r -> TeamBarRow(r, rows, spec, true, i, avg) { onTeam(r.team.id) } }
        }
        Text("View all ${rows.size} teams ›", style = MaterialTheme.typography.labelLarge, color = Color(0xFF64D2FF),
            modifier = Modifier.padding(start = 14.dp, top = 10.dp).clip(RoundedCornerShape(50)).clickable { open(teamListRoute(stats.league, stats.specs.indexOf(spec))) }.padding(vertical = 4.dp))
    }
}

/** One team's row: rank, logo, name, a bar scaled from the league's worst to best value, and the number. */
@Composable
fun TeamBarRow(r: com.dez.sports.data.TeamRow, all: List<com.dez.sports.data.TeamRow>, spec: TeamStatSpec, highlight: Boolean, i: Int, avg: Double, onClick: () -> Unit) {
    val best = all.first().value; val worst = all.last().value
    val span = (best - worst).takeIf { kotlin.math.abs(it) > 1e-9 } ?: 1.0
    val frac by animateFloatAsState((.08f + .92f * ((r.value - worst) / span).toFloat()).coerceIn(.04f, 1f), tween(450), label = "tb")
    val tc = teamColor(r.team.color, r.team.altColor).readable()
    Row(Modifier.fillMaxWidth().then(if (highlight) Modifier.background(tc.copy(alpha = .16f)) else Modifier.stripe(i)).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text((if (r.tied) "T" else "") + r.rank, style = MaterialTheme.typography.labelMedium.merge(TabularNums), color = Dez.Sub, modifier = Modifier.width(30.dp))
        TeamLogo(r.team, 22.dp)
        Spacer(Modifier.width(8.dp))
        Text(r.team.abbreviation, style = MaterialTheme.typography.labelLarge, color = if (highlight) Dez.Text else Dez.Text.copy(alpha = .9f), maxLines = 1, modifier = Modifier.width(48.dp))
        BoxWithConstraints(Modifier.weight(1f).height(16.dp)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(RoundedCornerShape(5.dp)).background(tc.copy(alpha = if (r.rank == 1) .95f else .6f)))
            val a = ((avg - worst) / span).toFloat().coerceIn(0f, 1f)
            Box(Modifier.offset(x = maxWidth * (.08f + .92f * a)).width(1.5.dp).fillMaxHeight().background(Dez.Text.copy(alpha = .5f)))
        }
        Text(spec.format(r.value), style = MaterialTheme.typography.labelLarge.merge(TabularNums).copy(fontWeight = if (r.rank == 1) FontWeight.Bold else FontWeight.Normal),
            color = Dez.Text, textAlign = TextAlign.End, modifier = Modifier.width(62.dp))
    }
}
