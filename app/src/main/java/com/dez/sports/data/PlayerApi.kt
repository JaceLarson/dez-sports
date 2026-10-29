package com.dez.sports.data

import com.dez.sports.model.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Player profiles, game logs, career stats and rosters (ESPN "common/v3" athlete endpoints). */
object PlayerApi {
    private const val COMMON = "https://site.web.api.espn.com/apis/common/v3/sports"
    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"

    suspend fun athlete(league: League, id: String): Athlete {
        val a = EspnApi.get("$COMMON/${league.path}/athletes/$id").obj("athlete")
        val summary = a.obj("statsSummary")
        return Athlete(
            id = id,
            name = a.str("displayName") ?: "",
            headshot = a.obj("headshot")?.str("href"),
            jersey = a.str("jersey"),
            position = a.obj("position")?.str("abbreviation"),
            team = a.obj("team")?.let { EspnApi.parseTeam(it) },
            height = a.str("displayHeight"),
            weight = a.str("displayWeight"),
            age = a.int("age"),
            birthplace = a.str("displayBirthPlace"),
            draft = a.str("displayDraft"),
            college = a.obj("college")?.str("name"),
            experience = a.str("displayExperience"),
            injury = a.arr("injuries").firstOrNull()?.let { i -> listOfNotNull(i.str("status"), i.obj("details")?.str("type")).joinToString(" · ") },
            summaryTitle = summary?.str("displayName"),
            summary = summary.arr("statistics").map { s ->
                SummaryStat(s.str("abbreviation") ?: s.str("shortDisplayName") ?: "", s.str("displayValue") ?: "–", s.str("rankDisplayValue"))
            },
        )
    }

    suspend fun gameLog(league: League, id: String, season: String? = null): GameLog {
        val root = EspnApi.get("$COMMON/${league.path}/athletes/$id/gamelog" + (season?.let { "?season=$it" } ?: ""))
        val events = root.obj("events") ?: JsonObject(emptyMap())
        fun row(e: JsonElement): GameLogRow? {
            val eid = e.str("eventId") ?: return null
            val meta = events[eid] ?: return null
            return GameLogRow(
                eventId = eid,
                dateIso = meta.str("gameDate") ?: "",
                atVs = meta.str("atVs") ?: "vs",
                opponent = EspnApi.parseTeam(meta.obj("opponent")),
                result = meta.str("gameResult"),
                score = meta.str("score"),
                stats = e.strings("stats"),
            )
        }
        val sections = root.arr("seasonTypes").mapNotNull { st ->
            val blocks = st.arr("categories").mapNotNull { c ->
                val rows = c.arr("events").mapNotNull(::row)
                if (rows.isEmpty()) null
                else GameLogBlock(
                    title = (c.str("displayName") ?: "").replaceFirstChar { it.uppercase() },
                    rows = rows.sortedByDescending { it.dateIso },
                    totals = c.strings("totals").takeIf { it.isNotEmpty() },
                )
            }
            if (blocks.isEmpty()) return@mapNotNull null
            val sum = st.obj("summary").arr("stats")
            GameLogSection(
                title = st.str("displayName") ?: "",
                blocks = blocks,
                averages = sum.firstOrNull { it.str("type") == "avg" }?.strings("stats"),
                totals = sum.firstOrNull { it.str("type") == "total" }?.strings("stats"),
            )
        }
        val seasonFilter = root.arr("filters").firstOrNull { it.str("name") == "season" }
        return GameLog(
            labels = root.strings("labels"),
            descriptions = root.strings("displayNames"),
            groups = root.arr("categories").map { ColumnGroup(it.str("displayName") ?: "", it.int("count") ?: 0) }.filter { it.count > 0 },
            seasons = seasonFilter.arr("options").map { (it.str("value") ?: "") to (it.str("displayValue") ?: "") },
            selectedSeason = seasonFilter?.str("value"),
            sections = sections,
        )
    }

    suspend fun career(league: League, id: String): List<CareerTable> {
        val root = EspnApi.get("$COMMON/${league.path}/athletes/$id/stats")
        val teams = (root.obj("teams") as? JsonObject)?.mapValues { EspnApi.parseTeam(it.value) } ?: emptyMap()
        return root.arr("categories").map { c ->
            CareerTable(
                title = c.str("displayName") ?: "",
                labels = c.strings("labels"),
                descriptions = c.strings("displayNames"),
                rows = c.arr("statistics").map { s ->
                    CareerRow(s.obj("season")?.str("displayName") ?: "", s.str("teamSlug")?.let { teams[it] }, s.strings("stats"))
                }.reversed(),
                totals = c.strings("totals").takeIf { it.isNotEmpty() },
            )
        }.filter { it.rows.isNotEmpty() }
    }

    /** This season's line ("All Splits") for one player, or null if they haven't played. */
    suspend fun seasonLine(league: League, id: String): SeasonLine? {
        // Without a season ESPN falls back to the player's last season with stats, so an injured player
        // who hasn't played all year would show last year's line. Ask for this season and make sure that's what came back.
        val season = StatsApi.currentSeason(league)
        val root = EspnApi.get("$COMMON/${league.path}/athletes/$id/splits?season=$season")
        if (root.arr("filters").any { it.str("name") == "season" && it.str("value") != season.toString() }) return null
        val labels = root.strings("labels")
        val stats = root.arr("splitCategories").firstOrNull()?.arr("splits")?.firstOrNull()?.strings("stats").orEmpty()
        if (labels.isEmpty() || stats.size != labels.size) return null
        return SeasonLine(labels, root.strings("descriptions"), stats)
    }

    suspend fun roster(league: League, teamId: String): List<RosterGroup> {
        val root = EspnApi.get("$SITE/${league.path}/teams/$teamId/roster")
        fun player(a: JsonElement) = RosterPlayer(
            id = a.str("id") ?: "",
            name = a.str("displayName") ?: a.str("fullName") ?: "",
            jersey = a.str("jersey"),
            position = a.obj("position")?.str("abbreviation"),
            headshot = a.obj("headshot")?.str("href"),
            detail = listOfNotNull(a.str("displayHeight"), a.str("displayWeight"), a.obj("experience")?.int("years")?.let { if (it == 0) "Rookie" else "Yr ${it + 1}" }, a.obj("college")?.str("shortName")).joinToString(" · ").ifEmpty { null },
            injured = a.arr("injuries").isNotEmpty(),
        )
        val athletes = root.arr("athletes")
        // Football/hockey/baseball rosters come grouped by unit; basketball is flat.
        return if (athletes.firstOrNull()?.obj("items") != null || athletes.firstOrNull().arr("items").isNotEmpty())
            athletes.map { g -> RosterGroup(g.str("position")?.replaceFirstChar { it.uppercase() }, g.arr("items").map(::player)) }
        else listOf(RosterGroup(null, athletes.map(::player)))
    }
}
