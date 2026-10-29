package com.dez.sports.data

import com.dez.sports.model.League
import com.dez.sports.model.SportKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/** Everything needed to answer "is this good?" for one number: where it sits among everyone in the league. */
data class Distribution(
    val title: String,              // "Points Per Game"
    val subject: String,            // "Lauri Markkanen" / "Utah Jazz"
    val value: Double,
    val display: String,
    val values: List<Double>,       // every comparable value, in ranking order
    val rank: Int,                  // 1-based, in ranking order; 0 = not ranked (unqualified)
    val descending: Boolean,        // true = biggest number ranks first
    val population: String,         // "qualified NBA players" / "NBA teams"
    val top: List<Pair<String, String>>,
) {
    val of get() = values.size
    val percentile get() = if (rank <= 0 || of <= 1) null else ((of - rank).toFloat() / (of - 1) * 100).toInt()
    val average get() = values.average()
}

object StatContext {
    private const val COMMON = "https://site.web.api.espn.com/apis/common/v3/sports"
    private val lowerFirst = setOf("ERA", "WHIP", "GAA", "TO", "PF", "FUM", "LST", "PIM", "GA", "ER", "BB", "L", "OBA")

    private data class Col(val cat: String, val name: String, val label: String, val display: String)
    private val columns = ConcurrentHashMap<League, List<Col>>()
    private val lists = ConcurrentHashMap<String, List<LeaderEntry>>()

    private suspend fun cols(league: League): List<Col> = columns[league] ?: run {
        val root = EspnApi.get("$COMMON/${league.path}/statistics/byathlete?region=us&lang=en&contentorigin=espn&isqualified=true&seasontype=2&limit=1")
        root.arr("categories").flatMap { c ->
            val names = c.strings("names"); val labels = c.strings("labels"); val displays = c.strings("displayNames")
            names.indices.map { Col(c.str("name") ?: "", names[it], labels.getOrElse(it) { "" }, displays.getOrElse(it) { names[it] }) }
        }.also { columns[league] = it }
    }

    /** Which by-athlete category a season table's columns live in, by what the table holds. */
    private fun preferredCats(kind: SportKind, table: String): List<String> = when (kind) {
        SportKind.FOOTBALL -> when (table) {
            "Quarterbacks" -> listOf("passing", "rushing"); "Running Backs" -> listOf("rushing", "receiving"); "Receivers" -> listOf("receiving", "rushing")
            "Defense" -> listOf("defensive", "defensiveinterceptions", "general"); "Kickers" -> listOf("kicking"); "Punters" -> listOf("punting"); else -> emptyList()
        }
        SportKind.BASEBALL -> if (table == "Pitchers") listOf("pitching") else listOf("batting")
        SportKind.HOCKEY -> if (table == "Goalies") listOf("defensive", "general") else listOf("offensive", "general", "penalties")
        else -> listOf("offensive", "general", "defensive")
    }

    /** A player's season number against every qualified player in the league. */
    suspend fun player(league: League, table: String, label: String, playerId: String, playerName: String, display: String): Distribution? = coroutineScope {
        // Box-score style "made-attempted" columns rank by makes.
        val want = mapOf("FG" to "FGM", "3PT" to "3PM", "FT" to "FTM")[label] ?: label
        val all = cols(league).filter { it.label == want }.ifEmpty { cols(league).filter { it.label == label } }
        val col = preferredCats(league.kind, table).firstNotNullOfOrNull { c -> all.firstOrNull { it.cat == c } } ?: all.firstOrNull() ?: return@coroutineScope null
        val key = "${col.cat}.${col.name}"
        val entries = lists["${league.name}/$key"] ?: run {
            val first = LeagueStats.players(league, key, 1)
            // Up to 400 players – every qualified NBA/NHL/NFL/MLB player; the top 400 in college.
            val rest = (2..minOf(first.pages, 8)).map { p -> async { runCatching { LeagueStats.players(league, key, p).entries }.getOrDefault(emptyList()) } }.awaitAll()
            (first.entries + rest.flatten()).also { lists["${league.name}/$key"] = it }
        }
        val values = entries.map { TeamPlayers.num(it.value) }.filter(Double::isFinite)
        if (values.size < 5) return@coroutineScope null
        val v = TeamPlayers.num(display).takeIf(Double::isFinite) ?: return@coroutineScope null
        val descending = want !in lowerFirst
        val rank = entries.indexOfFirst { it.athleteId == playerId }.let { if (it >= 0) it + 1 else 0 }
        Distribution(col.display, playerName, v, display, values, rank, descending, "qualified ${league.shortLabel} players",
            entries.take(3).map { it.name to it.value })
    }

    /** A team's season number against every team in the league, matched by the stat's name. */
    fun team(stats: LeagueTeamStats, statName: String, teamId: String, display: String): Distribution? {
        val key = stats.keyFor(statName) ?: return null
        val vals = stats.valuesOf(key)
        val v = vals[teamId] ?: return null
        val descending = !lowerFirst.contains(stats.labelOf(key)) && !statName.contains("Against", true) && !statName.contains("Allowed", true)
        val sorted = vals.entries.sortedBy { if (descending) -it.value else it.value }
        val better = vals.values.count { if (descending) it > v + 1e-9 else it < v - 1e-9 }
        return Distribution(statName, stats.teams[teamId]?.displayName ?: "", v, display, sorted.map { it.value }, better + 1, descending,
            "${stats.league.shortLabel} teams", sorted.take(3).mapNotNull { (id, x) -> stats.teams[id]?.let { it.abbreviation to "%.1f".format(x) } })
    }

    fun teamRank(stats: LeagueTeamStats, statName: String, teamId: String): Pair<Int, Boolean>? {
        // Games played/started and minutes say nothing about quality.
        if (listOf("Games", "Minutes").any { statName.startsWith(it) }) return null
        return team(stats, statName, teamId, "")?.let { it.rank to it.descending }
    }
}
