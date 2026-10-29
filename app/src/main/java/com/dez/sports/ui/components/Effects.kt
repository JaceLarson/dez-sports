package com.dez.sports.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val maxLife: Float, val size: Float, val color: Color, var rot: Float = 0f, val spin: Float = 0f)

/** Rising flame particles – shown over a team that's on a scoring run. [intensity] 0..1 scales the fire. */
@Composable
fun Flames(modifier: Modifier, intensity: Float = 1f) {
    val particles = remember { mutableStateListOf<Particle>() }
    var frame by remember { mutableLongStateOf(0L) }
    var bounds by remember { mutableStateOf(Size.Zero) }
    val palette = remember { listOf(Color(0xFFFFD60A), Color(0xFFFF9F0A), Color(0xFFFF6B1A), Color(0xFFFF3B30)) }
    LaunchedEffect(intensity) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                val w = bounds.width; val h = bounds.height
                if (w > 0) {
                    repeat((intensity * 3).toInt().coerceAtLeast(1)) {
                        particles += Particle(
                            x = Random.nextFloat() * w, y = h, vx = (Random.nextFloat() - .5f) * 30f, vy = -(60f + Random.nextFloat() * 120f) * (0.6f + intensity),
                            life = 0f, maxLife = 0.6f + Random.nextFloat() * 0.6f, size = 3f + Random.nextFloat() * 7f * (0.5f + intensity), color = palette.random(),
                        )
                    }
                }
                val it = particles.iterator()
                while (it.hasNext()) {
                    val p = it.next()
                    p.life += dt; p.x += p.vx * dt; p.y += p.vy * dt; p.vx += (Random.nextFloat() - .5f) * 40f * dt
                    if (p.life > p.maxLife) it.remove()
                }
                frame = now
            }
        }
    }
    Canvas(modifier) {
        bounds = size
        frame // read to redraw each frame
        particles.forEach { p ->
            val t = p.life / p.maxLife
            drawCircle(p.color.copy(alpha = (1 - t) * 0.85f), radius = p.size * (1 - t * 0.6f), center = Offset(p.x, p.y))
        }
    }
}

/** One-shot confetti burst in team colours (a favourite team's win, a touchdown…). */
@Composable
fun Confetti(modifier: Modifier, colors: List<Color>, key: Any?) {
    val particles = remember { mutableStateListOf<Particle>() }
    var frame by remember { mutableLongStateOf(0L) }
    var bounds by remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(key, bounds.width > 0) {
        if (bounds.width <= 0f) return@LaunchedEffect
        particles.clear()
        val w = bounds.width
        repeat(140) {
            val a = (-PI / 2 + (Random.nextFloat() - .5f) * 2.2f).toFloat()
            val speed = 500f + Random.nextFloat() * 700f
            particles += Particle(w / 2 + (Random.nextFloat() - .5f) * w * .3f, bounds.height * .35f, cos(a) * speed, sin(a) * speed, 0f,
                2.2f + Random.nextFloat() * 1.4f, 6f + Random.nextFloat() * 8f, (colors + Color.White).random(), Random.nextFloat() * 360f, (Random.nextFloat() - .5f) * 720f)
        }
        var last = 0L
        while (particles.isNotEmpty()) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                val it = particles.iterator()
                while (it.hasNext()) {
                    val p = it.next()
                    p.life += dt; p.vy += 900f * dt; p.vx *= 0.985f; p.vy *= 0.985f
                    p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.spin * dt
                    if (p.life > p.maxLife) it.remove()
                }
                frame = now
            }
        }
    }
    Canvas(modifier) {
        bounds = size
        frame
        particles.forEach { p ->
            val fade = (1 - (p.life / p.maxLife)).coerceIn(0f, 1f)
            rotate(p.rot, Offset(p.x, p.y)) {
                drawRect(p.color.copy(alpha = fade), Offset(p.x - p.size / 2, p.y - p.size / 4), Size(p.size, p.size / 2))
            }
        }
    }
}
