package com.dez.sports.data

import com.dez.sports.model.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** A 0–100 "should I watch this?" rating for a game that hasn't started, with what earned it. */
data class WatchScore(val score: Int, val verdict: String, val parts: List<DezPart>)

/**
 * Dez Watch: how worth watching an upcoming game looks, from how good both teams are, how close the line is,
 * whether it touches one of your teams' races, national TV, and what's riding on it in the standings.
 */
object WatchScores {
    /** Standings at two levels: the playoff race (conference / league) and the group a fan follows (division / conference). */
    private class Standings(val race: List<StandingsGroup>, val groups: List<StandingsGroup>, val at: Long)
    private val standings = ConcurrentHashMap<League, Standings>()

    private suspend fun standings(l: League): Standings? {
        standings[l]?.takeIf { System.currentTimeMillis() - it.at < 30 * 60_000 }?.let { return it }
        return runCatching {
            val race = EspnApi.standings(l)
            // The group a fan cares about: NBA conference, NHL/NFL/MLB division, college conference.
            val groups = if (l == League.NHL || l == League.NFL || l == League.MLB) EspnApi.standings(l, divisions = true) else race
            Standings(race, groups, System.currentTimeMillis())
        }.getOrNull()?.also { standings[l] = it }
    }

    private fun seasonLength(l: League) = when (l) { League.NBA, League.NHL -> 82; League.NFL -> 17; League.MLB -> 162; League.CFB -> 12; League.CBB -> 31; else -> 1 }

    /** Playoff line inside each race group: NBA play-in, NHL top 8 per conference, NFL top 7, MLB top 6. */
    private fun cutoff(l: League) = when (l) { League.NBA -> 10; League.NHL -> 8; League.NFL -> 7; League.MLB -> 6; else -> 0 }

    private data class Rec(val w: Int, val l: Int, val t: Int) { val gp get() = w + l + t }
    private fun rec(s: Side): Rec? = s.record?.split("-")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size >= 2 }?.let { Rec(it[0], it[1], it.getOrElse(2) { 0 }) }

    /** Win rate pulled toward .500 early in the season; AP rank vouches for college teams. */
    private fun strength(s: Side): Double {
        val r = rec(s)
        val wp = r?.let { (it.w + .5 * it.t + 2) / (it.gp + 4.0) } ?: .5
        return s.rank?.let { maxOf(wp, 1.0 - it / 50.0) } ?: wp
    }

    /** How good the two teams are together (both good matters more than one great team), about .35 (poor) to .70 (great). */
    fun quality(g: Game): Double {
        val a = strength(g.away); val h = strength(g.home)
        return (a + h) / 2 * .5 + minOf(a, h) * .5
    }

    fun teamsDetail(g: Game) = listOf(g.away, g.home).joinToString(" vs ") { s -> (s.rank?.let { "#$it " } ?: "") + s.team.abbreviation + (s.record?.let { " ($it)" } ?: "") }

    suspend fun of(g: Game): WatchScore? {
        if (g.state != GameState.PRE || g.league == League.PGA || g.isTbd) return null
        val parts = mutableListOf<DezPart>()

        // 1. How good the teams are (both good matters more than one great team).
        parts += DezPart("Teams", teamsDetail(g), (((quality(g) - .35) / .35).coerceIn(0.0, 1.0) * 33).roundToInt(), 33)

        // 2. How close the line is.
        val line = Upsets.cached(g.id) ?: runCatching { Upsets.line(g) }.getOrNull()
        val (close, lineText) = when {
            line == null -> .5 to "No line yet"
            (g.league.kind == SportKind.HOCKEY || g.league.kind == SportKind.BASEBALL) && line.favMoneyline != null -> {
                val fav = abs(line.favMoneyline).toDouble()
                (1 - (fav - 110) / 140).coerceIn(0.0, 1.0) to "${favAbbr(g, line)} ${line.favMoneyline}"
            }
            line.spread != null -> {
                val scale = when (g.league) { League.CFB -> 21.0; League.CBB -> 16.0; else -> 14.0 }
                (1 - line.spread / scale).coerceIn(0.0, 1.0) to "${favAbbr(g, line)} -${fmt(line.spread)}"
            }
            else -> .5 to "No line yet"
        }
        parts += DezPart("Close line", lineText, (close * 27).roundToInt(), 27)

        // 3. Your teams: one of them playing, or a team in their race.
        val favs = Favorites.teams.value.filter { it.league == g.league }
        val st = if (favs.isNotEmpty() || cutoff(g.league) > 0) standings(g.league) else null
        val mine = favs.firstOrNull { g.involves(it.teamId) }
        val yours = when {
            mine != null -> 10 to "${(if (g.home.team.id == mine.teamId) g.home else g.away).team.displayName} play"
            else -> favs.firstNotNullOfOrNull { f ->
                val grp = st?.groups?.firstOrNull { gr -> gr.rows.any { it.team.id == f.teamId } } ?: return@firstNotNullOfOrNull null
                val name = grp.rows.first { it.team.id == f.teamId }.team.name.ifEmpty { "your team" }
                if (grp.rows.any { it.team.id == g.home.team.id || it.team.id == g.away.team.id }) 6 to "In the ${name}' ${shortGroup(grp.name)}" else null
            } ?: (0 to "None of your teams' races")
        }
        parts += DezPart("Your teams", yours.second, yours.first, 10)

        // 4. Playoff picture: postseason, or two teams near the line, mattering more as the season goes on.
        val post = g.seriesSummary != null || g.note?.lowercase()?.let { n -> listOf("playoff", "bowl", "championship", "tournament", "final", "round", "wild card", "play-in").any { it in n } } == true
        val stakes = when {
            post -> 1.0 to (g.note ?: g.seriesSummary ?: "Postseason")
            else -> {
                val progress = listOfNotNull(rec(g.away), rec(g.home)).map { it.gp }.average().takeIf { !it.isNaN() }?.let { it / seasonLength(g.league) }?.coerceIn(.15, 1.0) ?: .15
                val inHunt: (Side) -> Boolean = if (g.league == League.CFB || g.league == League.CBB) { s -> s.rank != null }
                    else { s -> seed(st, s.team.id)?.let { it <= cutoff(g.league) + 2 } == true }
                val n = listOf(g.away, g.home).count(inHunt)
                val what = when (n) { 2 -> if (g.league.weekly || g.league == League.CBB) "Two ranked teams" else "Both in the playoff race"; 1 -> if (g.league == League.CFB || g.league == League.CBB) "One ranked team" else "One team in the race"; else -> "Not much riding on it" }
                (n / 2.0 * progress) to "$what · ${(progress * 100).roundToInt()}% through the season"
            }
        }
        parts += DezPart("Playoff picture", stakes.second, (stakes.first * 20).roundToInt(), 20)

        // 5. National TV.
        val national = g.channels.firstOrNull { Networks.isMarquee(it) } ?: g.channels.firstOrNull { it.national && !Networks.isStreamingOnly(it) }
        parts += DezPart("National TV", national?.name ?: "Local or streaming only", when { national == null -> 0; Networks.isMarquee(national) -> 10; else -> 6 }, 10)

        val score = parts.sumOf { it.points }.coerceIn(0, 100)
        return WatchScore(score, verdict(score), parts)
    }

    private fun seed(st: Standings?, teamId: String): Int? {
        val grp = st?.race?.firstOrNull { g -> g.rows.any { it.team.id == teamId } } ?: return null
        val i = grp.rows.indexOfFirst { it.team.id == teamId }
        return grp.rows[i].seed?.toIntOrNull() ?: (i + 1)
    }

    private fun shortGroup(name: String) = name.replace("Conference", "conference").replace("Division", "division")
    private fun favAbbr(g: Game, l: Line) = (if (l.favoriteHome) g.home else g.away).team.abbreviation
    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    fun verdict(score: Int) = when {
        score >= 80 -> "Must watch"
        score >= 65 -> "Worth your night"
        score >= 50 -> "Solid"
        score >= 35 -> "If you're free"
        else -> "Skip it"
    }
}
