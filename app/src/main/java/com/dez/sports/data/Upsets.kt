package com.dez.sports.data

import com.dez.sports.model.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** The pregame (closing) line. [spread] is the favourite's margin in points; moneylines are American odds. */
data class Line(val favoriteHome: Boolean, val spread: Double?, val favMoneyline: Int?, val dogMoneyline: Int?)

/**
 * A big underdog that is level or ahead late ([final] = false), or that won ([final] = true).
 * [odds] is how the line reads for the underdog: "+13.5" or "+210".
 */
data class Upset(val game: Game, val dog: Side, val fav: Side, val line: Line, val final: Boolean, val odds: String, val situation: String) {
    /** "UTAH was a 13.5-point underdog" / "UTAH was +210". */
    val pregame get() = if (line.spread != null && game.league.kind != SportKind.HOCKEY && game.league.kind != SportKind.BASEBALL)
        "${dog.team.abbreviation} was a ${fmt(line.spread)}-point underdog" else "${dog.team.abbreviation} was a $odds underdog"

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
}

/**
 * Upset alerts. Rare on purpose: only a real long shot (by the closing line, per sport) that is tied or ahead
 * in the last few minutes, or that actually won.
 */
object Upsets {
    private const val CORE = "https://sports.core.api.espn.com/v2/sports"
    private val lines = ConcurrentHashMap<String, Line>()
    private val noLine = ConcurrentHashMap.newKeySet<String>()

    /** How long a shot the underdog has to be. */
    private fun bigFavorite(league: League, l: Line): Boolean {
        val dogMl = l.dogMoneyline ?: 0
        return when (league) {
            League.CFB -> (l.spread ?: 0.0) >= 12.0 || l.spread == null && dogMl >= 400
            League.NFL -> (l.spread ?: 0.0) >= 9.5 || l.spread == null && dogMl >= 350
            League.NBA -> (l.spread ?: 0.0) >= 10.0 || l.spread == null && dogMl >= 400
            League.CBB -> (l.spread ?: 0.0) >= 12.0 || l.spread == null && dogMl >= 500
            League.NHL, League.MLB -> dogMl >= 200
            else -> false
        }
    }

    /** Seconds left in the period from ESPN's clock ("5:21", "45.3"). */
    private fun secondsLeft(clock: String?): Int? {
        val c = clock?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (':' in c) c.split(':').let { (m, s) -> (m.toIntOrNull() ?: return null) * 60 + (s.toDoubleOrNull() ?: 0.0).toInt() }
        else c.toDoubleOrNull()?.toInt()
    }

    /** Late enough that the result is in doubt, whoever is favoured. Cheap: decides whether to look up the line. */
    fun late(g: Game): Boolean {
        if (g.state != GameState.LIVE) return false
        val secs = secondsLeft(g.clock) ?: Int.MAX_VALUE
        return when (g.league) {
            League.NFL, League.CFB -> g.period >= 4
            League.NBA -> g.period >= 5 || g.period == 4 && secs <= 6 * 60
            League.CBB -> g.period >= 3 || g.period == 2 && secs <= 6 * 60
            League.NHL -> g.period >= 3
            League.MLB -> g.period >= 8
            else -> false
        }
    }

    /** Worth checking at all: a live game in its final stretch, or a finished one. */
    fun candidate(g: Game) = g.league != League.PGA && (late(g) || g.state == GameState.POST && g.away.score != null)

    /** The live alert or final upset for this game, if there is one. */
    suspend fun of(g: Game): Upset? {
        if (!candidate(g)) return null
        val line = line(g) ?: return null
        if (!bigFavorite(g.league, line)) return null
        val fav = if (line.favoriteHome) g.home else g.away
        val dog = if (line.favoriteHome) g.away else g.home
        val ds = dog.score?.toIntOrNull() ?: return null
        val fs = fav.score?.toIntOrNull() ?: return null
        val odds = if (g.league.kind == SportKind.HOCKEY || g.league.kind == SportKind.BASEBALL || line.spread == null)
            line.dogMoneyline?.let { "+$it" } ?: return null else "+${line.spread.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}"

        if (g.state == GameState.POST) {
            if (ds <= fs) return null
            return Upset(g, dog, fav, line, final = true, odds, "Won ${maxOf(ds, fs)}-${minOf(ds, fs)}")
        }

        val d = ds - fs
        val secs = secondsLeft(g.clock) ?: Int.MAX_VALUE
        val alert = when (g.league) {
            // Ahead in the 4th, or tied in the last 8 minutes; overtime always counts.
            League.NFL, League.CFB -> g.period >= 5 || g.period == 4 && (d > 0 || d == 0 && secs <= 8 * 60)
            League.NBA, League.CBB -> d >= 0
            League.NHL -> g.period >= 4 || d > 0
            League.MLB -> d > 0 || g.period >= 10 && d == 0
            else -> false
        }
        if (!alert) return null
        return Upset(g, dog, fav, line, final = false, odds, situation(g, d))
    }

    private fun situation(g: Game, d: Int): String {
        val who = g.let { if (d > 0) "leads by $d" else "tied" }
        val ot = when (g.league) {
            League.NFL, League.CFB, League.NBA -> g.period >= 5
            League.CBB -> g.period >= 3
            League.NHL -> g.period >= 4
            League.MLB -> g.period >= 10
            else -> false
        }
        return when {
            g.league == League.MLB -> "$who in the ${g.statusShort.ifEmpty { "inning ${g.period}" }}"
            ot -> "$who in ${if (g.league == League.NHL && g.period >= 5) "a shootout" else "overtime"}"
            g.clock != null -> "$who with ${g.clock} left"
            else -> who
        }
    }

    /** Closing line from ESPN's odds feed (a few KB). Finished games are cached on disk for good. */
    suspend fun line(g: Game): Line? {
        lines[g.id]?.let { return it }
        if (g.id in noLine && g.state != GameState.POST) return null
        val parsed = runCatching {
            val (sport, league) = g.league.path.split("/", limit = 2)
            val root = EspnApi.get("$CORE/$sport/leagues/$league/events/${g.id}/competitions/${g.id}/odds", final = g.state == GameState.POST)
            root.arr("items").firstNotNullOfOrNull { parse(it) }
        }.getOrNull()
        if (parsed != null) lines[g.id] = parsed else noLine += g.id
        return parsed
    }

    private fun american(s: String?): Double? = s?.replace("+", "")?.trim()?.let { if (it.equals("EVEN", true)) 100.0 else it.toDoubleOrNull() }

    private fun parse(item: kotlinx.serialization.json.JsonElement): Line? {
        val home = item.obj("homeTeamOdds") ?: return null
        val away = item.obj("awayTeamOdds") ?: return null
        // Closing line first; opening before "current", which turns into live odds once the game starts.
        fun pick(side: kotlinx.serialization.json.JsonElement, field: String) =
            listOf("close", "open").firstNotNullOfOrNull { side.obj(it)?.obj(field)?.let { o -> american(o.str("american") ?: o.str("alternateDisplayValue")) } }
        val homeMl = pick(home, "moneyLine") ?: home.str("moneyLine")?.toDoubleOrNull()
        val awayMl = pick(away, "moneyLine") ?: away.str("moneyLine")?.toDoubleOrNull()
        val homeSpread = pick(home, "pointSpread")
        val favoriteHome = when {
            homeMl != null && awayMl != null && homeMl != awayMl -> homeMl < awayMl
            homeSpread != null && homeSpread != 0.0 -> homeSpread < 0
            home.str("favorite") == "true" -> true
            away.str("favorite") == "true" -> false
            else -> return null
        }
        val spread = (homeSpread ?: item.str("spread")?.toDoubleOrNull())?.let { abs(it) }?.takeIf { it > 0 }
        val fav = if (favoriteHome) homeMl else awayMl
        val dog = if (favoriteHome) awayMl else homeMl
        return Line(favoriteHome, spread, fav?.roundToInt(), dog?.roundToInt()?.takeIf { it > 0 })
    }
}
