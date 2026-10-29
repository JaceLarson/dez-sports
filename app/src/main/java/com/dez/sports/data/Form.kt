package com.dez.sports.data

import com.dez.sports.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

enum class Heat { HOT, COLD }

/** Recent form against the season: "4-1 in the last 5, +11.2 margin (season +3.0)". */
data class Form(val heat: Heat?, val headline: String, val detail: String)

/**
 * Hot/cold radar: how a team or player has done lately compared with their own season.
 * Everything here is relative to themselves – a bad team on a 4-game win streak is hot.
 */
object Forms {
    private const val WINDOW = 5

    fun team(info: TeamInfo, teamId: String): Form? {
        val done = info.schedule.filter { it.game.state == GameState.POST && it.result != null }.sortedBy { it.game.dateIso }
        if (done.size < 4) return null
        fun margin(it: TeamScheduleItem): Int {
            val me = if (it.game.home.team.id == teamId) it.game.home else it.game.away
            val opp = if (it.game.home.team.id == teamId) it.game.away else it.game.home
            return (me.score?.toIntOrNull() ?: 0) - (opp.score?.toIntOrNull() ?: 0)
        }
        val recent = done.takeLast(WINDOW)
        val wins = recent.count { it.result!!.startsWith("W") }
        val losses = recent.size - wins
        val recentMargin = recent.map(::margin).average()
        val seasonMargin = done.map(::margin).average()
        val streak = done.reversed().let { r -> val first = r.first().result!!.first(); first to r.takeWhile { it.result!!.first() == first }.size }
        val swing = when (done.first().game.league.kind) { SportKind.HOCKEY -> 1.0; SportKind.BASEBALL -> 1.5; SportKind.FOOTBALL -> 7.0; else -> 6.0 }
        val heat = when {
            (wins >= recent.size - 1 && recentMargin > seasonMargin) || (streak.first == 'W' && streak.second >= 4) || recentMargin - seasonMargin >= swing * 1.5 && wins > losses -> Heat.HOT
            (losses >= recent.size - 1 && recentMargin < seasonMargin) || (streak.first == 'L' && streak.second >= 4) || seasonMargin - recentMargin >= swing * 1.5 && losses > wins -> Heat.COLD
            else -> null
        }
        val streakText = "${streak.first}${streak.second}"
        return Form(heat, "$wins-$losses in the last ${recent.size} · $streakText",
            "%+.1f margin lately vs %+.1f on the season".format(recentMargin, seasonMargin))
    }

    /** From a standings row: a 4+ game streak, or 8+ / 2- wins in the last 10. */
    fun standings(columns: List<String>, values: List<String>): Heat? {
        val strk = values.getOrNull(columns.indexOf("STRK")).orEmpty()
        val n = strk.drop(1).toIntOrNull() ?: 0
        if (strk.startsWith("W") && n >= 4) return Heat.HOT
        if (strk.startsWith("L") && n >= 4) return Heat.COLD
        val l10 = values.getOrNull(columns.indexOf("L10"))?.split("-")?.mapNotNull { it.toIntOrNull() }
        if (l10 != null && l10.size >= 2 && l10.sum() >= 8) {
            if (l10[0] >= 8) return Heat.HOT
            if (l10[0] <= 2) return Heat.COLD
        }
        return null
    }

    /** The stat a player's form is judged on, by what their game log has. */
    private val keyStats = listOf("PTS", "YDS", "H", "SV%", "K", "G", "REC", "TOT")

    /** A player's last 5 games against their season average on their headline stat. */
    fun player(log: GameLog): Form? {
        val section = log.sections.firstOrNull() ?: return null
        val rows = section.blocks.flatMap { it.rows }.sortedBy { it.dateIso }
        if (rows.size < 4) return null
        val col = keyStats.firstNotNullOfOrNull { k -> log.labels.indexOf(k).takeIf { it >= 0 } } ?: return null
        val label = log.labels[col]
        val values = rows.mapNotNull { TeamPlayers.num(it.stats.getOrElse(col) { "" }).takeIf(Double::isFinite) }
        if (values.size < 4) return null
        val recent = values.takeLast(WINDOW).average()
        val season = values.average()
        val minBase = when (label) { "PTS" -> 6.0; "YDS" -> 25.0; "SV%" -> 0.5; else -> 0.5 }
        if (season < minBase && recent < minBase) return null
        val ratio = if (season == 0.0) 2.0 else recent / season
        val (hot, cold) = if (label == "SV%") (recent - season >= .02) to (season - recent >= .025) else (ratio >= 1.25) to (ratio <= .75)
        val fmt = if (label == "SV%") "%.3f" else "%.1f"
        return Form(if (hot) Heat.HOT else if (cold) Heat.COLD else null,
            "${fmt.format(recent)} $label over the last ${minOf(WINDOW, values.size)}",
            "Season average ${fmt.format(season)} $label")
    }

    /**
     * Every player's form from the team's last five box scores, for the sports where a handful of games
     * means something (basketball points, hockey points, baseball average). Football plays too few games.
     */
    suspend fun roster(league: League, teamId: String, schedule: List<TeamScheduleItem>, season: List<StatTable>): Map<String, Heat> = coroutineScope {
        if (league.kind == SportKind.FOOTBALL || league.kind == SportKind.GOLF) return@coroutineScope emptyMap()
        val games = schedule.filter { it.game.state == GameState.POST }.sortedBy { it.game.dateIso }.takeLast(WINDOW)
        if (games.size < 3) return@coroutineScope emptyMap()
        val boxes = games.map { g -> async { runCatching { EspnApi.summary(league, g.game.id, final = true) }.getOrNull()?.boxes?.firstOrNull { it.team.id == teamId } } }.awaitAll().filterNotNull()
        // player id -> per-game (value, attempts) for the key stat
        val recent = HashMap<String, MutableList<Pair<Double, Double>>>()
        for (b in boxes) for (t in b.tables) {
            fun col(l: String) = t.labels.indexOf(l)
            for (r in t.rows) {
                val id = r.id ?: continue
                if (r.didNotPlay) continue
                fun v(l: String) = col(l).takeIf { it >= 0 }?.let { TeamPlayers.num(r.stats[it]) }?.takeIf(Double::isFinite)
                val sample = when (league.kind) {
                    SportKind.BASKETBALL -> v("PTS")?.let { it to 1.0 }
                    SportKind.HOCKEY -> if (col("SV%") >= 0) null else ((v("G") ?: 0.0) + (v("A") ?: 0.0)).takeIf { col("G") >= 0 }?.let { it to 1.0 }
                    SportKind.BASEBALL -> if (col("AB") >= 0) v("H")?.let { h -> h to (v("AB") ?: 0.0) } else null
                    else -> null
                } ?: continue
                recent.getOrPut(id) { mutableListOf() } += sample
            }
        }
        val out = HashMap<String, Heat>()
        for (t in season) for (r in t.rows) {
            val id = r.id ?: continue
            val games5 = recent[id] ?: continue
            fun s(l: String) = t.labels.indexOf(l).takeIf { it >= 0 }?.let { TeamPlayers.num(r.stats[it]) }?.takeIf(Double::isFinite)
            val heat = when (league.kind) {
                SportKind.BASKETBALL -> {
                    val base = s("PTS") ?: continue
                    if (games5.size < 3 || base < 6) continue
                    val now = games5.map { it.first }.average()
                    if (now >= base * 1.25 && now - base >= 3) Heat.HOT else if (now <= base * .7 && base - now >= 3) Heat.COLD else null
                }
                SportKind.HOCKEY -> {
                    val gp = s("GP")?.takeIf { it > 0 } ?: continue
                    val base = (s("PTS") ?: continue) / gp
                    if (games5.size < 3) continue
                    val now = games5.map { it.first }.average()
                    if (now >= 1.0 && now >= base + .4) Heat.HOT else if (base >= .6 && now <= base * .3) Heat.COLD else null
                }
                SportKind.BASEBALL -> {
                    val base = s("AVG") ?: continue
                    val ab = games5.sumOf { it.second }
                    if (ab < 10) continue
                    val now = games5.sumOf { it.first } / ab
                    if (now >= base + .100 && now >= .330) Heat.HOT else if (now <= base - .120) Heat.COLD else null
                }
                else -> null
            } ?: continue
            out[id] = heat
        }
        out
    }
}
