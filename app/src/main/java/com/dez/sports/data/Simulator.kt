package com.dez.sports.data

import com.dez.sports.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate
import kotlin.math.exp
import kotlin.random.Random

/** A team as the simulator sees it. [p] = chance to beat an average team on a neutral field. */
data class SimTeam(
    val team: Team,
    val conference: String,
    val division: String,
    val wins: Int, val losses: Int, val otl: Int,
    val confWins: Int, val confLosses: Int,
    val p: Double,
    val unlisted: Int,          // remaining games not in the pickable list (simulated vs. an average opponent)
)

data class SimGame(val id: String, val label: String, val away: Team, val home: Team, val conference: Boolean)

data class SimSetup(val league: League, val teams: List<SimTeam>, val games: List<SimGame>, val note: String, val metricLabels: Pair<String, String>)

data class SimTeamResult(val team: Team, val conference: String, val projW: Double, val projL: Double, val playoff: Double, val second: Double)

/**
 * "What if" playoff simulator. Simplified on purpose: records + ESPN power ratings decide each game,
 * real playoff formats decide the field (NFL 7 per conf, NBA top-6 + play-in, NHL 3 per division + 2 WC,
 * CFP 5 conference champions + 7 at-large).
 */
object Simulator {
    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"
    private const val SITE_V2 = "https://site.api.espn.com/apis/v2/sports"
    private const val FITT = "https://site.web.api.espn.com/apis/fitt/v3/sports"
    val supported = listOf(League.NFL, League.NBA, League.NHL, League.CFB)

    private fun logistic(x: Double) = 1.0 / (1.0 + exp(-x))

    private class Entry(val team: Team, val group: String, val stats: Map<String, String>)

    private suspend fun standingsEntries(league: League, query: String): List<Entry> {
        val out = mutableListOf<Entry>()
        fun walk(n: JsonElement, name: String) {
            val es = n.obj("standings").arr("entries")
            es.forEach { e ->
                val m = HashMap<String, String>()
                e.arr("stats").forEach { s ->
                    val v = s.str("displayValue") ?: s.str("summary") ?: s.str("value") ?: return@forEach
                    listOfNotNull(s.str("name"), s.str("type"), s.str("displayName")).forEach { k -> m.putIfAbsent(k, v) }
                    s.str("value")?.let { raw -> s.str("name")?.let { m["${it}#raw"] = raw } }
                }
                out += Entry(EspnApi.parseTeam(e.obj("team")), name, m)
            }
            n.arr("children").forEach { walk(it, it.str("name") ?: name) }
        }
        walk(EspnApi.get("$SITE_V2/${league.path}/standings$query"), "")
        // Standings omit team colours; fill them from the league directory.
        val dir = EspnApi.directory(league)
        return with(EspnApi) { out.map { Entry(it.team.enrich(dir), it.group, it.stats) } }
    }

    private fun rec(s: String?): Pair<Int, Int> = s?.split("-")?.let { (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0) } ?: (0 to 0)

    private suspend fun ratings(league: League): Map<String, Double> {
        val root = EspnApi.get("$FITT/${league.path}/powerindex?limit=1000")
        val cat = if (league.kind == SportKind.FOOTBALL) "fpi" else "bpi"
        val idx = root.arr("categories").firstOrNull { it.str("name") == cat }?.strings("names")?.indexOf(cat) ?: -1
        return root.arr("teams").mapNotNull { t ->
            val id = t.obj("team")?.str("id") ?: return@mapNotNull null
            val v = t.arr("categories").firstOrNull { it.str("name") == cat }?.arr("values")?.getOrNull(idx)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }
            v?.let { id to it }
        }.toMap()
    }

    suspend fun setup(league: League): SimSetup = coroutineScope {
        when (league) {
            League.NFL, League.CFB -> footballSetup(league)
            League.NBA -> basketballSetup()
            League.NHL -> hockeySetup()
            else -> error("Simulator not available for ${league.label}")
        }
    }

    private suspend fun footballSetup(league: League): SimSetup = coroutineScope {
        val nfl = league == League.NFL
        val entriesJob = async { standingsEntries(league, if (nfl) "?level=3" else "?group=80") }
        val ratingJob = async { runCatching { ratings(league) }.getOrDefault(emptyMap()) }
        val board = EspnApi.scoreboard(league)
        val curKey = board.currentWeekKey
        val regular = board.weeks.filter { it.seasonType == "2" }
        val startIdx = regular.indexOfFirst { it.key == curKey }.coerceAtLeast(0)
        val weekBoards = regular.drop(startIdx).map { w -> async { w to runCatching { EspnApi.scoreboard(league, week = w) }.getOrNull() } }.awaitAll()
        val entries = entriesJob.await(); val rating = ratingJob.await()
        val ids = entries.map { it.team.id }.toSet()
        val games = weekBoards.flatMap { (w, b) ->
            b?.games.orEmpty().filter { it.state == GameState.PRE && it.home.team.id in ids && it.away.team.id in ids }
                .map { SimGame(it.id, w.label, it.away.team, it.home.team, it.conferenceGame) }
        }.distinctBy { it.id }
        val teams = entries.distinctBy { it.team.id }.map { e ->
            val (w, l) = if (nfl) (e.stats["wins"]?.toIntOrNull() ?: 0) to (e.stats["losses"]?.toIntOrNull() ?: 0) else rec(e.stats["overall"] ?: e.stats["total"])
            val (cw, cl) = rec(e.stats["vs. Conf."] ?: e.stats["vsconf"])
            val r = rating[e.team.id] ?: 0.0
            val conference = if (nfl) e.group.substringBefore(" ") else e.group
            SimTeam(e.team, conference, e.group, w, l, 0, cw, cl, logistic(r / (if (nfl) 8.0 else 9.0)), 0)
        }
        val note = if (nfl) "Every remaining game is simulated from ESPN's FPI. Pick winners to lock them in."
        else "FBS only. Conference champion = best conference record; CFP = 5 highest-rated champions + 7 at-large."
        SimSetup(league, teams, games, note, if (nfl) "PLAYOFF" to "DIV" else "CFP" to "CONF")
    }

    private suspend fun upcoming(league: League, days: Int): List<SimGame> = coroutineScope {
        (0 until days).map { d -> async { runCatching { EspnApi.scoreboard(league, date = LocalDate.now().plusDays(d.toLong())) }.getOrNull()?.games.orEmpty() } }
            .awaitAll().flatten().filter { it.state == GameState.PRE }
            .map { SimGame(it.id, com.dez.sports.ui.components.localDay(it.dateIso), it.away.team, it.home.team, false) }.distinctBy { it.id }
    }

    private suspend fun basketballSetup(): SimSetup = coroutineScope {
        val entriesJob = async { standingsEntries(League.NBA, "") }
        val ratingJob = async { runCatching { ratings(League.NBA) }.getOrDefault(emptyMap()) }
        val games = upcoming(League.NBA, 7)
        val entries = entriesJob.await(); val rating = ratingJob.await()
        val listed = games.flatMap { listOf(it.home.id, it.away.id) }.groupingBy { it }.eachCount()
        val teams = entries.map { e ->
            val w = e.stats["wins"]?.toIntOrNull() ?: 0; val l = e.stats["losses"]?.toIntOrNull() ?: 0
            SimTeam(e.team, e.group, "", w, l, 0, 0, 0, logistic((rating[e.team.id] ?: 0.0) / 7.0), (82 - w - l - (listed[e.team.id] ?: 0)).coerceAtLeast(0))
        }
        SimSetup(League.NBA, teams, games, "Pick the next 7 days of games; the rest of the 82-game season is simulated from ESPN's BPI. Seeds 7–10 play the play-in.", "PLAYOFF" to "TOP 6")
    }

    private suspend fun hockeySetup(): SimSetup = coroutineScope {
        val entriesJob = async { standingsEntries(League.NHL, "?level=3") }
        val games = upcoming(League.NHL, 7)
        val entries = entriesJob.await()
        val listed = games.flatMap { listOf(it.home.id, it.away.id) }.groupingBy { it }.eachCount()
        val east = setOf("Atlantic Division", "Metropolitan Division")
        val teams = entries.map { e ->
            val w = e.stats["wins"]?.toIntOrNull() ?: 0; val l = e.stats["losses"]?.toIntOrNull() ?: 0
            val otl = (e.stats["otLosses"] ?: e.stats["overtimeLosses"])?.toIntOrNull() ?: 0
            val gp = w + l + otl
            // Regress early-season points% toward .500 so 10 games don't decide everything.
            val pct = if (gp == 0) .5 else ((w + otl * .5) + 10 * .5) / (gp + 10)
            SimTeam(e.team, if (e.group in east) "Eastern" else "Western", e.group, w, l, otl, 0, 0, pct, (82 - gp - (listed[e.team.id] ?: 0)).coerceAtLeast(0))
        }
        SimSetup(League.NHL, teams, games, "Pick the next 7 days; the rest of the season is simulated from each team's points pace. Top 3 per division + 2 wild cards per conference.", "PLAYOFF" to "DIV")
    }

    // ---------------------------------------------------------------- engine

    private fun homeEdge(league: League) = when (league) { League.NBA -> .035; League.NFL -> .03; League.CFB -> .04; else -> .02 }

    fun homeWinProb(setup: SimSetup, g: SimGame): Double {
        val byId = setup.teams.associateBy { it.team.id }
        val ph = byId[g.home.id]?.p ?: .5; val pa = byId[g.away.id]?.p ?: .5
        val log5 = ph * (1 - pa) / (ph * (1 - pa) + pa * (1 - ph))
        return (log5 + homeEdge(setup.league)).coerceIn(.02, .98)
    }

    fun run(setup: SimSetup, picks: Map<String, String>, sims: Int = 3000, seed: Int = 7): List<SimTeamResult> {
        val rnd = Random(seed)
        val teams = setup.teams
        val n = teams.size
        val idx = teams.withIndex().associate { it.value.team.id to it.index }
        val playoff = DoubleArray(n); val second = DoubleArray(n); val wSum = DoubleArray(n); val lSum = DoubleArray(n)
        val hockey = setup.league == League.NHL
        val probs = setup.games.map { homeWinProb(setup, it) }
        val w = IntArray(n); val l = IntArray(n); val ot = IntArray(n); val cw = IntArray(n); val cl = IntArray(n)
        repeat(sims) {
            for (i in 0 until n) { val t = teams[i]; w[i] = t.wins; l[i] = t.losses; ot[i] = t.otl; cw[i] = t.confWins; cl[i] = t.confLosses }
            setup.games.forEachIndexed { gi, g ->
                val h = idx[g.home.id] ?: return@forEachIndexed; val a = idx[g.away.id] ?: return@forEachIndexed
                val homeWins = picks[g.id]?.let { it == g.home.id } ?: (rnd.nextDouble() < probs[gi])
                val (win, lose) = if (homeWins) h to a else a to h
                w[win]++
                if (hockey && rnd.nextDouble() < .23) ot[lose]++ else l[lose]++
                if (g.conference) { cw[win]++; cl[lose]++ }
            }
            for (i in 0 until n) repeat(teams[i].unlisted) {
                if (rnd.nextDouble() < teams[i].p) w[i]++ else if (hockey && rnd.nextDouble() < .23) ot[i]++ else l[i]++
            }
            val jitter = DoubleArray(n) { rnd.nextDouble() * 1e-4 }
            fun pct(i: Int) = if (hockey) (2.0 * w[i] + ot[i]) + jitter[i] else (w[i].toDouble() / (w[i] + l[i]).coerceAtLeast(1)) + jitter[i]
            val (inSet, secondSet) = field(setup, pct = ::pct, confPct = { i -> cw[i].toDouble() / (cw[i] + cl[i]).coerceAtLeast(1) + teams[i].p * 1e-3 + jitter[i] }, rnd = rnd)
            inSet.forEach { playoff[it]++ }; secondSet.forEach { second[it]++ }
            for (i in 0 until n) { wSum[i] += w[i]; lSum[i] += l[i] + ot[i] }
        }
        return teams.indices.map { i ->
            SimTeamResult(teams[i].team, teams[i].conference, wSum[i] / sims, lSum[i] / sims, playoff[i] * 100 / sims, second[i] * 100 / sims)
        }.sortedWith(compareByDescending<SimTeamResult> { it.playoff }.thenByDescending { it.second }.thenByDescending { it.projW })
    }

    /** Returns (made the playoff field, secondary achievement) as team indices for one simulated season. */
    private fun field(setup: SimSetup, pct: (Int) -> Double, confPct: (Int) -> Double, rnd: Random): Pair<Set<Int>, Set<Int>> {
        val teams = setup.teams
        val made = HashSet<Int>(); val second = HashSet<Int>()
        when (setup.league) {
            League.NFL -> teams.indices.groupBy { teams[it].conference }.values.forEach { conf ->
                val divWinners = conf.groupBy { teams[it].division }.values.map { d -> d.maxBy(pct) }
                second += divWinners
                made += divWinners
                made += conf.filter { it !in divWinners }.sortedByDescending(pct).take(3)
            }
            League.NBA -> teams.indices.groupBy { teams[it].conference }.values.forEach { conf ->
                val order = conf.sortedByDescending(pct)
                made += order.take(6); second += order.take(6)
                if (order.size >= 10) {
                    fun game(home: Int, away: Int): Pair<Int, Int> {
                        val ph = teams[home].p * (1 - teams[away].p); val pa = teams[away].p * (1 - teams[home].p)
                        val hw = rnd.nextDouble() < (ph / (ph + pa) + .035)
                        return if (hw) home to away else away to home
                    }
                    val (w78, l78) = game(order[6], order[7])
                    val (w910, _) = game(order[8], order[9])
                    val (eighth, _) = game(l78, w910)
                    made += w78; made += eighth
                }
            }
            League.NHL -> teams.indices.groupBy { teams[it].conference }.values.forEach { conf ->
                val top3 = conf.groupBy { teams[it].division }.values.flatMap { d -> d.sortedByDescending(pct).take(3) }
                made += top3
                second += conf.groupBy { teams[it].division }.values.map { d -> d.maxBy(pct) }
                made += conf.filter { it !in top3 }.sortedByDescending(pct).take(2)
            }
            League.CFB -> {
                // Composite résumé score: winning % plus a nudge from FPI strength.
                fun score(i: Int) = pct(i) * 10 + teams[i].p * 3
                val champs = teams.indices.groupBy { teams[it].conference }.filterKeys { !it.contains("Independent", true) }
                    .values.map { c -> c.maxBy(confPct) }
                second += champs
                val autoBids = champs.sortedByDescending(::score).take(5)
                made += autoBids
                made += teams.indices.filter { it !in autoBids }.sortedByDescending(::score).take(7)
            }
            else -> Unit
        }
        return made to second
    }
}
