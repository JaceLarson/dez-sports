package com.dez.sports.ui.game

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dez.sports.model.*
import com.dez.sports.ui.components.TabularNums
import com.dez.sports.ui.components.isZeroStat
import com.dez.sports.ui.components.readable
import com.dez.sports.ui.components.stripe
import com.dez.sports.ui.components.AccentRule
import com.dez.sports.ui.components.TeamLogo
import com.dez.sports.ui.components.HeatIcon
import com.dez.sports.ui.components.colors
import com.dez.sports.ui.components.colorOf
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.teamColor

/** Segmented away/home switch tinted with each team's colour. */
@Composable
fun TeamSwitch(game: Game, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, dense: Boolean = false) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Dez.Card).padding(if (dense) 3.dp else 4.dp)) {
        listOf(game.away, game.home).forEachIndexed { i, side ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) game.colorOf(side).copy(alpha = .9f) else Color.Transparent, tween(250), label = "ts")
            Row(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(bg).clickable { onSelect(i) }.padding(vertical = if (dense) 5.dp else 8.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(side.team, if (dense) 18.dp else 20.dp); Spacer(Modifier.width(8.dp))
                Text(side.team.name.ifEmpty { side.team.abbreviation }, style = MaterialTheme.typography.labelLarge, color = if (sel) Color.White else Dez.Sub)
                if (dense) side.score?.let { Text("  $it", style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = if (sel) Color.White.copy(alpha = .75f) else Dez.Faint) }
            }
        }
    }
}

@Composable
fun TeamBoxView(box: TeamBox, league: League, wide: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        box.tables.forEach { StatTableView(it, box.team, league, wide) }
    }
}

// ============================================================ wide "fit to screen" box score

private val tableGap = 10.dp
private val headerH = 24.dp
private val subH = 18.dp
private val dnpH = 26.dp

private fun nameWidth(wide: Boolean, cols: Int) = when { !wide -> 132.dp; cols > 1 -> 116.dp; else -> 176.dp }

/** Narrowest each column can be without clipping: sized to its longest value or label. */
private fun naturalCols(t: StatTable): List<Dp> = t.labels.indices.map { i ->
    val v = maxOf(t.rows.maxOfOrNull { it.stats.getOrNull(i)?.length ?: 0 } ?: 0, t.totals?.getOrNull(i)?.length ?: 0)
    // Room for the bold leader pill around the value, so "620" never loses a digit.
    maxOf(v * (if (v > 6) 7.8f else 7.6f) + 14f, t.labels[i].length * 7f + 10f, 30f).dp
}

/** Sub-header rows: explicit groups, or basketball's starters / bench split. */
private fun groupsOf(t: StatTable, league: League): Map<Int, String> {
    if (t.groups.isNotEmpty()) return t.groups
    val p = t.rows.filterNot { it.didNotPlay }
    if (league.kind != SportKind.BASKETBALL || p.none { it.starter } || p.all { it.starter }) return emptyMap()
    return mapOf(0 to "STARTERS", p.indexOfFirst { !it.starter } to "BENCH")
}

private val hockeyOrder = listOf("G", "A", "PTS", "+/-", "S", "SOG", "SM", "BS", "HT", "TK", "GV", "PN", "PIM", "FW", "FL", "FO%", "SHFT", "TOI", "PPTOI", "SHTOI", "ESTOI", "YTDG")

/**
 * Tidies tables for display: hockey forwards + defense become one "Skaters" table (same columns,
 * one header) and skater columns lead with goals/assists instead of blocked shots.
 */
fun prepareTables(tables: List<StatTable>, league: League): List<StatTable> {
    var out = tables.filter { it.rows.isNotEmpty() }
    if (league.kind == SportKind.HOCKEY) {
        val f = out.firstOrNull { it.title.equals("Forwards", true) }
        val d = out.firstOrNull { it.title.startsWith("Defense", true) }
        if (f != null && d != null && f.labels == d.labels) {
            val merged = StatTable("Skaters", f.labels, f.descriptions, f.rows.filterNot { it.didNotPlay } + d.rows.filterNot { it.didNotPlay } + f.rows.filter { it.didNotPlay } + d.rows.filter { it.didNotPlay }, null,
                groups = mapOf(0 to "FORWARDS", f.rows.count { !it.didNotPlay } to "DEFENSE"))
            out = out.flatMap { if (it === f) listOf(merged) else if (it === d) emptyList() else listOf(it) }
        }
        out = out.map { t ->
            if ("G" !in t.labels || "A" !in t.labels) t else {
                val idx = t.labels.indices.sortedBy { i -> hockeyOrder.indexOf(t.labels[i]).let { if (it < 0) 99 else it } }
                t.copy(labels = idx.map { t.labels[it] }, descriptions = idx.map { t.descriptions.getOrElse(it) { "" } },
                    rows = t.rows.map { r -> r.copy(stats = idx.map { r.stats.getOrElse(it) { "" } }) }, totals = t.totals?.let { tot -> idx.map { tot.getOrElse(it) { "" } } })
            }
        }
    }
    return out
}

/** Height that doesn't depend on row height, and how many data rows the table has. */
private fun fixedAndRows(t: StatTable, league: League): Pair<Dp, Int> {
    val playing = t.rows.count { !it.didNotPlay }
    val fixed = headerH + subH * groupsOf(t, league).size + (if (t.rows.any { it.didNotPlay }) dnpH else 0.dp) + 8.dp
    return fixed to playing + (if (t.totals != null) 1 else 0)
}

private data class BoxPlan(val columns: List<List<StatTable>>, val rowH: Dp)

/**
 * Packs one team's tables into 1–3 newspaper columns and picks the row height that makes the
 * tallest column fit the screen. Basketball/hockey end up as one big table; football's many
 * narrow tables spread across columns.
 */
private fun planBox(tables: List<StatTable>, league: League, w: Dp, h: Dp): BoxPlan {
    var best: BoxPlan? = null
    var bestFit = -1f
    for (k in 1..3) {
        val colW = (w - tableGap * (k - 1)) / k
        if (k > 1 && tables.any { nameWidth(true, k) + naturalCols(it).fold(0.dp) { a, b -> a + b } > colW + 32.dp }) continue
        if (k > tables.size) continue
        // Masonry: each table goes to whichever column is currently shortest (estimated at 30dp rows).
        val cols = List(k) { mutableListOf<StatTable>() }
        val est = FloatArray(k)
        tables.forEach { t ->
            val (f, r) = fixedAndRows(t, league)
            val c = est.indices.minBy { est[it] }
            cols[c] += t; est[c] += f.value + r * 30f + tableGap.value
        }
        val fit = cols.filter { it.isNotEmpty() }.minOf { col ->
            val f = col.sumOf { fixedAndRows(it, league).first.value.toDouble() }.toFloat() + tableGap.value * (col.size - 1)
            val r = col.sumOf { fixedAndRows(it, league).second }.coerceAtLeast(1)
            (h.value - f) / r
        }
        // Prefer fewer columns unless more columns are needed to fit comfortably.
        if (fit > bestFit + 2f || best == null) { best = BoxPlan(cols, fit.coerceIn(24f, 34f).dp); bestFit = fit }
        if (fit >= 34f) break
    }
    return best!!
}

@Composable
fun WideBoxScore(game: Game, boxes: List<TeamBox>, team: Int) {
    val box = boxes.getOrNull(team) ?: return
    val tables = remember(box) { prepareTables(box.tables, game.league) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val plan = remember(tables, maxWidth, maxHeight) { planBox(tables, game.league, maxWidth, maxHeight - 4.dp) }
            Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(tableGap)) {
                plan.columns.forEach { col ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(tableGap)) {
                        col.forEach { key(box.team.id, it.title) { StatTableView(it, box.team, game.league, wide = true, rowH = plan.rowH, dense = true, multiCol = plan.columns.size > 1) } }
                    }
                }
            }
        }
    }
}

// ============================================================ table

/** The stat(s) your eye should land on first in each sport. */
private fun keyCols(league: League) = when (league.kind) {
    SportKind.BASKETBALL -> setOf("PTS")
    SportKind.HOCKEY -> setOf("G", "A", "SV")
    SportKind.FOOTBALL -> setOf("YDS")
    SportKind.BASEBALL -> setOf("H", "RBI", "IP")
    else -> emptySet()
}


/** Stats where the biggest number isn't an achievement (time, volume, or bad things). */
private val noLeader = setOf("MIN", "TOI", "PPTOI", "SHTOI", "ESTOI", "SHFT", "AB", "H-AB", "#P", "PC", "PC-ST", "ERA", "WHIP", "AVG", "OBP", "SLG", "OPS",
    "YTDG", "TO", "PF", "PIM", "GA", "GV", "FL", "LOST", "PN", "SM", "TGTS")
private val pitchingBad = setOf("H", "R", "ER", "BB", "HR")

private fun leaderEligible(t: StatTable, label: String) = label !in noLeader &&
    !(t.title.contains("Pitch", true) && label in pitchingBad) && !(t.title.equals("Passing", true) && label in setOf("INT", "SACKS"))

/** Double-double / triple-double tag for basketball lines. */
private fun ddTag(t: StatTable, r: StatRow): String? {
    val n = listOf("PTS", "REB", "AST", "STL", "BLK").count { l -> t.labels.indexOf(l).takeIf { it >= 0 }?.let { r.stats.getOrNull(it)?.toIntOrNull() }?.let { it >= 10 } == true }
    return when { n >= 3 -> "TD"; n == 2 -> "DD"; else -> null }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatTableView(t: StatTable, team: Team, league: League, wide: Boolean, rowH: Dp = 40.dp, dense: Boolean = false, multiCol: Boolean = false,
                  heat: Map<String, com.dez.sports.data.Heat> = emptyMap(), onCellLongPress: ((StatRow, Int) -> Unit)? = null) {
    var sortCol by remember(t.title) { mutableIntStateOf(-1) }
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) { if (hint != null) { delay(2500); hint = null } }
    val openPlayer = com.dez.sports.ui.components.LocalOpenPlayer.current
    val tc = teamColor(team.color, team.altColor).readable()
    val numericCol = { i: Int -> t.rows.any { r -> r.stats.getOrNull(i)?.let { parseNum(it) } != null } }
    val playing = t.rows.filterNot { it.didNotPlay }
    val sorted = if (sortCol >= 0) playing.sortedByDescending { parseNum(it.stats.getOrNull(sortCol) ?: "") ?: Float.MIN_VALUE } else playing
    val dnp = t.rows.filter { it.didNotPlay }
    // Column max for the "leader" pill and the key-stat bars.
    val maxes = t.labels.indices.map { i -> if (numericCol(i)) playing.maxOfOrNull { parseNum(it.stats.getOrNull(i) ?: "") ?: Float.MIN_VALUE } else null }
    val groups = if (sortCol < 0) groupsOf(t, league) else emptyMap()
    val keys = keyCols(league)
    val plusMinus = t.labels.indexOfFirst { it == "+/-" }
    val valueStyle = MaterialTheme.typography.bodyMedium.merge(TabularNums).let { if (dense) it.copy(fontSize = 13.sp) else it }

    val status = hint ?: if (sortCol >= 0) "Sorted by ${t.labels[sortCol]} · reset" else ""
    Column(Modifier.clip(RoundedCornerShape(if (dense) 16.dp else 20.dp)).background(Dez.Card)) {
        if (dense) {
            // Title moves into the header row; hints float over a thin bar only when needed.
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelMedium, color = if (hint != null) Dez.Sub else Dez.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(Dez.Card2).then(if (hint == null) Modifier.clickable { sortCol = -1 } else Modifier).padding(horizontal = 14.dp, vertical = 3.dp))
        } else Row(Modifier.height(44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tc)); Spacer(Modifier.width(8.dp))
            Text(t.title, style = MaterialTheme.typography.titleMedium, color = Dez.Text, maxLines = 1)
            Spacer(Modifier.weight(1f))
            AnimatedContent(status, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) }, label = "hint") { s ->
                Text(s, style = MaterialTheme.typography.labelMedium, color = if (hint != null) Dez.Sub else Dez.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (hint == null && sortCol >= 0) Modifier.clickable { sortCol = -1 } else Modifier)
            }
        }
        val natural = remember(t) { naturalCols(t) }
        val scroll = rememberScrollState()
        var availW by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        // Very wide tables (hockey skaters) give up some name width before resorting to sideways scrolling.
        val nameW = with(density) { nameWidth(wide, if (multiCol) 2 else 1).let { w -> if (!wide || availW == 0) w else (availW.toDp() - 8.dp - natural.fold(0.dp) { a, b -> a + b }).coerceIn(minOf(110.dp, w), w) } }
        // Columns keep their natural width; spare room is shared out so the table fills the card.
        val colWs: List<Dp> = with(density) {
            val spare = availW.toDp() - nameW - 8.dp - natural.fold(0.dp) { a, b -> a + b }
            val extra = if (spare > 0.dp && natural.isNotEmpty()) spare / natural.size else 0.dp
            natural.map { it + extra }
        }
        @Composable
        fun rowBg(idx: Int) = Modifier.stripe(idx)

        Row(Modifier.fillMaxWidth().onSizeChanged { availW = it.width }) {
            // Sticky name column
            Column(Modifier.width(nameW)) {
                if (dense) Row(Modifier.width(nameW).height(headerH).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(tc)); Spacer(Modifier.width(7.dp))
                    Text(t.title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else HeaderCell("", nameW, TextAlign.Start, dense)
                AccentRule(tc)
                sorted.forEachIndexed { idx, r ->
                    groups[idx]?.let { SubHeader(it) }
                    NameCell(r, nameW, rowH, wide, dense, multiCol || nameW < 150.dp, if (league.kind == SportKind.BASKETBALL) ddTag(t, r) else null, tc, rowBg(idx),
                        openPlayer?.let { f -> r.id?.let { id -> { f(league, id) } } }, r.id?.let { heat[it] })
                }
                if (t.totals != null) Box(Modifier.height(rowH).fillMaxWidth().background(Dez.Card2).padding(start = 14.dp), contentAlignment = Alignment.CenterStart) {
                    Text("TEAM", style = MaterialTheme.typography.labelLarge, color = Dez.Text)
                }
            }
            Column(Modifier.weight(1f).horizontalScroll(scroll)) {
                Row {
                    t.labels.forEachIndexed { i, l ->
                        HeaderCell(l, colWs[i], TextAlign.Center, dense, selected = i == sortCol, key = l in keys,
                            onClick = if (numericCol(i)) ({ sortCol = if (sortCol == i) -1 else i }) else null,
                            onLongClick = { hint = t.descriptions.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { "$l · $it" } ?: l })
                    }
                }
                AccentRule(tc, Modifier.width(colWs.fold(0.dp) { a, b -> a + b }))
                sorted.forEachIndexed { idx, r ->
                    if (idx in groups) Spacer(Modifier.height(subH))
                    Row(Modifier.height(rowH).then(rowBg(idx)), verticalAlignment = Alignment.CenterVertically) {
                        r.stats.forEachIndexed { i, v ->
                            val num = parseNum(v)
                            val lead = playing.size > 1 && leaderEligible(t, t.labels.getOrElse(i) { "" }) && maxes.getOrNull(i)?.let { m -> m > 0 && num == m } == true
                            val key = t.labels.getOrNull(i) in keys
                            StatCell(v, colWs.getOrElse(i) { 40.dp }, valueStyle, tc,
                                lead = lead, key = key, sorted = i == sortCol, zero = isZeroStat(v),
                                pm = if (i == plusMinus) num else null,
                                bar = if (key && num != null && (maxes[i] ?: 0f) > 0f) (num / maxes[i]!!).coerceIn(0f, 1f) else null,
                                onLongPress = onCellLongPress?.let { f -> { f(r, i) } })
                        }
                    }
                }
                t.totals?.let { tot ->
                    Row(Modifier.height(rowH).background(Dez.Card2), verticalAlignment = Alignment.CenterVertically) {
                        tot.forEachIndexed { i, v -> Text(v, style = valueStyle.copy(fontWeight = FontWeight.Bold), color = if (isZeroStat(v)) Dez.Sub else Dez.Text, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(colWs.getOrElse(i) { 40.dp })) }
                    }
                }
            }
        }
        if (dnp.isNotEmpty()) {
            Text("DNP  " + dnp.joinToString(" · ") { "${it.shortName}${it.reason?.let { r -> " (${r.lowercase().replaceFirstChar { c -> c.uppercase() }})" } ?: ""}" },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = Dez.Faint, maxLines = if (dense) 1 else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(if (dense) dnpH else Dp.Unspecified).padding(horizontal = 14.dp, vertical = if (dense) 6.dp else 12.dp))
        }
        Spacer(Modifier.height(if (dense) 4.dp else 8.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatCell(v: String, w: Dp, style: TextStyle, tc: Color, lead: Boolean, key: Boolean, sorted: Boolean, zero: Boolean, pm: Float?, bar: Float?, onLongPress: (() -> Unit)? = null) {
    val color = when {
        lead -> Color.White
        pm != null && pm > 0 -> Dez.Win
        pm != null && pm < 0 -> Color(0xFFFF6B60)
        zero -> Dez.Faint
        key || sorted -> Dez.Text
        else -> Dez.Text.copy(alpha = .8f)
    }
    Box(Modifier.width(w).fillMaxHeight().then(if (key) Modifier.background(Color.White.copy(alpha = .035f)) else Modifier)
        .then(if (onLongPress != null) Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress) else Modifier), contentAlignment = Alignment.Center) {
        Text(v, style = style.copy(fontWeight = if (lead || key || sorted) FontWeight.Bold else FontWeight.Normal), color = color, textAlign = TextAlign.Center, maxLines = 1,
            softWrap = false, overflow = TextOverflow.Visible,
            modifier = if (lead) Modifier.clip(RoundedCornerShape(7.dp)).background(tc.copy(alpha = .38f)).padding(horizontal = if (v.length > 5) 3.dp else 6.dp, vertical = 1.dp) else Modifier)
        if (bar != null && bar > 0f) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).width(w * .6f).height(2.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .06f))) {
            Box(Modifier.fillMaxWidth(bar).fillMaxHeight().background(tc.copy(alpha = .85f)))
        }
    }
}

@Composable
private fun SubHeader(text: String) {
    Box(Modifier.height(subH).padding(start = 14.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Dez.Faint)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeaderCell(text: String, w: Dp, align: TextAlign, dense: Boolean, selected: Boolean = false, key: Boolean = false, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null) {
    val c by animateColorAsState(when { selected -> Dez.Accent; key -> Dez.Text; else -> Dez.Sub }, label = "hc")
    Box(Modifier.width(w).height(if (dense) headerH else 30.dp)
        .then(if (key) Modifier.background(Color.White.copy(alpha = .035f)) else Modifier)
        .then(if (onClick != null || onLongClick != null) Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick) else Modifier),
        contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = c, textAlign = align, maxLines = 1, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun NameCell(r: StatRow, w: Dp, h: Dp, wide: Boolean, dense: Boolean, short: Boolean, tag: String?, tc: Color, bg: Modifier, onClick: (() -> Unit)?, heat: com.dez.sports.data.Heat? = null) {
    Row(Modifier.width(w).height(h).then(bg).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (wide && r.headshot != null && !short) {
            AsyncImage(r.headshot, null, Modifier.size(if (dense) (h.value - 6).coerceAtMost(26f).dp else 30.dp).clip(CircleShape).background(Dez.Card2))
            Spacer(Modifier.width(8.dp))
        }
        val name = if (wide && !short) r.name else r.shortName.let { sn ->
            // Some feeds send the full name as the short name – make our own "J. Williams".
            if (sn.length > 13 && sn.contains(' ')) "${sn.first()}. ${sn.substringAfter(' ')}" else sn
        }
        val meta = listOfNotNull(r.position, r.jersey?.let { "#$it" }).joinToString(" ")
        if (dense) {
            // One line: name, then position in faint text — saves a full row of height.
            Text(buildAnnotatedString {
                append(name)
                if (r.position != null) withStyle(SpanStyle(color = Dez.Faint, fontSize = 11.sp)) { append("  ${r.position}") }
            }, style = MaterialTheme.typography.bodyMedium, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        } else Column(Modifier.weight(1f, fill = false)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = Dez.Faint, maxLines = 1)
        }
        if (heat != null) { Spacer(Modifier.width(3.dp)); HeatIcon(heat, 12) }
        tag?.let {
            Spacer(Modifier.width(5.dp))
            Text(it, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Black), color = if (it == "TD") Color.Black else Color.White,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (it == "TD") Dez.Gold else tc.copy(alpha = .7f)).padding(horizontal = 4.dp, vertical = 1.dp))
        }
    }
}

private fun parseNum(s: String): Float? {
    if (s.isEmpty() || s == "--" || s == "-") return null
    val first = s.substringBefore("-").substringBefore("/").removePrefix("+")
    if (s.contains(":")) return s.split(":").let { (it[0].toFloatOrNull() ?: 0f) * 60 + (it.getOrNull(1)?.toFloatOrNull() ?: 0f) }
    return if (s.startsWith("-") && s.length > 1) s.toFloatOrNull() else first.toFloatOrNull()
}

/** Mirrored bar comparison of team stats. */
@Composable
fun TeamStatsCompare(game: Game, lines: List<TeamStatLine>) {
    val (awayC, homeC) = game.colors()
    Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(game.away.team, 22.dp); Spacer(Modifier.weight(1f))
            Text("TEAM STATS", style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
            Spacer(Modifier.weight(1f)); TeamLogo(game.home.team, 22.dp)
        }
        lines.forEach { l ->
            val a = parseNum(l.away); val h = parseNum(l.home)
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(l.away, style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = if ((a ?: 0f) >= (h ?: 0f)) Dez.Text else Dez.Sub, modifier = Modifier.width(70.dp))
                    Text(l.label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium), color = Dez.Sub, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(l.home, style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = if ((h ?: 0f) >= (a ?: 0f)) Dez.Text else Dez.Sub, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
                }
                if (a != null && h != null && a + h > 0 && a >= 0 && h >= 0) {
                    val fa by animateFloatAsState(a / (a + h), tween(900), label = "cmp")
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                            Box(Modifier.fillMaxWidth(fa.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(awayC))
                        }
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                            Box(Modifier.fillMaxWidth((1 - fa).coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(homeC))
                        }
                    }
                }
            }
        }
    }
}
