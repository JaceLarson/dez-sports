package com.dez.sports.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dez.sports.data.Distribution
import com.dez.sports.data.ordinal
import com.dez.sports.ui.theme.Dez

/** Long-press answer to "is this number good?": rank, percentile and the whole league as a dot strip. */
@Composable
fun StatContextDialog(accent: Color, loader: suspend () -> Distribution?, onDismiss: () -> Unit) {
    var state by remember { mutableStateOf<Result<Distribution?>?>(null) }
    LaunchedEffect(loader) { state = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { loader() } } }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Dez.Card).padding(18.dp)) {
            val d = state?.getOrNull()
            when {
                state == null -> { Text("Comparing with the league…", style = MaterialTheme.typography.bodyMedium, color = Dez.Sub); Spacer(Modifier.height(8.dp)); SkeletonCards(1) }
                d == null -> Text("No league comparison for this stat.", style = MaterialTheme.typography.bodyMedium, color = Dez.Sub)
                else -> DistributionView(d, accent)
            }
            Text("Close", style = MaterialTheme.typography.labelLarge, color = Color(0xFF64D2FF), modifier = Modifier.align(Alignment.End).padding(top = 12.dp).clickable(onClick = onDismiss).padding(6.dp))
        }
    }
}

@Composable
private fun DistributionView(d: Distribution, accent: Color) {
    val pct = d.percentile
    val c = when { pct == null -> accent; pct >= 67 -> Dez.Win; pct <= 33 -> Dez.Live; else -> Color(0xFFFFB340) }
    Text(d.subject.uppercase(), style = MaterialTheme.typography.labelMedium, color = Dez.Sub)
    Text(d.title, style = MaterialTheme.typography.titleMedium, color = Dez.Text)
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(d.display, style = MaterialTheme.typography.displaySmall.merge(TabularNums).copy(fontWeight = FontWeight.Black), color = Dez.Text)
        Spacer(Modifier.width(12.dp))
        Column {
            if (d.rank > 0) Text("${ordinal(d.rank)} ${if (d.descending) "highest" else "lowest"} of ${d.of}", style = MaterialTheme.typography.titleSmall, color = c)
            else Text("Not in the qualified ranks", style = MaterialTheme.typography.titleSmall, color = Dez.Sub)
            Text(d.population + (pct?.let { " · ${ordinal(it)} percentile" } ?: ""), style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
        }
    }
    Spacer(Modifier.height(14.dp))
    DotStrip(d, c)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(if (d.descending) "lowest" else "highest", style = MaterialTheme.typography.labelSmall, color = Dez.Faint, modifier = Modifier.weight(1f))
        Text("▏avg %.1f".format(d.average), style = MaterialTheme.typography.labelSmall, color = Dez.Faint, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(if (d.descending) "highest" else "lowest", style = MaterialTheme.typography.labelSmall, color = Dez.Faint, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
    if (d.top.isNotEmpty()) {
        Text("LEAGUE BEST", style = MaterialTheme.typography.labelSmall, color = Dez.Faint, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
        d.top.forEachIndexed { i, (name, v) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text("${i + 1}", style = MaterialTheme.typography.labelMedium.merge(TabularNums), color = Dez.Sub, modifier = Modifier.width(18.dp))
                Text(name, style = MaterialTheme.typography.labelLarge, color = Dez.Text, modifier = Modifier.weight(1f))
                Text(v, style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = Dez.Text)
            }
        }
    }
}

/** Every player/team as a dot from worst (left) to best (right), stacked where they bunch up; the subject glows. */
@Composable
private fun DotStrip(d: Distribution, c: Color) {
    val anim = remember(d) { Animatable(0f) }
    LaunchedEffect(d) { anim.animateTo(1f, tween(900)) }
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val lo = d.values.min(); val hi = d.values.max()
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        fun x(v: Double): Float { val f = ((v - lo) / span).toFloat(); return size.width * (if (d.descending) f else 1 - f) }
        val bins = HashMap<Int, Int>()
        val r = 3.2f
        d.values.forEach { v ->
            val px = x(v); val b = (px / (r * 2.2f)).toInt()
            val n = bins.merge(b, 1, Int::plus)!!
            val y = size.height - r - (n - 1) * r * 2.1f * anim.value
            if (y > r) drawCircle(Color.White.copy(alpha = .22f), r, Offset(px, y))
        }
        val ax = x(d.average)
        drawLine(Color.White.copy(alpha = .45f), Offset(ax, 0f), Offset(ax, size.height), strokeWidth = 2f)
        val sx = x(d.value)
        drawLine(c, Offset(sx, 0f), Offset(sx, size.height), strokeWidth = 3f)
        drawCircle(c, 9f, Offset(sx, size.height - 9f))
        drawCircle(Color.White, 9f, Offset(sx, size.height - 9f), style = Stroke(2.5f))
    }
}
