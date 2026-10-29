package com.dez.sports.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dez.sports.data.Form
import com.dez.sports.data.Heat
import com.dez.sports.ui.theme.Dez

val HotColor = Color(0xFFFF6B35)
val ColdColor = Color(0xFF5AC8FA)

fun Heat.emoji() = if (this == Heat.HOT) "🔥" else "🧊"

/** A small flame or ice cube that breathes, so a hot streak catches the eye in a list. */
@Composable
fun HeatIcon(heat: Heat?, size: Int = 13, modifier: Modifier = Modifier) {
    heat ?: return
    val t = rememberInfiniteTransition(label = "heat")
    val s = t.animateFloat(.9f, 1.08f, infiniteRepeatable(tween(if (heat == Heat.HOT) 520 else 1400), RepeatMode.Reverse), label = "hs")
    Text(heat.emoji(), fontSize = size.sp, modifier = modifier.graphicsLayer { scaleX = s.value; scaleY = s.value })
}

/** "🔥 HEATING UP · 4-1 in the last 5 …" */
@Composable
fun FormCard(f: Form, modifier: Modifier = Modifier) {
    val c = when (f.heat) { Heat.HOT -> HotColor; Heat.COLD -> ColdColor; null -> Dez.Sub }
    val title = when (f.heat) { Heat.HOT -> "HEATING UP"; Heat.COLD -> "ICE COLD"; null -> "RECENT FORM" }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Brush.horizontalGradient(listOf(c.copy(alpha = .22f), Dez.Card))).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (f.heat != null) { HeatIcon(f.heat, 24); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = c)
            Text(f.headline, style = MaterialTheme.typography.titleSmall, color = Dez.Text)
            Text(f.detail, style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
        }
    }
}
