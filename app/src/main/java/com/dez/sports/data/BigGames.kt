package com.dez.sports.data

import com.dez.sports.model.*
import com.dez.sports.ui.components.localDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Network logos. ESPN's scoreboard only links logos for its own channels, so the rest come from its media catalog. */
object Networks {
    private val guids = mapOf(
        "abc" to "2acff74d-269f-36ac-96eb-9c66f8ba52ff", "espn" to "335fd2d2-97b9-336b-81ee-573eb6bdcffc",
        "espn2" to "4bab8fd8-cd13-379f-9558-c3614851bf91", "espnu" to "8c9e5134-a2bf-39f1-9380-3ec6c75ed106",
        "espnews" to "237524b3-a04c-3c80-9550-5a7d889d073a", "espn+" to "322a7e47-3cdb-3b5c-ab1b-678e1f2c27e5",
        "sec network" to "4658b4d2-a8e1-3630-9475-13af31514cfe", "secn" to "4658b4d2-a8e1-3630-9475-13af31514cfe",
        "acc network" to "8936b3f6-e2cb-3924-807b-00f236018174", "accn" to "8936b3f6-e2cb-3924-807b-00f236018174",
        "btn" to "73f43b5f-0e3e-33d2-be22-4f2901536166", "big ten network" to "73f43b5f-0e3e-33d2-be22-4f2901536166",
        "cbs" to "1169b9b9-7729-3ebb-8928-06743f7ceecc", "cbssn" to "3458ed2c-2fba-3ad3-a2c4-b3d98071a4e6",
        "cbs sports network" to "3458ed2c-2fba-3ad3-a2c4-b3d98071a4e6", "paramount+" to "353f9156-780d-3b27-a47b-9356633ce6e6",
        "fox" to "1567408b-ad27-3efd-94cb-b42a34de57e6", "fs1" to "75edea6f-e652-337c-9906-7eda86bbc683",
        "fs2" to "f422a7e7-2be8-34db-8224-65f63b8f93d7", "nbc" to "682b31a2-7a3d-39fe-8d3f-f95358fbafdf",
        "peacock" to "9855b9a2-1dc7-3a5e-804b-871b6a889e8d", "usa net" to "bfa7e9e7-b357-3a9e-849e-dd16f3e62df8",
        "usa network" to "bfa7e9e7-b357-3a9e-849e-dd16f3e62df8", "tnt" to "9ea03d18-1d7a-30ca-9fbf-c10edd81ecd5",
        "trutv" to "08c432b5-8d4c-38d0-97e2-45fecb898bf8", "tbs" to "cba51d04-32be-3d2a-aafa-d54d7592309f",
        "hbo max" to "07a845e5-05c0-3181-9877-5b9714f28ad7", "max" to "07a845e5-05c0-3181-9877-5b9714f28ad7",
        "prime video" to "fa8ca25a-4924-31a8-a7d2-2de0549b5bef", "apple tv" to "70fc3161-b2bb-3d5e-bbf6-f084fb99c246",
        "apple tv+" to "70fc3161-b2bb-3d5e-bbf6-f084fb99c246", "cw" to "1cea662c-18ca-3bbe-a88d-fb4e42bb8ecb",
        "the cw" to "1cea662c-18ca-3bbe-a88d-fb4e42bb8ecb", "nfl net" to "8281947e-bd6c-3a8a-9b1d-6fc957d1709a",
        "nfl network" to "8281947e-bd6c-3a8a-9b1d-6fc957d1709a", "nba tv" to "4bce3453-e476-3711-bf97-8f38240b7b22",
        "mlb net" to "9e01bc96-32bd-31d7-a4c6-40597b0dbab6", "mlb network" to "9e01bc96-32bd-31d7-a4c6-40597b0dbab6",
        "tennis channel" to "33420978-aa6e-3593-8cbb-22ae37921fb5", "telemundo" to "b9c3958e-5853-39a6-a827-c28c0d1b3fb6",
    )

    fun logo(name: String): String? = guids[name.trim().lowercase()]?.let { "https://a.espncdn.com/guid/$it/logos/default.png" }

    /** Catch-all subscription services that carry hundreds of games – not "where everyone's watching". */
    private val notMarquee = setOf("espn+", "nba league pass", "nhl power play", "mlb.tv", "nfl+", "espn3", "accnx", "secn+", "b1g+", "eradm", "espn deportes radio")
    fun isMarquee(c: Channel) = c.national && c.name.lowercase() !in notMarquee
}

data class BigGame(val game: Game, val hype: Int, val tags: List<String>, val channel: Channel?, val mustWatch: Boolean)
data class TvTonight(val big: List<BigGame>, val guide: List<BigGame>)

/**
 * Picks tonight's games worth turning on: only games on national TV (so they can actually be watched),
 * ranked by how good the teams are, how close the line is, playoff stakes and – once live – whether it's close and late.
 */
object BigGames {
    fun pick(all: List<Game>, isFavorite: (Game) -> Boolean, today: LocalDate = LocalDate.now()): TvTonight {
        val onTv = all.filter { g ->
            g.league != League.PGA && (g.state == GameState.LIVE || localDate(g.dateIso) == today) && g.channels.any(Networks::isMarquee)
        }.map(::rate)
        val guide = onTv.sortedWith(compareBy<BigGame>({ it.game.state == GameState.POST }, { it.game.dateIso }, { -it.hype }))
        val open = onTv.filter { it.game.state != GameState.POST && !isFavorite(it.game) }
        val big = open.filter { it.hype >= 40 }.sortedByDescending { it.hype }.take(6)
            .sortedWith(compareBy<BigGame>({ !it.mustWatch }, { it.game.state != GameState.LIVE }, { it.game.dateIso }))
        return TvTonight(big, guide)
    }

    fun rate(g: Game): BigGame {
        val tags = mutableListOf<String>()
        var hype = 10
        val college = g.league == League.CFB || g.league == League.CBB

        // Stakes
        val stakes = g.seriesSummary ?: g.note?.takeIf { n -> listOf("game", "bowl", "championship", "playoff", "final", "tournament", "series").any { n.lowercase().contains(it) } }
        if (stakes != null) { hype += 40; tags += shorten(stakes) }

        // Team quality
        if (college) {
            val ra = g.away.rank; val rh = g.home.rank
            listOfNotNull(ra, rh).forEach { hype += 26 - it }
            if (ra != null && rh != null) { hype += 15; tags += if (ra <= 10 && rh <= 10) "Top-10 Clash" else "Ranked Matchup" }
        } else {
            val a = winPct(g.away.record); val h = winPct(g.home.record)
            if (a != null && h != null) {
                hype += (30 * minOf(a.first, h.first) + 20 * (a.first + h.first) / 2).toInt()
                if (a.second >= 3 && h.second >= 3 && a.first == 1.0 && h.first == 1.0) tags += "Unbeatens Collide"
                else if (a.second >= 5 && h.second >= 5 && a.first >= .65 && h.first >= .65) tags += "Top Teams"
            } else hype += 12
        }

        // Betting line: a coin-flip game is a better watch
        val line = spread(g.odds)
        line?.second?.let { s ->
            hype += when { s <= 3 -> 10; s <= 6 -> 5; s >= 13 -> -10; else -> 0 }
            if (s <= 2.5 && g.state == GameState.PRE) tags += "Toss-Up"
        }

        // Window
        val main = g.channels.firstOrNull(Networks::isMarquee)
        if (main?.name?.lowercase() in setOf("abc", "cbs", "fox", "nbc")) hype += 6
        if (g.league.kind == SportKind.FOOTBALL && etHour(g.dateIso) >= 19) { hype += 6; if (!college) tags += "Primetime" }

        // Live drama
        var mustWatch = false
        if (g.state == GameState.LIVE) {
            val a = g.away.score?.toIntOrNull(); val h = g.home.score?.toIntOrNull()
            if (a != null && h != null) {
                val margin = abs(a - h)
                val (lastPeriod, close) = when (g.league.kind) {
                    SportKind.BASKETBALL -> (if (g.league == League.CBB) 2 else 4) to 8
                    SportKind.FOOTBALL -> 4 to 8
                    SportKind.HOCKEY -> 3 to 1
                    SportKind.BASEBALL -> 7 to 2
                    SportKind.GOLF -> 99 to 0
                }
                val regulation = if (g.league.kind == SportKind.BASEBALL) 9 else lastPeriod
                when {
                    g.period > regulation && margin <= close -> { hype += 45; mustWatch = true; tags.add(0, "Overtime") }
                    g.period >= lastPeriod && margin <= close -> { hype += 40; mustWatch = true; tags.add(0, "Close & Late") }
                    g.period >= lastPeriod && margin > close * 2 -> hype -= 30
                }
                // Underdog leading in the second half
                val fav = line?.first
                if (fav != null && g.period * 2 > lastPeriod) {
                    val favSide = listOf(g.away, g.home).firstOrNull { it.team.abbreviation.equals(fav, true) }
                    val dog = if (favSide == g.away) h to a else a to h
                    if (favSide != null && dog.first > dog.second) { hype += 20; tags.add(0, "Upset Alert") }
                }
            }
        }
        return BigGame(g, hype, tags.distinct().take(2), main, mustWatch)
    }

    /** "12-3" / "5-2-1" → win share (OT losses count as losses) and games played. */
    private fun winPct(rec: String?): Pair<Double, Int>? {
        val n = rec?.split("-")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size >= 2 } ?: return null
        val gp = n.sum()
        return if (gp == 0) null else n[0].toDouble() / gp to gp
    }

    /** "LAL -6.5 · O/U 230" → ("LAL", 6.5); "EVEN" → (null, 0). */
    private fun spread(odds: String?): Pair<String?, Double>? {
        val d = odds?.substringBefore(" · ")?.trim() ?: return null
        if (d.equals("EVEN", true) || d.equals("PK", true)) return null to 0.0
        val m = Regex("^([A-Z0-9&]+)\\s+-([0-9.]+)$").find(d) ?: return null
        return m.groupValues[1] to (m.groupValues[2].toDoubleOrNull() ?: return null)
    }

    private fun etHour(iso: String) = runCatching { Instant.parse(EspnApi.fixIso(iso)).atZone(ZoneId.of("America/New_York")).hour }.getOrDefault(0)

    private fun shorten(s: String) = s.replace("National Championship", "Title Game").let { if (it.length > 28) it.take(26).trimEnd() + "…" else it }
}
