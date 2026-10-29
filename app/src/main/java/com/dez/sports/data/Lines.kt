package com.dez.sports.data

import com.dez.sports.model.League
import com.dez.sports.model.Team
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.text.Normalizer
import java.time.Instant

data class LinePlayer(val name: String, val jersey: String?, val pos: String, val injury: String?, val gameTimeDecision: Boolean, val espnId: String?, val headshot: String?) {
    val lastName get() = name.substringAfter(' ', name)
}
data class LineGroup(val id: String, val players: List<LinePlayer>)

/** A team's current line combinations: even-strength lines, goalies, special teams and who's out. */
data class TeamLines(
    val source: String,
    /** True when the lines come from that morning's skate/reporting rather than just the last game. */
    val reported: Boolean,
    val updated: Instant?,
    val forwards: List<LineGroup>,
    val defense: List<LineGroup>,
    val goalies: List<LinePlayer>,
    val powerPlay: List<LineGroup>,
    val penaltyKill: List<LineGroup>,
    val out: List<LinePlayer>,
)

/**
 * NHL line combinations. Neither ESPN nor the NHL publish lines, so they come from Daily Faceoff,
 * which tracks every team's lines from morning skates, beat reporters and the last game.
 */
object Lines {
    private val json = Json { ignoreUnknownKeys = true }
    private val nextData = Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)

    suspend fun load(team: Team): TeamLines? = coroutineScope {
        val roster = async { runCatching { PlayerApi.roster(League.NHL, team.id).flatMap { it.players } }.getOrDefault(emptyList()) }
        val page = withContext(Dispatchers.IO) {
            EspnApi.client.newCall(Request.Builder().url("https://www.dailyfaceoff.com/teams/${slug(team.displayName)}/line-combinations")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 17) DezSports").build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body!!.string()
            }
        }
        val data = json.parseToJsonElement(nextData.find(page)?.groupValues?.get(1) ?: return@coroutineScope null)
        val combos = data.path("props", "pageProps", "combinations") ?: return@coroutineScope null
        val espn = roster.await()
        val byName = espn.associateBy { key(it.name) }
        val byJersey = espn.filter { it.jersey != null }.associateBy { it.jersey }
        val players = combos.arr("players").map { p ->
            val name = p.str("name") ?: ""
            val jersey = p.str("jerseyNumber")
            val match = byName[key(name)] ?: byJersey[jersey]?.takeIf { key(it.name).substringAfterLast(' ') == key(name).substringAfterLast(' ') }
            p.str("groupIdentifier").orEmpty() to LinePlayer(name, jersey, p.str("positionIdentifier").orEmpty(), p.str("injuryStatus"), p.bool("gameTimeDecision"), match?.id, match?.headshot)
        }
        fun groups(prefix: String) = players.filter { it.first.matches(Regex("$prefix\\d")) }.groupBy { it.first }
            .toSortedMap().map { (id, ps) -> LineGroup(id, ps.map { it.second }.sortedBy { order(it.pos) }) }
        val source = combos.str("sourceName") ?: ""
        TeamLines(
            source = source,
            reported = !source.startsWith("Last Game"),
            updated = combos.str("updatedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            forwards = groups("f"), defense = groups("d"),
            goalies = players.filter { it.first == "g" }.map { it.second }.sortedBy { it.pos },
            powerPlay = groups("pp"), penaltyKill = groups("pk"),
            out = players.filter { it.first == "ir" }.map { it.second },
        )
    }

    /** Left wing, centre, right wing; left then right defence; then skater order. */
    private fun order(pos: String) = listOf("lw", "c", "rw", "ld", "rd").indexOf(pos).let { if (it < 0) 10 + (pos.filter(Char::isDigit).toIntOrNull() ?: 0) else it }

    /** "St. Louis Blues" → "st-louis-blues", "Montréal Canadiens" → "montreal-canadiens". */
    fun slug(name: String) = key(name).replace(' ', '-')

    private fun key(name: String) = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        .lowercase().replace(Regex("[^a-z0-9 ]"), "").trim().replace(Regex("\\s+"), " ")
}
