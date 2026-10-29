package com.dez.sports.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dez.sports.model.Game
import com.dez.sports.model.League
import com.dez.sports.model.Leader
import com.dez.sports.model.GameState
import com.dez.sports.model.Side
import com.dez.sports.model.SportKind
import com.dez.sports.model.Team
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.darken
import com.dez.sports.ui.theme.teamColor

/** Opens a player page from anywhere (score cards, box scores, rosters). Provided by the nav host. */
val LocalOpenPlayer = staticCompositionLocalOf<((League, String) -> Unit)?> { null }

fun Side.color() = teamColor(team.color, team.altColor)

private fun dist(a: Color, b: Color) = kotlin.math.abs(a.red - b.red) + kotlin.math.abs(a.green - b.green) + kotlin.math.abs(a.blue - b.blue)

/** Away/home colours, swapping the away team to its alternate when the two primaries clash (e.g. Lakers vs Jazz). */
fun Game.colors(): Pair<Color, Color> {
    val h = home.color(); val a = away.color()
    if (dist(a, h) > 0.35f) return a to h
    val alt = teamColor(away.team.altColor, away.team.color)
    return (if (dist(alt, h) > dist(a, h)) alt else a) to h
}

/** Converts "DET 35" into a 0..1 position from the away (left) end zone. */
fun fieldPosition(game: Game, spot: String?): Float? {
    if (spot == null) return null
    val parts = spot.trim().split(" ")
    val yd = parts.lastOrNull()?.toIntOrNull() ?: return null
    if (parts.size == 1) return yd / 100f
    val side = parts.first()
    return when (side) {
        game.home.team.abbreviation -> (100 - yd) / 100f
        game.away.team.abbreviation -> yd / 100f
        else -> null
    }
}

@Composable
fun ScoreCard(game: Game, onClick: () -> Unit, modifier: Modifier = Modifier, highlight: Boolean = false, onPlayer: ((League, String) -> Unit)? = null) {
    val (awayC, homeC) = game.colors()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 600f), label = "press")
    val flash = rememberFlash("${game.away.score}-${game.home.score}")
    val live = game.state == GameState.LIVE
    val upset = rememberUpset(game)

    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(20.dp))
            .upsetGlow(upset, 20.dp)
            .background(Brush.horizontalGradient(0f to awayC.darken(0.25f), 0.5f to Color(0xFF111113), 1f to homeC.darken(0.25f)))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .35f))))
            .then(if (highlight || flash > 0f) Modifier.border(1.5.dp, Color.White.copy(alpha = if (flash > 0f) flash * .9f else .12f), RoundedCornerShape(20.dp)) else Modifier)
            .clickable(interaction, null, onClick = onClick)
            .animateContentSize(spring(stiffness = 400f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        upset?.let { UpsetBanner(it, Modifier.padding(bottom = 6.dp)) }
        val note = game.note ?: game.seriesSummary
        if (note != null) {
            Text(note.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .7f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), textAlign = TextAlign.Center)
        }
        // Two-column grids on the unfolded screen make cards narrower; shrink the score so names never clip.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 380.dp
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamBlock(game, game.away, Modifier.weight(1f), leading = true, logo = if (narrow) 30.dp else 34.dp)
                ScoreText(game, game.away, game.home, if (narrow) 25 else 30)
                Center(game, Modifier.width(if (narrow) 70.dp else 86.dp))
                ScoreText(game, game.home, game.away, if (narrow) 25 else 30)
                TeamBlock(game, game.home, Modifier.weight(1f), leading = false, logo = if (narrow) 30.dp else 34.dp)
            }
        }
        when {
            live && game.league.kind == SportKind.FOOTBALL && game.football != null -> FootballLive(game)
            live && game.league.kind == SportKind.BASEBALL && game.baseball != null -> BaseballLive(game)
            live && game.lastPlay != null -> LastPlay(game.lastPlay)
            game.state == GameState.PRE -> PreGameFooter(game)
        }
        if (game.leaders.isNotEmpty()) LeadersCarousel(game, onPlayer ?: LocalOpenPlayer.current)
    }
}

/** Logo beside the abbreviation and record, mirrored for the home side, so a card is one short row. */
@Composable
private fun TeamBlock(game: Game, side: Side, modifier: Modifier, leading: Boolean, logo: androidx.compose.ui.unit.Dp) {
    val dim = game.state == GameState.POST && !side.winner && (game.away.winner || game.home.winner)
    Row(modifier.graphicsLayer { alpha = if (dim) .62f else 1f }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (leading) Arrangement.Start else Arrangement.End) {
        val logoView = @Composable {
            Box {
                TeamLogo(side.team, logo)
                val poss = game.football?.possessionTeamId == side.team.id && game.state == GameState.LIVE
                if (poss) PossessionBall(Modifier.align(Alignment.BottomEnd).offset(x = 6.dp))
            }
        }
        if (leading) logoView()
        Column(Modifier.padding(horizontal = 6.dp).weight(1f, fill = false), horizontalAlignment = if (leading) Alignment.Start else Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                side.rank?.let { Text("$it ", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .65f)) }
                Text(side.team.abbreviation, style = MaterialTheme.typography.titleSmall, color = Dez.Text, maxLines = 1)
            }
            side.record?.let { Text(it.substringBefore(","), style = MaterialTheme.typography.labelSmall.merge(TabularNums), color = Color.White.copy(alpha = .55f), maxLines = 1) }
        }
        if (!leading) logoView()
    }
}

@Composable
private fun ScoreText(game: Game, side: Side, other: Side, size: Int) {
    if (game.state == GameState.PRE) { Spacer(Modifier.width(8.dp)); return }
    val lost = game.state == GameState.POST && (side.score?.toDoubleOrNull() ?: 0.0) < (other.score?.toDoubleOrNull() ?: 0.0)
    RollingScore(side.score ?: "0", ScoreStyle.copy(fontSize = size.sp), if (lost) Color.White.copy(alpha = .5f) else Color.White, Modifier.padding(horizontal = 2.dp))
}

@Composable
private fun Center(game: Game, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        when (game.state) {
            GameState.LIVE -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(5.dp)
                    Text(liveLabel(game), style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = Dez.Live, maxLines = 1)
                }
                TvLine(game, .6f)
            }
            GameState.PRE -> {
                Text(if (game.isTbd) "TBD" else localTime(game.dateIso), style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = Dez.Text, maxLines = 1)
                TvLine(game, .65f)
                WatchBadge(game, Modifier.padding(top = 3.dp))
            }
            GameState.POST -> {
                Text(game.statusShort.replace("Final/", "F/").let { if (it.startsWith("Final")) it.replace("Final", "FINAL") else it },
                    style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = .8f), textAlign = TextAlign.Center, maxLines = 2)
                DezBadge(game, Modifier.padding(top = 3.dp))
            }
        }
    }
}

fun liveLabel(game: Game): String {
    val s = game.statusShort
    return s.replace(" - ", " ").replace("End of ", "End ").replace("Halftime", "HALF")
}

@Composable
private fun FootballLive(game: Game) {
    val f = game.football!!
    val (awayC, homeC) = game.colors()
    Column(Modifier.padding(top = 6.dp)) {
        FieldStrip(
            yardLine = fieldPosition(game, f.spot)?.let { (it * 100).toInt() },
            possessionLeft = f.possessionTeamId?.let { it == game.away.team.id },
            redZone = f.isRedZone, leftColor = awayC, rightColor = homeC,
        )
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (f.isRedZone) { Pill("RED ZONE", Dez.Live.copy(alpha = .25f), Dez.Live); Spacer(Modifier.width(6.dp)) }
            Text(f.downDistance ?: "", style = MaterialTheme.typography.labelLarge, color = Dez.Text)
        }
        f.lastPlay?.let { LastPlay(it) }
    }
}

@Composable
private fun BaseballLive(game: Game) {
    val b = game.baseball!!
    Row(Modifier.padding(top = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BaseDiamond(b.onFirst, b.onSecond, b.onThird, 34.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            CountDots("B", b.balls, 3, Dez.Win)
            CountDots("S", b.strikes, 2, Dez.Gold)
            CountDots("O", b.outs, 2, Dez.Live)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            b.batter?.let { Text("AB  $it", style = MaterialTheme.typography.labelLarge, color = Dez.Text, maxLines = 1) }
            b.pitcher?.let { Text("P  $it", style = MaterialTheme.typography.labelMedium, color = Dez.Sub, maxLines = 1) }
        }
    }
    b.lastPlay?.let { LastPlay(it) }
}

@Composable
private fun LastPlay(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .6f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun PreGameFooter(game: Game) {
    val extra = game.broadcasts.drop(1)
    if (game.odds == null && extra.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        extra.take(2).forEach { Pill(it, Color.White.copy(alpha = .1f), Color.White.copy(alpha = .75f)); Spacer(Modifier.width(6.dp)) }
        game.odds?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .6f)) }
    }
}

/**
 * Swipeable top performers under the score. Page 1 is each team's best overall line,
 * then one page per stat (PTS / REB / AST, passing / rushing / receiving…). Auto-advances until touched.
 */
@Composable
private fun LeadersCarousel(game: Game, onPlayer: ((League, String) -> Unit)?) {
    val pages = remember(game.leaders) {
        val byCat = game.leaders.groupBy { it.category }
        val order = byCat.keys.sortedBy { if (it == "RAT") 0 else 1 }
        order.map { cat -> cat to byCat.getValue(cat) }
    }
    if (pages.isEmpty()) return
    val pager = androidx.compose.foundation.pager.rememberPagerState { pages.size }
    val title = when (game.state) { GameState.PRE -> "SEASON"; GameState.LIVE -> "LIVE"; GameState.POST -> "TOP" }
    Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .28f)).padding(vertical = 3.dp)) {
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxWidth()) { p ->
            val (cat, leaders) = pages[p]
            Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LeaderHalf(leaders.firstOrNull { it.teamId == game.away.team.id }, game, Modifier.weight(1f), leading = true, onPlayer)
                Text(if (cat == "RAT") title else cat, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .55f),
                    textAlign = TextAlign.Center, modifier = Modifier.width(44.dp), maxLines = 1)
                LeaderHalf(leaders.firstOrNull { it.teamId == game.home.team.id }, game, Modifier.weight(1f), leading = false, onPlayer)
            }
        }
        if (pages.size > 1) Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                val w by androidx.compose.animation.core.animateDpAsState(if (i == pager.currentPage) 14.dp else 5.dp, label = "dot")
                Box(Modifier.padding(horizontal = 2.dp).height(3.dp).width(w).clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = if (i == pager.currentPage) .8f else .25f)))
            }
        }
    }
}

@Composable
private fun LeaderHalf(l: Leader?, game: Game, modifier: Modifier, leading: Boolean, onPlayer: ((League, String) -> Unit)?) {
    if (l == null) { Spacer(modifier); return }
    val click = if (onPlayer != null && l.athleteId != null) Modifier.clickable { onPlayer(game.league, l.athleteId) } else Modifier
    Row(modifier.clip(RoundedCornerShape(10.dp)).then(click).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (leading) Arrangement.Start else Arrangement.End) {
        if (leading) Headshot(l)
        Column(Modifier.padding(horizontal = 6.dp).weight(1f, fill = false), horizontalAlignment = if (leading) Alignment.Start else Alignment.End) {
            Text(l.player, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(l.line, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, lineHeight = 12.sp), color = Color.White.copy(alpha = .7f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (leading) TextAlign.Start else TextAlign.End)
        }
        if (!leading) Headshot(l)
    }
}

@Composable
private fun Headshot(l: Leader) {
    coil3.compose.AsyncImage(l.headshot, l.player, Modifier.size(26.dp).clip(CircleShape).background(Color.White.copy(alpha = .08f)),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
}

fun Game.colorOf(s: Side): Color = if (s.team.id == away.team.id) colors().first else colors().second

/** Team B's colour, switched to its alternate when it would look like team A's (e.g. two blue teams). */
fun secondColor(ca: Color, b: Team): Color {
    val c = teamColor(b.color, b.altColor)
    val d = (c.red - ca.red) * (c.red - ca.red) + (c.green - ca.green) * (c.green - ca.green) + (c.blue - ca.blue) * (c.blue - ca.blue)
    return if (d < .03f) teamColor(b.altColor, b.color) else c
}
