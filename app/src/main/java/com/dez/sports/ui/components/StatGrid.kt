package com.dez.sports.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dez.sports.ui.theme.Dez

/*
 * Shared look for every stats table (box scores, game logs, splits, career, standings):
 * zebra rows, dimmed zeros, team-colour pills for standout numbers and a tinted totals row.
 */

val StripeColor = Color.White.copy(alpha = .028f)

fun Modifier.stripe(index: Int) = if (index % 2 == 1) background(StripeColor) else this

/** Team colours like navy or black vanish on the dark cards; lift them until they read. */
fun Color.readable(): Color = if (luminance() < .12f) lerp(this, Color.White, .35f) else this

/** "0", "0-0", "0/0", "0.0", ".000", "--" all carry no information – they get dimmed. */
fun isZeroStat(v: String) = v.isEmpty() || v.all { it == '0' || it == '-' || it == '/' || it == '.' || it == ':' }

/** Stats where a bigger number is worse – never highlighted as a "best". */
val LowerIsBetter = setOf("ERA", "WHIP", "TO", "TOV", "PF", "GAA", "GA", "L", "FUM", "LOST", "PIM", "INT%")

enum class Emphasis { NONE, PILL, HOT, COLD }

@Composable
fun GridValue(v: String, w: Dp, accent: Color, emphasis: Emphasis = Emphasis.NONE, bold: Boolean = false, base: Color = Dez.Text.copy(alpha = .82f)) {
    val color = when {
        emphasis == Emphasis.PILL -> Color.White
        emphasis == Emphasis.HOT -> accent.readable().let { lerp(it, Color.White, .25f) }
        emphasis == Emphasis.COLD || isZeroStat(v) -> Dez.Faint
        else -> base
    }
    Box(Modifier.width(w).fillMaxHeight(), contentAlignment = Alignment.Center) {
        Text(v, style = MaterialTheme.typography.bodyMedium.merge(TabularNums).copy(fontWeight = if (bold || emphasis == Emphasis.PILL || emphasis == Emphasis.HOT) FontWeight.Bold else FontWeight.Normal),
            color = color, textAlign = TextAlign.Center, maxLines = 1,
            modifier = if (emphasis == Emphasis.PILL) Modifier.clip(RoundedCornerShape(7.dp)).background(accent.readable().copy(alpha = .42f)).padding(horizontal = 6.dp, vertical = 1.dp) else Modifier)
    }
}

/** Header row underline in the team colour. */
@Composable
fun AccentRule(accent: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(accent.readable().copy(alpha = .45f)))
}
