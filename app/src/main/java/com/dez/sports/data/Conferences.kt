package com.dez.sports.data

import com.dez.sports.model.Game
import com.dez.sports.model.League
import java.util.concurrent.ConcurrentHashMap

/** A college conference; [id] matches the scoreboard's team.conferenceId. */
data class Conference(val id: String, val name: String, val short: String, val teamIds: Set<String>)

/** College conferences (FBS / Division I) from the standings feed, and the Scores filter built on them. */
object Conferences {
    const val ALL = "all"
    const val TOP25 = "top25"

    private val cache = ConcurrentHashMap<League, List<Conference>>()

    fun filterable(l: League) = l == League.CFB || l == League.CBB

    suspend fun of(l: League): List<Conference> {
        cache[l]?.let { return it }
        val group = if (l == League.CFB) 80 else 50
        val root = EspnApi.get("https://site.api.espn.com/apis/v2/sports/${l.path}/standings?group=$group")
        val list = root.arr("children").mapNotNull { c ->
            val id = c.str("id") ?: return@mapNotNull null
            val name = c.str("name") ?: return@mapNotNull null
            // Sun Belt and friends nest their teams one level down, in divisions.
            val entries = c.obj("standings").arr("entries") + c.arr("children").flatMap { it.obj("standings").arr("entries") }
            Conference(id, name, shortName(name), entries.mapNotNull { it.obj("team")?.str("id") }.toSet())
        }
        if (list.isNotEmpty()) cache[l] = list
        return list
    }

    /** Your teams' games always stay in; otherwise Top 25 (either team ranked) or a conference (either team in it). */
    fun matches(g: Game, filter: String): Boolean = when (filter) {
        ALL -> true
        TOP25 -> g.away.rank != null || g.home.rank != null
        else -> g.away.conferenceId == filter || g.home.conferenceId == filter
    } || Favorites.teams.value.any { it.league == g.league && g.involves(it.teamId) }

    private val shorts = mapOf(
        "Atlantic Coast Conference" to "ACC", "Southeastern Conference" to "SEC", "Mid-American Conference" to "MAC",
        "Conference USA" to "C-USA", "FBS Independents" to "Independents", "Atlantic 10 Conference" to "A-10",
        "Mid-Eastern Athletic Conference" to "MEAC", "Southwestern Athletic Conference" to "SWAC", "Coastal Athletic Association" to "CAA",
        "West Coast Conference" to "WCC", "Missouri Valley Conference" to "MVC", "Atlantic Sun Conference" to "ASUN",
        "Ohio Valley Conference" to "OVC", "United Athletic Conference" to "UAC", "Northeast Conference" to "NEC",
    )

    private fun shortName(name: String) = shorts[name] ?: name.removeSuffix(" Conference").removeSuffix(" League").let { if (it == "Ivy" || it == "Patriot" || it == "Horizon" || it == "Summit") "$it League" else it }
}
