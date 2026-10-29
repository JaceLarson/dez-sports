package com.dez.sports.data

import com.dez.sports.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Season stats for every player on a roster, as box-score style tables. ESPN has no team-wide
 * player stats feed, so this pulls each player's season line and groups players whose stat
 * columns match (skaters vs goalies, hitters vs pitchers, QBs vs receivers…).
 */
object TeamPlayers {
    suspend fun load(league: League, roster: List<RosterGroup>): List<StatTable> = coroutineScope {
        val gate = Semaphore(8)
        val players = roster.flatMap { it.players }.distinctBy { it.id }
        val results = players.map { p ->
            async { gate.withPermit { runCatching { PlayerApi.seasonLine(league, p.id)?.let { p to it } } } }
        }.awaitAll()
        val lines = results.mapNotNull { it.getOrNull() }
        if (lines.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        lines.groupBy { it.second.labels }.map { (labels, group) ->
            val rows = group.map { (p, l) ->
                StatRow(p.id, p.name, shortName(p.name), p.position, p.jersey, p.headshot, starter = false, didNotPlay = false, reason = null, stats = l.stats)
            }
            val title = title(league, labels, group.mapNotNull { it.first.position })
            val key = sortColumn(labels)
            val sorted = if (key < 0) rows else rows.sortedByDescending { r -> num(r.stats[key]) * (if (labels[key] in LowerFirst) -1 else 1) }
            StatTable(title, labels, group.first().second.descriptions, sorted, null)
        }.sortedBy { order(it.title) }
    }

    private val LowerFirst = setOf("ERA", "WHIP", "GAA")

    private fun shortName(full: String) = full.split(" ").let { if (it.size > 1) "${it.first().first()}. ${it.drop(1).joinToString(" ")}" else full }

    fun num(v: String): Double = v.replace(",", "").substringBefore("-").substringBefore(":").toDoubleOrNull() ?: Double.NEGATIVE_INFINITY

    private fun title(league: League, labels: List<String>, positions: List<String>): String = when (league.kind) {
        SportKind.BASKETBALL -> "Players"
        SportKind.HOCKEY -> if ("GAA" in labels || "SV%" in labels) "Goalies" else "Skaters"
        SportKind.BASEBALL -> if ("ERA" in labels) "Pitchers" else "Hitters"
        SportKind.FOOTBALL -> when {
            "CMP" in labels || "QBR" in labels -> "Quarterbacks"
            labels.firstOrNull() == "CAR" -> "Running Backs"
            labels.firstOrNull() == "REC" -> "Receivers"
            "TOT" in labels || "SOLO" in labels || "SACK" in labels -> "Defense"
            "FGM" in labels || "FG" in labels || "XPM" in labels -> "Kickers"
            "PUNTS" in labels || "NO" in labels && "AVG" in labels -> "Punters"
            else -> positions.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "Players"
        }
        SportKind.GOLF -> "Players"
    }

    private fun order(title: String) = listOf("Players", "Skaters", "Goalies", "Hitters", "Pitchers", "Quarterbacks", "Running Backs", "Receivers", "Defense", "Kickers", "Punters")
        .indexOf(title).let { if (it < 0) 99 else it }

    /** The column a team's stat sheet is ranked by first. */
    private fun sortColumn(labels: List<String>): Int =
        listOf("PTS", "TOT", "YDS", "SV%", "IP", "AVG", "FGM", "G").firstNotNullOfOrNull { k -> labels.indexOf(k).takeIf { it >= 0 } } ?: -1
}
