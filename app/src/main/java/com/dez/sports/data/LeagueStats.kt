package com.dez.sports.data

import com.dez.sports.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** How a team stat is read, which way is good, and how it's printed. */
data class TeamStatSpec(
    val label: String,
    /** "category.name", "opp:category.name" (what opponents did against the team) or several joined by "|" as fallbacks. */
    val key: String,
    val higherIsBetter: Boolean,
    /** Divide a season total by games played. */
    val perGame: Boolean = false,
    val decimals: Int = 1,
    /** ".235" rather than "0.235". */
    val dropLeadingZero: Boolean = false,
) {
    fun format(v: Double): String {
        val s = "%.${decimals}f".format(v)
        return if (dropLeadingZero && s.startsWith("0.")) s.drop(1) else if (decimals == 0) "%,d".format(v.roundToInt()) else s
    }
}

data class TeamRank(val spec: TeamStatSpec, val value: Double, val rank: Int, val tied: Boolean, val of: Int, val average: Double, val best: Double, val worst: Double) {
    val display get() = spec.format(value)
    val ordinal get() = (if (tied) "T-" else "") + ordinal(rank)
    /** 1.0 = best in the league, 0.0 = worst. */
    val percentile get() = if (of <= 1) 1f else 1f - (rank - 1f) / (of - 1f)
}

data class TeamRow(val team: Team, val value: Double, val rank: Int, val tied: Boolean)

class LeagueTeamStats(val league: League, val teams: Map<String, Team>, private val values: Map<String, Map<String, Double>>,
                      private val displayNames: Map<String, String> = emptyMap(), private val labels: Map<String, String> = emptyMap()) {
    /** Own-team column whose ESPN display name matches ("Points Per Game" → "offensive.avgPoints"). */
    fun keyFor(displayName: String): String? = displayNames.entries.firstOrNull { (k, n) -> !k.startsWith("opp:") && n.equals(displayName, true) && (values[k]?.size ?: 0) >= teams.size / 2 }?.key
    fun valuesOf(key: String): Map<String, Double> = values[key].orEmpty()
    fun labelOf(key: String): String = labels[key].orEmpty()

    val specs: List<TeamStatSpec> = LeagueStats.specs(league).filter { resolve(it).size >= teams.size / 2 }

    private fun resolve(s: TeamStatSpec): Map<String, Double> {
        val raw = s.key.split("|").firstNotNullOfOrNull { k -> values[k]?.takeIf { it.isNotEmpty() } } ?: return emptyMap()
        if (!s.perGame) return raw
        val gp = values[LeagueStats.gamesKey(league)] ?: return emptyMap()
        return raw.mapNotNull { (id, v) -> gp[id]?.takeIf { it > 0 }?.let { id to v / it } }.toMap()
    }

    /** Every team ordered best to worst for one stat. */
    fun table(s: TeamStatSpec): List<TeamRow> {
        val vals = resolve(s)
        val sorted = vals.entries.sortedBy { if (s.higherIsBetter) -it.value else it.value }
        return sorted.mapNotNull { (id, v) ->
            val t = teams[id] ?: return@mapNotNull null
            val better = vals.values.count { if (s.higherIsBetter) it > v + 1e-9 else it < v - 1e-9 }
            val same = vals.values.count { kotlin.math.abs(it - v) < 1e-9 }
            TeamRow(t, v, better + 1, same > 1)
        }
    }

    fun rankOf(teamId: String, s: TeamStatSpec): TeamRank? {
        val vals = resolve(s)
        val v = vals[teamId] ?: return null
        val better = vals.values.count { if (s.higherIsBetter) it > v + 1e-9 else it < v - 1e-9 }
        val same = vals.values.count { kotlin.math.abs(it - v) < 1e-9 }
        val best = if (s.higherIsBetter) vals.values.max() else vals.values.min()
        val worst = if (s.higherIsBetter) vals.values.min() else vals.values.max()
        return TeamRank(s, v, better + 1, same > 1, vals.size, vals.values.average(), best, worst)
    }

    fun ranks(teamId: String) = specs.mapNotNull { rankOf(teamId, it) }
}

data class PlayerPage(val entries: List<LeaderEntry>, val pages: Int, val total: Int)

/**
 * League-wide numbers from ESPN's "by team" and "by athlete" feeds: every team's season stats (so a team can be
 * ranked against the league) and complete, paged player leaderboards behind the top-10 leaders.
 */
object LeagueStats {
    private const val COMMON = "https://site.web.api.espn.com/apis/common/v3/sports"
    private val cache = ConcurrentHashMap<League, LeagueTeamStats>()

    fun gamesKey(l: League) = when (l.kind) {
        SportKind.HOCKEY -> "general.games"
        SportKind.BASEBALL -> "batting.gamesPlayed"
        else -> "general.gamesPlayed"
    }

    fun specs(l: League): List<TeamStatSpec> = when (l.kind) {
        SportKind.BASKETBALL -> listOf(
            TeamStatSpec("Points", "offensive.avgPoints", true),
            TeamStatSpec("Points Allowed", "opp:offensive.avgPoints", false),
            TeamStatSpec("Point Differential", "differential.avgPointsDifferential", true),
            TeamStatSpec("Field Goal %", "offensive.fieldGoalPct", true),
            TeamStatSpec("3-Point %", "offensive.threePointFieldGoalPct", true),
            TeamStatSpec("Free Throw %", "offensive.freeThrowPct", true),
            TeamStatSpec("3-Pointers Made", "offensive.avgThreePointFieldGoalsMade", true),
            TeamStatSpec("Rebounds", "general.avgRebounds", true),
            TeamStatSpec("Assists", "offensive.avgAssists", true),
            TeamStatSpec("Turnovers", "offensive.avgTurnovers", false),
            TeamStatSpec("Steals", "defensive.avgSteals", true),
            TeamStatSpec("Blocks", "defensive.avgBlocks", true),
            TeamStatSpec("Opponent FG %", "opp:offensive.fieldGoalPct", false),
            TeamStatSpec("Opponent 3P %", "opp:offensive.threePointFieldGoalPct", false),
        )
        SportKind.HOCKEY -> listOf(
            TeamStatSpec("Goals For / Game", "offensive.avgGoals", true, decimals = 2),
            TeamStatSpec("Goals Against / Game", "defensive.avgGoalsAgainst", false, decimals = 2),
            TeamStatSpec("Power Play %", "offensive.powerPlayPct", true),
            TeamStatSpec("Penalty Kill %", "defensive.penaltyKillPct", true),
            TeamStatSpec("Shots / Game", "offensive.shotsTotal", true, perGame = true),
            TeamStatSpec("Shots Against / Game", "defensive.shotsAgainst", false, perGame = true),
            TeamStatSpec("Shooting %", "offensive.shootingPct", true),
            TeamStatSpec("Save %", "defensive.savePct", true, decimals = 3, dropLeadingZero = true),
            TeamStatSpec("Power Play Goals", "offensive.powerPlayGoals", true, decimals = 0),
            TeamStatSpec("Shutouts", "defensive.shutouts", true, decimals = 0),
            TeamStatSpec("Penalty Minutes / Game", "penalties.penaltyMinutes", false, perGame = true),
        )
        SportKind.FOOTBALL -> listOf(
            TeamStatSpec("Points / Game", "passing.totalPointsPerGame", true),
            TeamStatSpec("Points Allowed / Game", "opp:passing.totalPointsPerGame", false),
            TeamStatSpec("Yards / Game", "passing.yardsPerGame", true),
            TeamStatSpec("Yards Allowed / Game", "opp:passing.yardsPerGame", false),
            TeamStatSpec("Passing Yards / Game", "passing.netPassingYardsPerGame|passing.passingYardsPerGame", true),
            TeamStatSpec("Rushing Yards / Game", "rushing.rushingYardsPerGame", true),
            TeamStatSpec("Pass Yards Allowed / Game", "opp:passing.netPassingYardsPerGame|opp:passing.passingYardsPerGame", false),
            TeamStatSpec("Rush Yards Allowed / Game", "opp:rushing.rushingYardsPerGame", false),
            TeamStatSpec("Yards / Carry", "rushing.yardsPerRushAttempt", true),
            TeamStatSpec("3rd Down %", "miscellaneous.thirdDownConvPct", true),
            TeamStatSpec("Turnover Differential", "miscellaneous.turnOverDifferential", true, decimals = 0),
            TeamStatSpec("Takeaways", "miscellaneous.totalTakeaways", true, decimals = 0),
            TeamStatSpec("Sacks", "opp:passing.sacks", true, decimals = 0),
            TeamStatSpec("Sacks Allowed", "passing.sacks", false, decimals = 0),
            TeamStatSpec("Penalty Yards / Game", "miscellaneous.totalPenaltyYards", false, perGame = true),
        )
        SportKind.BASEBALL -> listOf(
            TeamStatSpec("Runs / Game", "batting.runs", true, perGame = true, decimals = 2),
            TeamStatSpec("Batting Average", "batting.avg", true, decimals = 3, dropLeadingZero = true),
            TeamStatSpec("On-Base %", "batting.onBasePct", true, decimals = 3, dropLeadingZero = true),
            TeamStatSpec("Slugging %", "batting.slugAvg", true, decimals = 3, dropLeadingZero = true),
            TeamStatSpec("OPS", "batting.OPS", true, decimals = 3, dropLeadingZero = true),
            TeamStatSpec("Home Runs", "batting.homeRuns", true, decimals = 0),
            TeamStatSpec("Stolen Bases", "batting.stolenBases", true, decimals = 0),
            TeamStatSpec("ERA", "pitching.ERA", false, decimals = 2),
            TeamStatSpec("WHIP", "pitching.WHIP", false, decimals = 2),
            TeamStatSpec("Strikeouts (Pitching)", "pitching.strikeouts", true, decimals = 0),
            TeamStatSpec("Opponent Average", "pitching.opponentAvg", false, decimals = 3, dropLeadingZero = true),
            TeamStatSpec("Saves", "pitching.saves", true, decimals = 0),
            TeamStatSpec("Quality Starts", "pitching.qualityStarts", true, decimals = 0),
            TeamStatSpec("Errors", "fielding.errors", false, decimals = 0),
            TeamStatSpec("Fielding %", "fielding.fieldingPct", true, decimals = 3, dropLeadingZero = true),
        )
        SportKind.GOLF -> emptyList()
    }

    suspend fun teams(league: League): LeagueTeamStats = cache[league] ?: coroutineScope {
        val dir = async { EspnApi.directory(league) }
        val url = "$COMMON/${league.path}/statistics/byteam?region=us&lang=en&contentorigin=espn&seasontype=2&limit=400"
        val first = EspnApi.get(url)
        val pages = first.obj("pagination")?.int("pages") ?: 1
        val rest = (2..pages).map { p -> async { EspnApi.get("$url&page=$p") } }.awaitAll()
        val roots = listOf(first) + rest
        // The column names live once at the top level; each team only carries values in the same order.
        val names = first.arr("categories").filter { it.strings("names").isNotEmpty() }.associate { (it.str("name") ?: "") to it.strings("names") }
        val displayNames = HashMap<String, String>(); val labels = HashMap<String, String>()
        first.arr("categories").forEach { c ->
            val cat = c.str("name") ?: return@forEach
            val ns = c.strings("names"); val ds = c.strings("displayNames"); val ls = c.strings("labels")
            ns.forEachIndexed { i, n -> displayNames.putIfAbsent("$cat.$n", ds.getOrElse(i) { n }); labels.putIfAbsent("$cat.$n", ls.getOrElse(i) { "" }) }
        }
        val directory = dir.await()
        val values = HashMap<String, HashMap<String, Double>>()
        val teams = HashMap<String, Team>()
        for (root in roots) for (t in root.arr("teams")) {
            val team = t.obj("team") ?: continue
            val id = team.str("id") ?: continue
            // College feeds include non-Division I schools; the directory is the D-I list.
            if (directory.isNotEmpty() && id !in directory) continue
            teams[id] = directory[id] ?: EspnApi.parseTeam(team)
            for (c in t.arr("categories")) {
                val cat = c.str("name") ?: continue
                val cols = names[cat] ?: continue
                val prefix = if (c.str("splitId") == "900") "opp:" else ""
                c.arr("values").forEachIndexed { i, v ->
                    val n = cols.getOrNull(i) ?: return@forEachIndexed
                    val d = v.content?.toDoubleOrNull() ?: return@forEachIndexed
                    // Columns repeat (e.g. avgRebounds twice); first one wins.
                    values.getOrPut("$prefix$cat.$n") { HashMap() }.putIfAbsent(id, d)
                }
            }
        }
        LeagueTeamStats(league, teams, values, displayNames, labels).also { if (teams.isNotEmpty()) cache[league] = it }
    }

    // ---------------------------------------------------------------- complete player leaderboards

    /** Leader-feed category names that differ from the stat they sort by. */
    private val aliases = mapOf(
        "pointsPerGame" to "offensive.avgPoints", "assistsPerGame" to "offensive.avgAssists", "fieldGoalPercentage" to "offensive.fieldGoalPct",
        "reboundsPerGame" to "general.avgRebounds", "stealsPerGame" to "defensive.avgSteals", "blocksPerGame" to "defensive.avgBlocks",
        "FreeThrowPct" to "offensive.freeThrowPct", "3PointPct" to "offensive.threePointFieldGoalPct", "3PointsMadePerGame" to "offensive.avgThreePointFieldGoalsMade",
        "minutesPerGame" to "general.avgMinutes", "foulsPerGame" to "general.avgFouls", "avgTurnovers" to "offensive.avgTurnovers",
        "doubleDouble" to "general.doubleDouble", "quarterbackRating" to "passing.QBRating",
    )

    /** Names that exist in more than one category; the leader board means this one. */
    private fun preferred(kind: SportKind) = when (kind) {
        SportKind.FOOTBALL -> mapOf("sacks" to "defensive.sacks", "interceptions" to "defensiveinterceptions.interceptions", "totalPoints" to "scoring.totalPoints", "totalTouchdowns" to "scoring.totalTouchdowns",
            "rushingTouchdowns" to "rushing.rushingTouchdowns", "receivingTouchdowns" to "receiving.receivingTouchdowns")
        SportKind.BASEBALL -> mapOf("strikeouts" to "pitching.strikeouts", "wins" to "pitching.wins", "hits" to "batting.hits", "homeRuns" to "batting.homeRuns", "WARBR" to "batting.WARBR")
        SportKind.HOCKEY -> mapOf("wins" to "general.wins")
        else -> emptyMap()
    }

    private val lowerFirst = setOf("avgGoalsAgainst", "ERA", "WHIP", "opponentAvg", "avgTurnovers", "avgFouls", "foulsPerGame")

    private val columns = ConcurrentHashMap<League, Map<String, List<String>>>()

    /** The by-athlete sort key behind a leader category, or null when the full list isn't available for it. */
    suspend fun sortKey(league: League, leaderName: String): String? {
        val cols = columns[league] ?: runCatching {
            EspnApi.get("$COMMON/${league.path}/statistics/byathlete?region=us&lang=en&contentorigin=espn&isqualified=true&seasontype=2&limit=1")
                .arr("categories").associate { (it.str("name") ?: "") to it.strings("names") }
        }.getOrNull()?.also { columns[league] = it } ?: return null
        fun exists(k: String) = k.substringBefore('.').let { c -> cols[c]?.contains(k.substringAfter('.')) == true }
        (preferred(league.kind)[leaderName] ?: aliases[leaderName])?.let { if (exists(it)) return it }
        return cols.entries.firstOrNull { leaderName in it.value }?.let { "${it.key}.$leaderName" }
    }

    suspend fun players(league: League, sortKey: String, page: Int): PlayerPage {
        val stat = sortKey.substringAfter('.')
        val dir = if (stat in lowerFirst) "asc" else "desc"
        val root = EspnApi.get("$COMMON/${league.path}/statistics/byathlete?region=us&lang=en&contentorigin=espn&isqualified=true&seasontype=2&limit=50&page=$page&sort=$sortKey:$dir")
        val cat = sortKey.substringBefore('.')
        val idx = root.arr("categories").firstOrNull { it.str("name") == cat }?.strings("names")?.indexOf(stat) ?: -1
        val directory = EspnApi.directory(league)
        val per = root.obj("pagination")?.int("limit") ?: 50
        val entries = root.arr("athletes").mapIndexedNotNull { i, e ->
            val a = e.obj("athlete") ?: return@mapIndexedNotNull null
            val c = e.arr("categories").firstOrNull { it.str("name") == cat }
            val value = c?.strings("totals")?.getOrNull(idx) ?: return@mapIndexedNotNull null
            LeaderEntry((page - 1) * per + i + 1, a.str("id") ?: return@mapIndexedNotNull null, a.str("displayName") ?: "",
                a.obj("headshot")?.str("href"), a.str("teamId")?.let { directory[it] }, value)
        }
        return PlayerPage(entries, root.obj("pagination")?.int("pages") ?: 1, root.obj("pagination")?.int("count") ?: entries.size)
    }
}

fun ordinal(n: Int): String = "$n" + if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
