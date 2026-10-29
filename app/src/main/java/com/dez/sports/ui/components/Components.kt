package com.dez.sports.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dez.sports.data.EspnApi
import com.dez.sports.model.Team
import com.dez.sports.ui.theme.Dez
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TeamLogo(team: Team, size: Dp, modifier: Modifier = Modifier) {
    val url = team.darkLogo ?: team.logo
    if (url == null) {
        Box(modifier.size(size).clip(CircleShape).background(Dez.Card2), contentAlignment = Alignment.Center) {
            Text(team.abbreviation.take(3), style = MaterialTheme.typography.labelSmall, color = Dez.Sub)
        }
    } else {
        AsyncImage(model = url, contentDescription = team.displayName, modifier = modifier.size(size))
    }
}

@Composable
fun LiveDot(size: Dp = 7.dp, color: Color = Dez.Live) {
    val t = rememberInfiniteTransition(label = "live")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearOutSlowInEasing)), label = "pulse")
    Canvas(Modifier.size(size * 2.2f)) {
        val r = size.toPx() / 2
        drawCircle(color.copy(alpha = (1 - pulse) * 0.5f), radius = r + r * 1.2f * pulse)
        drawCircle(color, radius = r)
    }
}

/** A score that rolls up/down when it changes, like a flip board. */
@Composable
fun RollingScore(value: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = value, modifier = modifier,
        transitionSpec = {
            val up = (targetState.toIntOrNull() ?: 0) >= (initialState.toIntOrNull() ?: 0)
            (slideInVertically(spring(dampingRatio = 0.7f, stiffness = 300f)) { if (up) it else -it } + fadeIn(tween(200)))
                .togetherWith(slideOutVertically(tween(220)) { if (up) -it else it } + fadeOut(tween(160)))
        }, label = "score",
    ) { v -> Text(v, style = style, color = color, maxLines = 1) }
}

/** Short glow when a value changes – used to flash a card when someone scores. */
@Composable
fun rememberFlash(key: Any?): Float {
    val anim = remember { Animatable(0f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(key) {
        if (first) { first = false; return@LaunchedEffect }
        anim.snapTo(1f); anim.animateTo(0f, tween(1600, easing = FastOutSlowInEasing))
    }
    return anim.value
}

@Composable
fun Pill(text: String, bg: Color = Dez.Card2, fg: Color = Dez.Sub, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = Dez.Text, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

// ---------------------------------------------------------------- baseball

@Composable
fun BaseDiamond(first: Boolean, second: Boolean, third: Boolean, size: Dp = 30.dp, on: Color = Dez.Gold) {
    val f by animateColorAsState(if (first) on else Dez.Faint.copy(alpha = .5f), label = "1b")
    val s by animateColorAsState(if (second) on else Dez.Faint.copy(alpha = .5f), label = "2b")
    val t by animateColorAsState(if (third) on else Dez.Faint.copy(alpha = .5f), label = "3b")
    Canvas(Modifier.size(size)) {
        val b = this.size.width * 0.3f
        fun base(cx: Float, cy: Float, c: Color) = rotate(45f, Offset(cx, cy)) {
            drawRect(c, topLeft = Offset(cx - b / 2, cy - b / 2), size = Size(b, b))
        }
        val w = this.size.width; val h = this.size.height
        base(w / 2, h * 0.27f, s)
        base(w * 0.23f, h * 0.62f, t)
        base(w * 0.77f, h * 0.62f, f)
    }
}

@Composable
fun CountDots(label: String, filled: Int, total: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Dez.Sub, modifier = Modifier.width(12.dp))
        repeat(total) { i ->
            val c by animateColorAsState(if (i < filled) color else Dez.Faint.copy(alpha = .4f), label = "dot")
            Box(Modifier.padding(horizontal = 1.5.dp).size(6.dp).clip(CircleShape).background(c))
        }
    }
}

// ---------------------------------------------------------------- football

/** A mini field with the ball spot. yardLine is 0..100 from the away team's end zone (left). */
@Composable
fun FieldStrip(yardLine: Int?, possessionLeft: Boolean?, redZone: Boolean, leftColor: Color, rightColor: Color, modifier: Modifier = Modifier) {
    val target = ((yardLine ?: 50).coerceIn(0, 100)) / 100f
    val pos by animateFloatAsState(target, spring(stiffness = 120f), label = "ball")
    val glow = rememberInfiniteTransition(label = "rz").animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "rzg")
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val w = size.width; val h = size.height; val ez = w * 0.08f
        drawRoundRect(Color(0xFF1F3A26), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
        drawRect(leftColor.copy(alpha = .8f), Offset(0f, 0f), Size(ez, h))
        drawRect(rightColor.copy(alpha = .8f), Offset(w - ez, 0f), Size(ez, h))
        val field = w - 2 * ez
        for (i in 1..9) {
            val x = ez + field * i / 10f
            drawLine(Color.White.copy(alpha = if (i == 5) .45f else .18f), Offset(x, 2f), Offset(x, h - 2f), strokeWidth = 1.5f)
        }
        if (redZone) {
            val rz = field * 0.2f
            val left = possessionLeft == false
            drawRect(Dez.Live.copy(alpha = glow.value * 0.45f), Offset(if (left) ez else w - ez - rz, 0f), Size(rz, h))
        }
        val bx = ez + field * pos
        val path = Path().apply {
            val r = h * 0.42f
            moveTo(bx - r * 1.4f, h / 2); quadraticTo(bx, h / 2 - r * 1.3f, bx + r * 1.4f, h / 2); quadraticTo(bx, h / 2 + r * 1.3f, bx - r * 1.4f, h / 2); close()
        }
        drawPath(path, Color(0xFFB5651D))
        drawLine(Color.White, Offset(bx - h * .2f, h / 2), Offset(bx + h * .2f, h / 2), strokeWidth = 1.5f)
    }
}

@Composable
fun PossessionBall(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 10.dp, height = 7.dp)) {
        val path = Path().apply {
            moveTo(0f, size.height / 2); quadraticTo(size.width / 2, -size.height * .3f, size.width, size.height / 2)
            quadraticTo(size.width / 2, size.height * 1.3f, 0f, size.height / 2); close()
        }
        drawPath(path, Color(0xFFC8702A))
    }
}

// ---------------------------------------------------------------- loading

fun Modifier.shimmer(): Modifier = composed {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x = t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    drawBehind { drawRect(Brush.linearGradient(listOf(Dez.Card, Dez.Card2, Dez.Card), start = Offset(x.value * 800f, 0f), end = Offset(x.value * 800f + 600f, 300f))) }
}

@Composable
fun SkeletonCards(count: Int = 5) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(count) { Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(22.dp)).shimmer()) }
    }
}

/** A failed load, with a way to try again. */
@Composable
fun ErrorState(title: String, sub: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Dez.Sub, textAlign = TextAlign.Center)
        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Dez.Faint, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
        Text("Try again", style = MaterialTheme.typography.labelLarge, color = Dez.Text,
            modifier = Modifier.padding(top = 14.dp).clip(RoundedCornerShape(50)).background(Dez.Card2).clickable(onClick = onRetry).padding(horizontal = 18.dp, vertical = 9.dp))
    }
}

@Composable
fun EmptyState(title: String, sub: String? = null) {
    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Dez.Sub, textAlign = TextAlign.Center)
        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Dez.Faint, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "bar")
    Box(modifier.height(height).clip(RoundedCornerShape(50)).background(Dez.Card2)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(50)).background(color))
    }
}

@Composable
fun RingProgress(fraction: Float, color: Color, size: Dp, stroke: Dp = 5.dp, content: @Composable () -> Unit = {}) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(1100, easing = FastOutSlowInEasing), label = "ring")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            drawArc(Dez.Card2, -90f, 360f, false, style = Stroke(sw), topLeft = Offset(sw / 2, sw / 2), size = Size(this.size.width - sw, this.size.height - sw))
            drawArc(color, -90f, 360f * f, false, style = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round), topLeft = Offset(sw / 2, sw / 2), size = Size(this.size.width - sw, this.size.height - sw))
        }
        content()
    }
}

// ---------------------------------------------------------------- time

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d")

fun localTime(iso: String): String = runCatching {
    Instant.parse(EspnApi.fixIso(iso)).atZone(ZoneId.systemDefault()).format(timeFmt)
}.getOrDefault("")

fun localDay(iso: String): String = runCatching {
    Instant.parse(EspnApi.fixIso(iso)).atZone(ZoneId.systemDefault()).format(dayFmt)
}.getOrDefault("")

fun localDate(iso: String) = runCatching { Instant.parse(EspnApi.fixIso(iso)).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()

val TabularNums = TextStyle(fontFeatureSettings = "tnum")
val ScoreStyle = TextStyle(fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = (-1).sp, fontFeatureSettings = "tnum")

@Composable
fun LeaguePicker(options: List<com.dez.sports.model.League>, selected: com.dez.sports.model.League, onSelect: (com.dez.sports.model.League) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { i ->
            val l = options[i]
            val sel = l == selected
            val bg by animateColorAsState(if (sel) Dez.Text else Dez.Card2, tween(250), label = "lp")
            val fg by animateColorAsState(if (sel) Color.Black else Dez.Text, tween(250), label = "lpfg")
            Text(l.shortLabel, style = MaterialTheme.typography.labelLarge, color = fg,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).clickable { onSelect(l) }.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Composable
fun ScreenTitle(title: String, sub: String? = null) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp)) {
        sub?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = Dez.Sub) }
        Text(title, style = MaterialTheme.typography.headlineLarge, color = Dez.Text)
    }
}
