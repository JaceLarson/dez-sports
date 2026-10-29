package com.dez.sports.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.dez.sports.data.Favorites
import com.dez.sports.data.ForYouRepository
import com.dez.sports.model.Game
import com.dez.sports.model.GameState
import com.dez.sports.model.League
import com.dez.sports.ui.components.colors
import com.dez.sports.ui.components.liveLabel
import com.dez.sports.ui.components.localDate
import com.dez.sports.ui.components.localDay
import com.dez.sports.ui.components.localTime
import com.dez.sports.ui.theme.darken
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.time.LocalDate

class WidgetSide(val abbr: String, val score: String?, val record: String?, val logo: Bitmap?, val dim: Boolean)

class WidgetGame(
    val id: String,
    val league: League,
    val label: String,          // "JAZZ · TODAY"
    val state: GameState,
    val status: String,         // "Q3 5:21", "7:00 PM", "FINAL"
    val sub: String?,           // TV channel or date
    val away: WidgetSide,
    val home: WidgetSide,
    val background: Bitmap,
)

class WidgetSnapshot(val games: List<WidgetGame>, val updatedAt: Long) {
    val hasLive get() = games.any { it.state == GameState.LIVE }
}

/** Loads the favourite-team games for the widget and keeps the last good snapshot in memory. */
object WidgetData {
    private val state = kotlinx.coroutines.flow.MutableStateFlow<WidgetSnapshot?>(null)
    /** Observed by the widget UI so a background refresh recomposes it. */
    val flow: kotlinx.coroutines.flow.StateFlow<WidgetSnapshot?> = state
    val latest: WidgetSnapshot? get() = state.value

    suspend fun get(context: Context): WidgetSnapshot? = latest ?: refresh(context)

    suspend fun refresh(context: Context): WidgetSnapshot? = runCatching {
        withTimeout(30_000) { build(context) }
    }.getOrNull()?.also { state.value = it } ?: latest

    private suspend fun build(context: Context): WidgetSnapshot = coroutineScope {
        val leagues = Favorites.teams.value.map { it.league }.toSet()
        val feed = ForYouRepository.load(leagues)
        val today = LocalDate.now()
        val cards = feed.favorites.filter { it.featured != null }
            .sortedWith(compareBy({ order(it.featured!!, today) }, { it.featured!!.dateIso }))
        val games = cards.map { c ->
            async {
                val g = c.featured!!
                val (awayC, homeC) = g.colors()
                WidgetGame(
                    id = g.id, league = g.league,
                    label = "${c.team.name.uppercase()} · ${c.featuredLabel}",
                    state = g.state,
                    status = when (g.state) {
                        GameState.LIVE -> liveLabel(g)
                        GameState.PRE -> if (localDate(g.dateIso) == today) localTime(g.dateIso) else "${localDay(g.dateIso).substringBefore(",")} ${localTime(g.dateIso)}"
                        GameState.POST -> g.statusShort.replace("Final", "FINAL")
                    },
                    sub = when (g.state) {
                        GameState.POST -> localDay(g.dateIso)
                        else -> g.broadcasts.firstOrNull()
                    },
                    away = side(context, g, home = false),
                    home = side(context, g, home = true),
                    background = gradient(awayC.darken(.25f).toArgb(), homeC.darken(.25f).toArgb()),
                )
            }
        }.awaitAll()
        WidgetSnapshot(games, System.currentTimeMillis())
    }

    /** Live first, then today's games, then recent finals, then upcoming. */
    private fun order(g: Game, today: LocalDate) = when {
        g.state == GameState.LIVE -> 0
        localDate(g.dateIso) == today -> 1
        g.state == GameState.POST -> 2
        else -> 3
    }

    private suspend fun side(context: Context, g: Game, home: Boolean): WidgetSide {
        val s = if (home) g.home else g.away
        val o = if (home) g.away else g.home
        val lost = g.state == GameState.POST && (s.score?.toDoubleOrNull() ?: 0.0) < (o.score?.toDoubleOrNull() ?: 0.0)
        return WidgetSide(s.team.abbreviation, s.score, s.record, logo(context, s.team.darkLogo ?: s.team.logo), lost)
    }

    private suspend fun logo(context: Context, url: String?): Bitmap? {
        if (url == null) return null
        val req = ImageRequest.Builder(context).data(url).size(112).allowHardware(false).build()
        return (SingletonImageLoader.get(context).execute(req) as? SuccessResult)?.image?.toBitmap()
    }

    /** Team-colour fill like the in-app score cards: away colour → near-black → home colour. */
    private fun gradient(away: Int, home: Int): Bitmap {
        val w = 360; val h = 72
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(away, 0xFF111113.toInt(), home), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        }
        Canvas(bmp).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return bmp
    }
}
