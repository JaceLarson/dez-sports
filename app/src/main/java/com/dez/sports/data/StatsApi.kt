package com.dez.sports.data

import com.dez.sports.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder
import java.time.LocalDate

data class LeaderEntry(val rank: Int, val athleteId: String, val name: String, val headshot: String?, val team: Team?, val value: String)
data class LeaderCategory(val name: String, val abbreviation: String, val entries: List<LeaderEntry>, val key: String = "")

data class SearchPlayer(val id: String, val name: String, val league: League, val team: Team?, val headshot: String?, val position: String?)

data class SplitTable(val title: String, val labels: List<String>, val rows: List<Pair<String, List<String>>>)

data class TeamStatCategory(val title: String, val stats: List<Pair<String, String>>)

data class Meeting(
    val eventId: String, val dateIso: String, val season: String, val postseason: Boolean,
    val homeIsA: Boolean, val aScore: Int, val bScore: Int, val note: String?,
) { val aWon get() = aScore > bScore; val tie get() = aScore == bScore }

/** Leaders, player search, splits, team stats and head-to-head history. */
object StatsApi {
    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"
    private const val SITE_V3 = "https://site.web.api.espn.com/apis/site/v3/sports"
    private const val COMMON = "https://site.web.api.espn.com/apis/common/v3/sports"

    suspend fun leaders(league: League): List<LeaderCategory> = coroutineScope {
        val root = EspnApi.get("$SITE_V3/${league.path}/leaders")
        val cats = root.obj("leaders").arr("categories").map { c ->
            LeaderCategory(
                name = c.str("displayName") ?: "",
                abbreviation = c.str("abbreviation") ?: "",
                key = c.str("name") ?: "",
                entries = c.arr("leaders").mapIndexedNotNull { i, l ->
                    val a = l.obj("athlete") ?: return@mapIndexedNotNull null
                    LeaderEntry(i + 1, a.str("id") ?: return@mapIndexedNotNull null, a.str("displayName") ?: "",
                        a.obj("headshot")?.str("href"), l.obj("team")?.let { EspnApi.parseTeam(it) }, l.str("displayValue") ?: "")
                },
            )
        }.filter { it.entries.isNotEmpty() }
        // During the postseason this feed switches to playoff game lines ("4-8, 2B, RBI"); rebuild those from season stats.
        cats.map { c ->
            async {
                if (c.entries.none { "," in it.value }) return@async c
                val key = LeagueStats.sortKey(league, c.key) ?: return@async null
                runCatching { LeagueStats.players(league, key, 1).entries.take(10) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { c.copy(entries = it) }
            }
        }.awaitAll().filterNotNull()
    }

    private val slugs = mapOf("nba" to League.NBA, "nfl" to League.NFL, "nhl" to League.NHL, "mlb" to League.MLB,
        "college-football" to League.CFB, "mens-college-basketball" to League.CBB)

    suspend fun searchPlayers(query: String, league: League? = null): List<SearchPlayer> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val root = EspnApi.get("https://site.api.espn.com/apis/common/v3/search?query=$q&limit=25&type=player")
        return root.arr("items").mapNotNull { i ->
            val l = slugs[i.str("league")] ?: return@mapNotNull null
            if (league != null && l != league) return@mapNotNull null
            SearchPlayer(
                id = i.str("id") ?: return@mapNotNull null,
                name = i.str("displayName") ?: "",
                league = l,
                team = i.arr("teamRelationships").firstOrNull()?.obj("core")?.let { EspnApi.parseTeam(it) },
                headshot = i.obj("headshot")?.str("href") ?: i.str("headshot"),
                position = i.obj("position")?.str("abbreviation") ?: i.str("position"),
            )
        }
    }

    suspend fun splits(league: League, athleteId: String): List<SplitTable> {
        // This season only: unasked, ESPN serves an injured player's last season as if it were current.
        val season = currentSeason(league)
        val root = EspnApi.get("$COMMON/${league.path}/athletes/$athleteId/splits?season=$season")
        if (root.arr("filters").any { it.str("name") == "season" && it.str("value") != season.toString() }) return emptyList()
        val labels = root.strings("labels")
        return root.arr("splitCategories").map { c ->
            SplitTable(c.str("displayName") ?: "", labels, c.arr("splits").map { (it.str("displayName") ?: "") to it.strings("stats") })
        }.filter { it.rows.isNotEmpty() }
    }

    suspend fun teamStats(league: League, teamId: String): List<TeamStatCategory> {
        val root = EspnApi.get("$SITE/${league.path}/teams/$teamId/statistics")
        return root.obj("results")?.obj("stats").arr("categories").map { c ->
            TeamStatCategory(c.str("displayName") ?: "", c.arr("stats").map { (it.str("displayName") ?: "") to (it.str("displayValue") ?: "") })
        }.filter { it.stats.isNotEmpty() }
    }

    /** Current ESPN "season" year for a league (NBA 2026-27 = 2027, NFL 2026 = 2026). */
    fun currentSeason(league: League): Int {
        val now = LocalDate.now()
        return when (league.kind) {
            SportKind.BASKETBALL, SportKind.HOCKEY -> if (now.monthValue >= 8) now.year + 1 else now.year
            SportKind.FOOTBALL -> if (now.monthValue >= 3) now.year else now.year - 1
            else -> now.year
        }
    }

    /**
     * Every meeting between team A and team B over the last [seasons] seasons (regular season + playoffs),
     * built from team A's schedules. Most recent first.
     */
    suspend fun headToHead(league: League, a: String, b: String, seasons: Int, onProgress: (Float) -> Unit = {}): List<Meeting> = coroutineScope {
        val cur = currentSeason(league)
        val years = (0 until seasons).map { cur - it }
        var done = 0
        val types = listOf(2, 3)   // regular season + postseason
        years.flatMap { y -> types.map { t -> y to t } }.map { (y, t) ->
            async {
                val r = runCatching { EspnApi.get("$SITE/${league.path}/teams/$a/schedule?season=$y&seasontype=$t") }.getOrNull()
                synchronized(this@StatsApi) { done++; onProgress(done.toFloat() / (years.size * types.size)) }
                r?.arr("events").orEmpty().mapNotNull { e -> meeting(e, a, b, y, t == 3) }
            }
        }.awaitAll().flatten().distinctBy { it.eventId }.sortedByDescending { it.dateIso }
    }

    private fun meeting(e: JsonElement, a: String, b: String, year: Int, post: Boolean): Meeting? {
        val comp = e.arr("competitions").firstOrNull() ?: return null
        val cs = comp.arr("competitors")
        val ca = cs.firstOrNull { it.obj("team")?.str("id") == a } ?: return null
        val cb = cs.firstOrNull { it.obj("team")?.str("id") == b } ?: return null
        val state = comp.obj("status")?.obj("type")?.str("state")
        if (state != "post") return null
        fun score(c: JsonElement) = (c.obj("score")?.str("displayValue") ?: c.str("score"))?.toDoubleOrNull()?.toInt()
        val sa = score(ca) ?: return null; val sb = score(cb) ?: return null
        return Meeting(e.str("id") ?: "", e.str("date") ?: "", e.obj("season")?.str("displayName") ?: "$year",
            post, ca.str("homeAway") == "home", sa, sb, comp.arr("notes").firstOrNull()?.str("headline"))
    }

    /** All teams in a league (for pickers). */
    suspend fun teams(league: League): List<Team> =
        EspnApi.get("$SITE/${league.path}/teams?limit=1000").arr("sports").firstOrNull().arr("leagues").firstOrNull().arr("teams")
            .map { EspnApi.parseTeam(it.obj("team")) }.sortedBy { it.displayName }
}
