package com.dez.sports.data

import com.dez.sports.model.Game
import com.dez.sports.model.GameState
import com.dez.sports.model.League
import com.dez.sports.model.SportKind
import kotlin.math.abs
import kotlin.math.roundToInt

/** One ingredient of a game's excitement rating and how many of the 100 points it earned. */
data class DezPart(val label: String, val detail: String, val points: Int, val max: Int)

data class DezScore(val score: Int, val verdict: String, val parts: List<DezPart>)

/**
 * "Dez Score": a 0–100 rating of how fun a finished game was to watch, built from the final score,
 * the period-by-period line, the stakes and the pregame line (a long shot winning is the best kind of game).
 */
object DezScores {
    private class Scale(val close: Float, val comeback: Float, val highScoring: Int, val regulation: Int)

    private fun scale(l: League) = when (l.kind) {
        SportKind.BASKETBALL -> Scale(24f, 15f, if (l == League.CBB) 160 else 240, if (l == League.CBB) 2 else 4)
        SportKind.FOOTBALL -> Scale(24f, 14f, 60, 4)
        SportKind.HOCKEY -> Scale(3f, 2f, 8, 3)
        SportKind.BASEBALL -> Scale(5f, 3f, 14, 9)
        SportKind.GOLF -> Scale(1f, 1f, 1, 1)
    }

    fun of(g: Game, line: Line? = Upsets.cached(g.id)): DezScore? {
        if (g.state != GameState.POST || g.league.kind == SportKind.GOLF) return null
        val a = g.away.score?.toIntOrNull() ?: return null
        val h = g.home.score?.toIntOrNull() ?: return null
        val s = scale(g.league)
        val margin = abs(a - h)
        val parts = mutableListOf<DezPart>()

        // 1. How close it finished.
        val closeness = (1f - margin / s.close).coerceIn(0f, 1f)
        parts += DezPart("Finish", if (margin == 0) "Tied" else "Decided by $margin", (closeness * 30).roundToInt(), 30)

        // Period-by-period running score, from each team's linescore.
        val la = g.away.linescores.map { it.toIntOrNull() ?: 0 }
        val lh = g.home.linescores.map { it.toIntOrNull() ?: 0 }
        val n = minOf(la.size, lh.size)
        val runA = la.take(n).runningFold(0) { acc, v -> acc + v }.drop(1)
        val runH = lh.take(n).runningFold(0) { acc, v -> acc + v }.drop(1)
        val awayWon = a > h

        // 2. Back and forth: the biggest deficit the winner erased, and how often the lead changed hands, between periods.
        val deficit = if (n == 0 || a == h) 0 else (0 until n - 1).maxOfOrNull { i -> if (awayWon) runH[i] - runA[i] else runA[i] - runH[i] }?.coerceAtLeast(0) ?: 0
        var changes = 0; var leader = 0
        for (i in 0 until n) {
            val now = (runA[i] - runH[i]).let { if (it > 0) 1 else if (it < 0) -1 else 0 }
            if (now != 0 && leader != 0 && now != leader) changes++
            if (now != 0) leader = now
        }
        val swing = (deficit / s.comeback).coerceIn(0f, 1f) * .6f + minOf(changes, 3) / 3f * .4f
        val swingDetail = listOfNotNull(if (deficit > 0) "Winner trailed by $deficit" else null, if (changes > 0) "$changes lead change${if (changes > 1) "s" else ""}" else null)
            .joinToString(" · ").ifEmpty { "One team led throughout" }
        parts += DezPart("Back and forth", swingDetail, (swing * 14).roundToInt(), 14)

        // 3. How good the teams were (records after this game; AP rank in college).
        val q = WatchScores.quality(g)
        parts += DezPart("Teams", WatchScores.teamsDetail(g), (((q - .35) / .35).coerceIn(0.0, 1.0) * 20).roundToInt(), 20)

        // 4. Drama in the last period: close going into it.
        val lastReg = minOf(s.regulation, n) - 1
        val beforeLast = if (lastReg >= 1) abs(runA[lastReg - 1] - runH[lastReg - 1]) else margin
        // Tied after regulation is as dramatic as it gets.
        val extra = (n - s.regulation).coerceAtLeast(0)
        val late = if (extra > 0) 1f else (1f - beforeLast / (s.close * .6f)).coerceIn(0f, 1f)
        val lateDetail = when {
            extra > 0 -> if (g.league.kind == SportKind.BASEBALL) "Tied after nine; went to extras" else "Tied at the end of regulation"
            else -> "Within $beforeLast entering the final period"
        }
        parts += DezPart("Late drama", lateDetail, (late * 16).roundToInt(), 16)

        // 6. Fireworks and stakes.
        val total = a + h
        parts += DezPart("Offense", "$total combined", (((total - s.highScoring * .6f) / (s.highScoring * .4f)).coerceIn(0f, 1f) * 4).roundToInt(), 4)
        val ranked = listOfNotNull(g.away.rank, g.home.rank)
        val stakes = (if (g.seriesSummary != null || g.note?.let { n2 -> listOf("final", "playoff", "bowl", "championship", "tournament", "game ").any { n2.lowercase().contains(it) } } == true) 3 else 0) +
            (if (ranked.size == 2) 2 else 0)
        parts += DezPart("Stakes", listOfNotNull(g.seriesSummary ?: g.note, if (ranked.size == 2) "Ranked vs ranked" else null).joinToString(" · ").ifEmpty { "Regular game" }, minOf(stakes, 4), 4)

        // 7. The upset: how long a shot the winner was before the game.
        parts += upset(g, line, awayWon, margin, s)
        val score = parts.sumOf { it.points }.coerceIn(0, 100)
        return DezScore(score, verdict(score), parts)
    }

    /** Up to 12 for a long shot winning (more the longer the odds); a few for a big underdog who nearly did it. */
    private fun upset(g: Game, line: Line?, awayWon: Boolean, margin: Int, s: Scale): DezPart {
        val winnerHome = !awayWon
        if (line != null && g.away.score != g.home.score) {
            val dogWon = line.favoriteHome != winnerHome
            // How long a shot, from 0 (pick'em) to 1 (a true long shot), by spread or moneyline.
            val shot = when {
                line.spread != null && g.league.kind != SportKind.HOCKEY && g.league.kind != SportKind.BASEBALL ->
                    (line.spread / (if (g.league.kind == SportKind.FOOTBALL) 14.0 else 12.0)).coerceIn(0.0, 1.0)
                line.dogMoneyline != null -> ((line.dogMoneyline - 100) / 200.0).coerceIn(0.0, 1.0)
                else -> 0.0
            }
            val odds = if (line.spread != null && g.league.kind != SportKind.HOCKEY && g.league.kind != SportKind.BASEBALL) "+${fmt(line.spread)}" else line.dogMoneyline?.let { "+$it" } ?: ""
            val dog = if (line.favoriteHome) g.away else g.home
            if (dogWon && shot >= .25) return DezPart("Upset", "${dog.team.abbreviation} won as a $odds underdog", (shot * 12).roundToInt().coerceAtLeast(3), 12)
            if (!dogWon && shot >= .7 && margin <= s.close * .2f) return DezPart("Upset", "${dog.team.abbreviation} ($odds) nearly pulled it off", 3, 12)
            return DezPart("Upset", if (dogWon) "Underdog won, but it was close to a coin flip" else "Favorite won", 0, 12)
        }
        // No line (some college games): an unranked or much lower-ranked team beating a ranked one.
        val loserRank = if (awayWon) g.home.rank else g.away.rank
        val winnerRank = if (awayWon) g.away.rank else g.home.rank
        val upset = loserRank != null && (winnerRank == null || winnerRank > loserRank + 5)
        return DezPart("Upset", if (upset) "Beat No. $loserRank" else "No line", if (upset) (if (loserRank!! <= 10) 9 else 6) else 0, 12)
    }

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    fun verdict(score: Int) = when {
        score >= 85 -> "Instant classic"
        score >= 70 -> "Thriller"
        score >= 55 -> "Good game"
        score >= 35 -> "Watchable"
        else -> "Snoozer"
    }
}
