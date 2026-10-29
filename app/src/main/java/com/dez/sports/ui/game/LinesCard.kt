package com.dez.sports.ui.game

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dez.sports.data.LineGroup
import com.dez.sports.data.LinePlayer
import com.dez.sports.data.Lines
import com.dez.sports.data.TeamLines
import com.dez.sports.model.Game
import com.dez.sports.model.League
import com.dez.sports.ui.components.*
import com.dez.sports.ui.theme.Dez
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Lines don't change minute to minute; keep them for the session so tab swipes don't refetch. */
private val cache = java.util.concurrent.ConcurrentHashMap<String, TeamLines>()

/** Both teams' projected lines for an NHL game, one team at a time. */
@Composable
fun LinesCard(g: Game) {
    val lines by produceState<List<TeamLines?>?>(null, g.away.team.id, g.home.team.id) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            listOf(g.away.team, g.home.team).map { t ->
                async { cache[t.id] ?: runCatching { Lines.load(t) }.getOrNull()?.also { cache[t.id] = it } }
            }.map { it.await() }
        }
    }
    var team by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(vertical = 14.dp)) {
        Text("STARTING LINES", style = MaterialTheme.typography.labelMedium, color = Dez.Sub, modifier = Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(10.dp))
        TeamSwitch(g, team, { team = it }, Modifier.padding(horizontal = 10.dp), dense = true)
        AnimatedContent(lines?.let { it.getOrNull(team) to team }, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "lines") { state ->
            when {
                state == null -> Text("Loading lines…", style = MaterialTheme.typography.bodySmall, color = Dez.Sub, modifier = Modifier.padding(14.dp))
                state.first == null -> Text("Lines aren't available for this team right now.", style = MaterialTheme.typography.bodySmall, color = Dez.Sub, modifier = Modifier.padding(14.dp))
                else -> TeamLinesView(state.first!!, if (state.second == 0) g.colorOf(g.away) else g.colorOf(g.home))
            }
        }
    }
}

@Composable
private fun TeamLinesView(l: TeamLines, tc: Color) {
    val accent = tc.readable()
    Column(Modifier.padding(top = 10.dp)) {
        SourceLine(l)
        Section("FORWARDS")
        l.forwards.forEachIndexed { i, grp -> LineRow("L${i + 1}", grp, accent, i) }
        Section("DEFENSE")
        l.defense.forEachIndexed { i, grp -> LineRow("D${i + 1}", grp, accent, i) }
        if (l.goalies.isNotEmpty()) { Section("GOALIES"); Goalies(l.goalies, accent) }
        if (l.powerPlay.isNotEmpty() || l.penaltyKill.isNotEmpty()) {
            Section("SPECIAL TEAMS")
            l.powerPlay.forEachIndexed { i, grp -> UnitRow("PP${i + 1}", grp, Dez.Win) }
            l.penaltyKill.forEachIndexed { i, grp -> UnitRow("PK${i + 1}", grp, Dez.Live) }
        }
        if (l.out.isNotEmpty()) {
            Section("OUT")
            l.out.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = MaterialTheme.typography.bodySmall, color = Dez.Text, modifier = Modifier.weight(1f))
                    Text(p.injury?.uppercase() ?: "OUT", style = MaterialTheme.typography.labelSmall, color = Dez.Live)
                }
            }
        }
    }
}

/** Where the lines came from: a reporter/morning skate (fresh) or just carried over from the last game. */
@Composable
private fun SourceLine(l: TeamLines) {
    val lastGame = Regex("Last Game \\((\\d{4}-\\d{2}-\\d{2})\\)").find(l.source)?.groupValues?.get(1)
        ?.let { runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("MMM d")) }.getOrNull() }
    val text = when {
        lastGame != null -> "Same as last game · $lastGame"
        l.source.isNotBlank() -> "Reported by ${l.source}"
        else -> "Latest lines"
    } + (l.updated?.let { " · updated ${ago(it)}" } ?: "")
    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (l.reported) Dez.Win else Dez.Faint))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Dez.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun ago(t: Instant): String {
    val m = Duration.between(t, Instant.now()).toMinutes().coerceAtLeast(0)
    return when { m < 60 -> "${m}m ago"; m < 48 * 60 -> "${m / 60}h ago"; else -> "${m / 1440}d ago" }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelSmall, color = Dez.Faint, modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp))
}

/** "L1  [9 Keller] [8 Schmaltz] [67 Crouse]" – one line, wingers either side of the centre. */
@Composable
private fun LineRow(label: String, grp: LineGroup, accent: Color, i: Int) {
    Row(Modifier.fillMaxWidth().stripe(i).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium.merge(TabularNums), color = Dez.Sub, modifier = Modifier.width(28.dp))
        // Pairs and trios share the width so a line reads straight across.
        val slots = if (label.startsWith("D")) 2 else 3
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(slots) { s -> Box(Modifier.weight(1f)) { grp.players.getOrNull(s)?.let { PlayerChip(it, accent) } } }
        }
    }
}

@Composable
private fun PlayerChip(p: LinePlayer, accent: Color) {
    val open = LocalOpenPlayer.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Dez.Card2)
        .then(if (open != null && p.espnId != null) Modifier.clickable { open(League.NHL, p.espnId) } else Modifier)
        .padding(horizontal = 5.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Jersey(p.jersey, accent, 22.dp)
        Spacer(Modifier.width(5.dp))
        Text(p.lastName, style = MaterialTheme.typography.labelLarge, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (p.gameTimeDecision || p.injury != null) Text(" GTD", style = MaterialTheme.typography.labelSmall, color = Dez.Gold, maxLines = 1)
    }
}

@Composable
private fun Jersey(n: String?, accent: Color, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(accent.copy(alpha = .8f)), contentAlignment = Alignment.Center) {
        Text(n ?: "–", color = if (accent.luminance() > .6f) Color.Black else Color.White, fontSize = if ((n?.length ?: 0) > 1) 10.sp else 11.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, style = TabularNums)
    }
}

/** Starter gets the headshot and the spotlight; the backup sits beside him. */
@Composable
private fun Goalies(gs: List<LinePlayer>, accent: Color) {
    val open = LocalOpenPlayer.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        gs.take(2).forEachIndexed { i, p ->
            Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (i == 0) accent.copy(alpha = .18f) else Dez.Card2)
                .then(if (open != null && p.espnId != null) Modifier.clickable { open(League.NHL, p.espnId) } else Modifier)
                .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (p.headshot != null) AsyncImage(p.headshot, null, Modifier.size(34.dp).clip(CircleShape).background(Dez.Card2), contentScale = ContentScale.Crop)
                else Jersey(p.jersey, accent, 30.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (i == 0) "STARTER" else "BACKUP", style = MaterialTheme.typography.labelSmall, color = if (i == 0) accent else Dez.Faint)
                    Text(p.lastName, style = MaterialTheme.typography.labelLarge, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                p.jersey?.let { Text("#$it", style = MaterialTheme.typography.labelMedium.merge(TabularNums), color = Dez.Sub) }
            }
        }
    }
}

/** Special-teams units are five skaters, so they read as a single line of names. */
@Composable
private fun UnitRow(label: String, grp: LineGroup, color: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium.merge(TabularNums), color = color, modifier = Modifier.width(38.dp))
        Text(grp.players.joinToString("  ·  ") { it.lastName }, style = MaterialTheme.typography.bodySmall, color = Dez.Text.copy(alpha = .9f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
