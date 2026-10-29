package com.dez.sports.ui.stats

import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.dez.sports.data.PlayerApi
import com.dez.sports.model.Athlete
import com.dez.sports.model.GameLog
import com.dez.sports.model.League
import com.dez.sports.ui.components.TabularNums
import com.dez.sports.ui.components.TeamLogo
import com.dez.sports.ui.components.secondColor
import com.dez.sports.ui.theme.Dez
import com.dez.sports.ui.theme.darken
import com.dez.sports.ui.theme.lighten
import com.dez.sports.ui.theme.teamColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class CompareSide(val athlete: Athlete, val log: GameLog?)

class CompareViewModel(handle: SavedStateHandle) : ViewModel() {
    val league = MutableStateFlow(League.valueOf(handle["league"]!!))
    val sides = MutableStateFlow<List<CompareSide?>>(listOf(null, null))
    val loading = MutableStateFlow(listOf(false, false))

    init {
        handle.get<String>("a")?.takeIf { it.isNotBlank() }?.let { set(0, league.value, it) }
        handle.get<String>("b")?.takeIf { it.isNotBlank() }?.let { set(1, league.value, it) }
    }

    fun set(slot: Int, l: League, id: String) {
        league.value = l
        loading.value = loading.value.toMutableList().also { it[slot] = true }
        viewModelScope.launch(Dispatchers.Default) {
            val a = runCatching { PlayerApi.athlete(l, id) }.getOrNull()
            val log = runCatching { PlayerApi.gameLog(l, id) }.getOrNull()
            sides.value = sides.value.toMutableList().also { it[slot] = a?.let { at -> CompareSide(at, log) } }
            loading.value = loading.value.toMutableList().also { it[slot] = false }
        }
    }
}

private fun num(s: String?) = s?.replace(",", "")?.toFloatOrNull()

/** Labels both players share, with each one's season average (first section's averages row). */
private fun sharedAverages(a: CompareSide, b: CompareSide): List<Triple<String, String, String>> {
    fun avgMap(s: CompareSide): Map<String, String> {
        val log = s.log ?: return emptyMap()
        val avg = log.sections.firstOrNull()?.averages ?: return emptyMap()
        return log.labels.zip(avg).distinctBy { it.first }.toMap()
    }
    val am = avgMap(a); val bm = avgMap(b)
    val fromLog = am.keys.filter { it in bm }.map { Triple(it, am.getValue(it), bm.getValue(it)) }
    if (fromLog.isNotEmpty()) return fromLog
    val sa = a.athlete.summary.associate { it.label to it.value }; val sb = b.athlete.summary.associate { it.label to it.value }
    return sa.keys.filter { it in sb }.map { Triple(it, sa.getValue(it), sb.getValue(it)) }
}

@Composable
fun CompareScreen(onBack: () -> Unit, vm: CompareViewModel = viewModel()) {
    val league by vm.league.collectAsState()
    val sides by vm.sides.collectAsState()
    val loading by vm.loading.collectAsState()
    var picking by remember { mutableStateOf(-1) }
    val a = sides[0]; val b = sides[1]
    val ca = a?.athlete?.team?.let { teamColor(it.color, it.altColor) } ?: Dez.Accent
    val cb = b?.athlete?.team?.let { t -> if (t.id == a?.athlete?.team?.id) teamColor(t.color, t.altColor).lighten(.45f) else secondColor(ca, t) } ?: Color(0xFFFF9F0A)

    LazyColumn(Modifier.fillMaxSize().background(Dez.Bg), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(ca.darken(.3f), Dez.Bg, cb.darken(.3f))))) {
                Column(Modifier.statusBarsPadding().padding(bottom = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(8.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .25f)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                        }
                        Text("COMPARE · ${league.shortLabel}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .8f))
                    }
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayerSlot(a, loading[0], Modifier.weight(1f)) { picking = 0 }
                        Text("VS", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black), color = Color.White.copy(alpha = .6f), modifier = Modifier.padding(horizontal = 8.dp))
                        PlayerSlot(b, loading[1], Modifier.weight(1f)) { picking = 1 }
                    }
                }
            }
        }
        if (a != null && b != null) {
            val rows = sharedAverages(a, b)
            item(key = "tape-${a.athlete.id}-${b.athlete.id}") { Box(Modifier.padding(horizontal = 12.dp)) { TaleOfTheTape(league, a.athlete, b.athlete, rows, ca, cb) } }
            if (rows.isNotEmpty()) item { Box(Modifier.padding(horizontal = 12.dp)) { Averages(rows, ca, cb) } }
            item { Box(Modifier.padding(horizontal = 12.dp)) { OverlayChart(a, b, ca, cb) } }
        } else item {
            Text("Tap a slot to pick players. You can compare anyone in the same sport.", style = MaterialTheme.typography.bodyMedium, color = Dez.Sub, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
        }
    }
    if (picking >= 0) PlayerSearchSheet(null, onDismiss = { picking = -1 }) { p -> vm.set(picking, p.league, p.id); picking = -1 }
}

@Composable
private fun PlayerSlot(s: CompareSide?, loading: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (s == null) {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Text(if (loading) "Loading…" else "Pick player", style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.padding(top = 8.dp))
        } else {
            AsyncImage(s.athlete.headshot, s.athlete.name, Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = .2f)), contentScale = ContentScale.Crop)
            Text(s.athlete.name, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                s.athlete.team?.let { TeamLogo(it, 16.dp); Spacer(Modifier.width(4.dp)) }
                Text(listOfNotNull(s.athlete.position, s.athlete.team?.abbreviation).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .7f))
            }
            Text("Change", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .5f))
        }
    }
}

@Composable
private fun Averages(rows: List<Triple<String, String, String>>, ca: Color, cb: Color) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("SEASON AVERAGES", style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
        rows.forEach { (label, va, vb) ->
            val na = num(va); val nb = num(vb)
            // Lower is better for turnovers/fouls/ERA-style stats.
            val lowerBetter = label in setOf("TO", "PF", "ERA", "WHIP", "GAA", "INT", "FUM", "L", "PIM", "BB")
            val aWins = na != null && nb != null && (if (lowerBetter) na < nb else na > nb)
            val bWins = na != null && nb != null && (if (lowerBetter) nb < na else nb > na)
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(va, style = MaterialTheme.typography.titleMedium.merge(TabularNums).copy(fontWeight = if (aWins) FontWeight.Black else FontWeight.Normal), color = if (aWins) ca.lighten(.3f) else Dez.Sub, modifier = Modifier.width(90.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, color = Dez.Sub, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    Text(vb, style = MaterialTheme.typography.titleMedium.merge(TabularNums).copy(fontWeight = if (bWins) FontWeight.Black else FontWeight.Normal), color = if (bWins) cb.lighten(.3f) else Dez.Sub, textAlign = TextAlign.End, modifier = Modifier.width(90.dp))
                }
                if (na != null && nb != null && na + nb > 0 && na >= 0 && nb >= 0) {
                    val fa by animateFloatAsState(na / (na + nb), tween(900), label = "cmp")
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) { Box(Modifier.fillMaxWidth(fa.coerceIn(.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(ca)) }
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) { Box(Modifier.fillMaxWidth((1 - fa).coerceIn(.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(cb)) }
                    }
                }
            }
        }
    }
}

/** Both players' game-by-game line for one stat, by game number. */
@Composable
private fun OverlayChart(a: CompareSide, b: CompareSide, ca: Color, cb: Color) {
    val la = a.log ?: return; val lb = b.log ?: return
    val shared = la.labels.withIndex().filter { (i, l) ->
        val j = lb.labels.indexOf(l)
        j >= 0 && la.sections.firstOrNull()?.blocks?.flatMap { it.rows }?.all { num(it.stats.getOrNull(i)) != null } == true &&
            lb.sections.firstOrNull()?.blocks?.flatMap { it.rows }?.all { num(it.stats.getOrNull(j)) != null } == true
    }.map { it.value }.distinct()
    if (shared.isEmpty()) return
    var label by remember(shared) { mutableStateOf(shared.firstOrNull { it in setOf("PTS", "YDS", "G", "H") } ?: shared.first()) }
    fun series(log: GameLog): List<Float> {
        val i = log.labels.indexOf(label)
        return log.sections.firstOrNull()?.blocks?.flatMap { it.rows }?.sortedBy { it.dateIso }?.map { num(it.stats.getOrNull(i)) ?: 0f }.orEmpty()
    }
    val sa = series(la); val sb = series(lb)
    val max = (sa + sb).maxOrNull()?.coerceAtLeast(1f) ?: 1f
    val anim = remember(label) { Animatable(0f) }
    LaunchedEffect(label) { anim.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dez.Card).padding(14.dp)) {
        Text("GAME BY GAME", style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shared) { l ->
                Text(l, style = MaterialTheme.typography.labelMedium, color = if (l == label) Dez.Text else Dez.Sub,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (l == label) Dez.Card2 else Color.Transparent).clickable { label = l }.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(170.dp)) {
            val n = maxOf(sa.size, sb.size).coerceAtLeast(2)
            fun path(s: List<Float>) = Path().apply {
                s.forEachIndexed { i, v -> val p = Offset(size.width * i / (n - 1), size.height * (1 - v / max)); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            }
            listOf(.25f, .5f, .75f).forEach { y -> drawLine(Dez.Hairline, Offset(0f, size.height * y), Offset(size.width, size.height * y)) }
            clipRect(right = size.width * anim.value) {
                listOf(sa to ca, sb to cb).forEach { (s, c) ->
                    if (s.isEmpty()) return@forEach
                    drawPath(path(s), c, style = Stroke(5f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    s.forEachIndexed { i, v -> drawCircle(c, 5f, Offset(size.width * i / (n - 1), size.height * (1 - v / max))) }
                    val avg = s.average().toFloat()
                    drawLine(c.copy(alpha = .6f), Offset(0f, size.height * (1 - avg / max)), Offset(size.width, size.height * (1 - avg / max)), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Legend(a.athlete.name, ca, sa, Modifier.weight(1f)); Legend(b.athlete.name, cb, sb, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Legend(name: String, c: Color, s: List<Float>, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(6.dp))
        Text("$name · ${if (s.isEmpty()) "–" else "%.1f avg".format(s.average())}", style = MaterialTheme.typography.labelMedium, color = Dez.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
