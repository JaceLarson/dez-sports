package com.dez.sports.data

import com.dez.sports.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object EspnApi {
    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"
    private const val SITE_V2 = "https://site.api.espn.com/apis/v2/sports"
    private const val FITT = "https://site.web.api.espn.com/apis/fitt/v3/sports"

    lateinit var client: OkHttpClient
        private set

    fun init(cacheDir: File) {
        client = OkHttpClient.Builder()
            .dns(ResilientDns)
            // Season-wide features fan out to dozens of requests; don't let them queue the screen's own data.
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 48; maxRequestsPerHost = 16 })
            .cache(Cache(File(cacheDir, "espn"), 50L * 1024 * 1024))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** [final]: the response can't change (a finished game), so any copy on disk is used without asking ESPN again. */
    internal suspend fun get(url: String, final: Boolean = false): JsonElement = withContext(Dispatchers.IO) {
        if (final) client.newCall(Request.Builder().url(url).cacheControl(okhttp3.CacheControl.FORCE_CACHE).build()).execute().use { r ->
            if (r.isSuccessful) return@withContext json.parseToJsonElement(r.body!!.string())
        }
        try {
            fetch(url)
        } catch (e: java.io.IOException) {
            // Offline: fall back to the last response we cached, if any.
            client.newCall(Request.Builder().url(url).cacheControl(okhttp3.CacheControl.FORCE_CACHE).build()).execute().use { r ->
                if (!r.isSuccessful) throw e
                json.parseToJsonElement(r.body!!.string())
            }
        }
    }

    /** A dropped connection or a hiccup in the phone's DNS usually clears up within a second or two. */
    private suspend fun fetch(url: String): JsonElement {
        var attempt = 0
        while (true) {
            try {
                return client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
                    if (r.code >= 500 && attempt < 2) throw java.io.IOException("HTTP ${r.code}")
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    json.parseToJsonElement(r.body!!.string())
                }
            } catch (e: java.io.IOException) {
                if (++attempt > 2) throw e
                kotlinx.coroutines.delay(500L * attempt * attempt)
            }
        }
    }

    private const val NO_COLOR = 0xFF333333

    val dateParam: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    // ---------------------------------------------------------------- scoreboard

    suspend fun scoreboard(league: League, date: LocalDate? = null, week: WeekOption? = null): Scoreboard {
        val q = buildList {
            if (league.scoreboardQuery.isNotEmpty()) add(league.scoreboardQuery)
            if (week != null) { add("week=${week.week}"); add("seasontype=${week.seasonType}") }
            else if (date != null) add("dates=${date.format(dateParam)}")
        }.joinToString("&")
        val root = get("$SITE/${league.path}/scoreboard" + if (q.isEmpty()) "" else "?$q")
        if (league == League.PGA) {
            return Scoreboard(emptyList(), golf = root.arr("events").map { parseGolf(it) })
        }
        val games = root.arr("events").mapNotNull { runCatching { parseEvent(league, it) }.getOrNull() }
        val weeks = if (league.weekly) parseWeeks(root) else emptyList()
        val cur = root.obj("week")?.int("number")?.let { n ->
            val st = root.obj("season")?.int("type") ?: 2
            "$st-$n"
        }
        return Scoreboard(games, weeks = weeks, currentWeekKey = cur)
    }

    private fun parseWeeks(root: JsonElement): List<WeekOption> {
        val cal = root.arr("leagues").firstOrNull().arr("calendar")
        return cal.flatMap { section ->
            val st = section.str("value") ?: return@flatMap emptyList()
            if (st == "4") return@flatMap emptyList() // off season
            section.arr("entries").map { e ->
                val label = e.str("label") ?: ""
                val alt = e.str("alternateLabel")
                WeekOption("$st-${e.str("value")}", if (st == "1") (alt ?: label) else label.replace("Week ", "Wk "), e.str("detail") ?: "", st, e.str("value") ?: "1")
            }
        }
    }

    fun parseTeam(t: JsonElement?): Team {
        val logos = t.arr("logos")
        val logo = t.str("logo") ?: logos.firstOrNull()?.str("href")
        val dark = logos.firstOrNull { l -> l.strings("rel").contains("dark") }?.str("href")
        return Team(
            id = t.str("id") ?: "",
            name = t.str("name") ?: t.str("shortDisplayName") ?: t.str("displayName") ?: "",
            location = t.str("location") ?: "",
            displayName = t.str("displayName") ?: "",
            abbreviation = t.str("abbreviation") ?: "",
            color = parseColor(t.str("color"), NO_COLOR),
            altColor = parseColor(t.str("alternateColor"), 0xFF666666),
            logo = logo,
            darkLogo = dark,
        )
    }

    private fun parseColor(hex: String?, fallback: Long): Long =
        hex?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it } ?: fallback

    private fun parseSide(c: JsonElement): Side {
        val rank = c.obj("curatedRank")?.int("current")?.takeIf { it in 1..25 }
        val record = c.arr("records").firstOrNull()?.str("summary") ?: c.arr("record").firstOrNull()?.str("displayValue")
        val score = c.str("score") ?: c.obj("score")?.str("displayValue")
        return Side(
            team = parseTeam(c.obj("team")),
            score = score,
            record = record,
            rank = rank,
            winner = c.bool("winner"),
            homeAway = c.str("homeAway") ?: "",
            linescores = c.arr("linescores").map { it.str("displayValue") ?: it.str("value")?.substringBefore(".0") ?: "" },
        )
    }

    fun parseEvent(league: League, e: JsonElement): Game {
        val comp = e.arr("competitions").first()
        val status = comp.obj("status") ?: e.obj("status")
        val type = status.obj("type")
        val state = when (type.str("state")) { "in" -> GameState.LIVE; "post" -> GameState.POST; else -> GameState.PRE }
        val comps = comp.arr("competitors")
        val away = parseSide(comps.first { it.str("homeAway") == "away" })
        val home = parseSide(comps.first { it.str("homeAway") == "home" })
        val sit = comp.obj("situation")
        val broadcasts = buildList {
            comp.arr("broadcasts").forEach { b -> b.strings("names").forEach { add(it) } }
            if (isEmpty()) comp.arr("geoBroadcasts").forEach { g -> g.obj("media")?.str("shortName")?.let { add(it) } }
        }.distinct()
        val channels = comp.arr("geoBroadcasts").mapNotNull { g ->
            if (g.obj("type")?.str("shortName").equals("Radio", true)) return@mapNotNull null
            val name = g.obj("media")?.str("shortName") ?: return@mapNotNull null
            Channel(name, national = g.obj("market")?.str("type") == "National", streaming = g.obj("type")?.str("shortName") == "Streaming")
        }.distinctBy { it.name }
        val odds = comp.arr("odds").firstOrNull()?.let { o ->
            if (state == GameState.PRE) Upsets.lineFromScoreboard(o)?.let { Upsets.remember(e.str("id") ?: "", it) }
            listOfNotNull(o.str("details"), o.str("overUnder")?.let { "O/U $it" }).joinToString(" · ").ifEmpty { null }
        }
        // Leaders live per competitor (NBA/NHL/MLB) or per competition (NFL); collect both, tagged by team.
        fun leader(cat: JsonElement, teamId: String?): Leader? {
            val l = cat.arr("leaders").firstOrNull() ?: return null
            val ath = l.obj("athlete")
            val abbr = (cat.str("abbreviation") ?: cat.str("shortDisplayName") ?: "").uppercase()
            val value = l.str("displayValue") ?: ""
            return Leader(
                category = abbr,
                player = ath?.str("shortName") ?: ath?.str("displayName") ?: "",
                line = if (value.toDoubleOrNull() != null) "$value $abbr" else value,
                headshot = ath?.obj("headshot")?.str("href") ?: ath?.str("headshot"),
                teamId = l.obj("team")?.str("id") ?: teamId,
                athleteId = ath?.str("id"),
            )
        }
        val leaders = (comp.arr("leaders").mapNotNull { leader(it, null) } +
            comp.arr("competitors").flatMap { c -> c.arr("leaders").mapNotNull { leader(it, c.obj("team")?.str("id")) } })
            .distinctBy { Triple(it.category, it.teamId, it.athleteId) }
        val football = if (league.kind == SportKind.FOOTBALL && sit != null && state == GameState.LIVE) FootballSituation(
            downDistance = sit.str("downDistanceText") ?: sit.str("shortDownDistanceText"),
            possessionTeamId = sit.str("possession"),
            isRedZone = sit.bool("isRedZone"),
            spot = sit.str("possessionText"),
            lastPlay = sit.obj("lastPlay")?.str("text"),
        ) else null
        val baseball = if (league.kind == SportKind.BASEBALL && sit != null && state == GameState.LIVE) BaseballSituation(
            balls = sit.int("balls") ?: 0, strikes = sit.int("strikes") ?: 0, outs = sit.int("outs") ?: 0,
            onFirst = sit.bool("onFirst") || sit.obj("onFirst") != null,
            onSecond = sit.bool("onSecond") || sit.obj("onSecond") != null,
            onThird = sit.bool("onThird") || sit.obj("onThird") != null,
            batter = sit.obj("batter")?.obj("athlete")?.str("shortName"),
            pitcher = sit.obj("pitcher")?.obj("athlete")?.str("shortName"),
            lastPlay = sit.obj("lastPlay")?.str("text"),
        ) else null
        val note = comp.arr("notes").firstOrNull()?.str("headline")
        return Game(
            id = e.str("id") ?: "",
            league = league,
            dateIso = e.str("date") ?: comp.str("date") ?: "",
            state = state,
            statusDetail = type.str("detail") ?: "",
            statusShort = type.str("shortDetail") ?: type.str("detail") ?: "",
            clock = status.str("displayClock"),
            period = status.int("period") ?: 0,
            away = away,
            home = home,
            broadcasts = broadcasts,
            venue = comp.obj("venue")?.str("fullName"),
            note = note,
            seriesSummary = comp.obj("series")?.str("summary"),
            odds = odds,
            football = football,
            baseball = baseball,
            lastPlay = sit?.obj("lastPlay")?.str("text"),
            leaders = leaders,
            conferenceGame = comp.bool("conferenceCompetition"),
            channels = channels,
            isTbd = comp.bool("timeValid").not() && state == GameState.PRE && e.str("date")?.endsWith("T04:00Z") == true,
        )
    }

    private fun parseGolf(e: JsonElement): GolfEvent {
        val comp = e.arr("competitions").firstOrNull()
        val status = comp.obj("status") ?: e.obj("status")
        val state = when (status.obj("type").str("state")) { "in" -> GameState.LIVE; "post" -> GameState.POST; else -> GameState.PRE }
        val players = comp.arr("competitors").map { c ->
            val ath = c.obj("athlete")
            val rounds = c.arr("linescores").mapNotNull { r -> r.str("value")?.substringBefore(".0")?.takeIf { r.arr("linescores").isNotEmpty() || it != "0" } }
            val curRound = c.arr("linescores").lastOrNull { it.arr("linescores").isNotEmpty() }
            val holes = curRound.arr("linescores").size
            GolfPlayer(
                position = c.obj("status")?.obj("position")?.str("displayName") ?: c.str("order") ?: "",
                name = ath.str("displayName") ?: "",
                flag = ath.obj("flag")?.str("href"),
                toPar = c.str("score") ?: "E",
                today = curRound?.str("displayValue"),
                thru = c.obj("status")?.str("thru")?.let { if (it == "18") "F" else it } ?: if (holes == 18) "F" else if (holes > 0) "$holes" else null,
                rounds = rounds,
            )
        }
        val fmt = DateTimeFormatter.ofPattern("MMM d")
        val range = runCatching {
            val s = Instant.parse(fixIso(e.str("date")!!)).atZone(ZoneId.systemDefault())
            val en = e.str("endDate")?.let { Instant.parse(fixIso(it)).atZone(ZoneId.systemDefault()) }
            if (en != null) "${s.format(fmt)} – ${en.format(fmt)}" else s.format(fmt)
        }.getOrDefault("")
        return GolfEvent(
            id = e.str("id") ?: "",
            name = e.str("name") ?: "",
            state = state,
            detail = status.obj("type").str("detail") ?: "",
            dateRange = range,
            broadcasts = comp.arr("broadcasts").flatMap { it.strings("names") }.distinct(),
            venue = e.arr("venues").firstOrNull()?.str("fullName") ?: comp.obj("venue")?.str("fullName"),
            players = players,
        )
    }

    /** One game, same shape as a scoreboard event (~20 KB) – used for lock-screen live scores. */
    suspend fun event(league: League, eventId: String): Game = parseEvent(league, get("$SITE/${league.path}/scoreboard/$eventId"))

    // ---------------------------------------------------------------- game summary

    /** [final]: the game is known to be over, so a copy from disk is as good as a fresh one. */
    suspend fun summary(league: League, eventId: String, final: Boolean = false): GameDetail = withContext(Dispatchers.Default) { summaryOf(league, eventId, final) }

    private suspend fun summaryOf(league: League, eventId: String, final: Boolean): GameDetail {
        val root = get("$SITE/${league.path}/summary?event=$eventId", final)
        val header = root.obj("header")
        val hc = header.arr("competitions").first()
        // Header lacks a few scoreboard bits; synthesise an "event" for parseEvent.
        val event = JsonObject(mapOf("id" to (header.obj("id") ?: kotlinx.serialization.json.JsonPrimitive(eventId)), "competitions" to kotlinx.serialization.json.JsonArray(listOf(hc)), "date" to (hc.path("date") ?: kotlinx.serialization.json.JsonPrimitive(""))))
        var game = parseEvent(league, event)
        val gi = root.obj("gameInfo")
        game = game.copy(venue = gi.obj("venue")?.str("fullName") ?: game.venue)
        val teamsById = buildMap {
            root.obj("boxscore").arr("teams").forEach { val t = parseTeam(it.obj("team")); put(t.id, t) }
            put(game.away.team.id, game.away.team); put(game.home.team.id, game.home.team)
        }

        val boxes = root.obj("boxscore").arr("players").map { p ->
            val team = teamsById[p.obj("team")?.str("id")] ?: parseTeam(p.obj("team"))
            TeamBox(team, p.arr("statistics").mapNotNull { s -> parseTable(s, league) })
        }.sortedBy { if (it.team.id == game.away.team.id) 0 else 1 }

        val teamStats = parseTeamStats(root, game)

        val plays = root.arr("plays").map { parsePlay(it, league) }
        val drives = root.obj("drives").arr("previous").map { d ->
            Drive(
                teamId = d.obj("team")?.str("id"),
                description = d.str("description") ?: "",
                result = d.str("displayResult") ?: d.str("result") ?: "",
                plays = d.arr("plays").map { parsePlay(it, league) },
            )
        }
        val current = root.obj("drives").obj("current")?.let { d ->
            Drive(d.obj("team")?.str("id"), d.str("description") ?: "", "In progress", d.arr("plays").map { parsePlay(it, league) })
        }
        val allDrives = if (current != null && drives.none { it.description == current.description }) drives + current else drives
        val scoring = root.arr("scoringPlays").map { parsePlay(it, league) }.ifEmpty {
            (plays.ifEmpty { allDrives.flatMap { it.plays } }).filter { it.scoring }
        }

        // Order win-probability points by play sequence: ESPN appends late corrections out of order.
        val playOrder = HashMap<String, Int>()
        (root.arr("plays") + root.obj("drives").arr("previous").flatMap { it.arr("plays") } + root.obj("drives").obj("current").arr("plays"))
            .forEach { pl -> pl.str("id")?.let { id -> playOrder.putIfAbsent(id, playOrder.size) } }
        val wpRaw = root.arr("winprobability")
        val wpSorted = if (playOrder.isEmpty()) wpRaw else wpRaw.filter { it.str("playId") in playOrder }.sortedBy { playOrder[it.str("playId")] }
        val wp = wpSorted.mapNotNull { it.float("homeWinPercentage")?.let { p -> WinProbPoint(p) } }
        val shots = plays.mapNotNull { it.shot }

        val predictor = root.obj("predictor")?.let { p ->
            val h = p.obj("homeTeam")?.float("gameProjection"); val a = p.obj("awayTeam")?.float("gameProjection")
            if (h != null && a != null) a to h else null
        }

        val pick = root.arr("pickcenter").firstOrNull()
        val oddsDetail = buildList {
            pick?.let { o ->
                o.obj("provider")?.str("name")?.let { add("Provider" to it) }
                o.str("details")?.let { add("Line" to it) }
                o.str("overUnder")?.let { add("Over/Under" to it) }
                o.obj("awayTeamOdds")?.str("moneyLine")?.let { add("${game.away.team.abbreviation} ML" to signed(it)) }
                o.obj("homeTeamOdds")?.str("moneyLine")?.let { add("${game.home.team.abbreviation} ML" to signed(it)) }
            }
        }

        val injuries = root.arr("injuries").flatMap { t ->
            val tid = t.obj("team")?.str("id") ?: ""
            t.arr("injuries").map { i ->
                Injury(tid, i.obj("athlete")?.str("displayName") ?: "", i.str("status") ?: "", i.obj("details")?.str("type") ?: i.obj("type")?.str("description"))
            }
        }

        val leaders = root.arr("leaders").flatMap { t ->
            val tid = t.obj("team")?.str("id")
            t.arr("leaders").mapNotNull { cat ->
                val l = cat.arr("leaders").firstOrNull() ?: return@mapNotNull null
                Leader(cat.str("displayName") ?: cat.str("name") ?: "", l.obj("athlete")?.str("displayName") ?: "", l.str("displayValue") ?: "", l.obj("athlete")?.obj("headshot")?.str("href"), tid, l.obj("athlete")?.str("id"))
            }
        }

        val article = root.obj("article")
        val recapBody = article?.str("story")?.let { stripHtml(it) }?.take(1400)

        return GameDetail(
            game = game,
            boxes = boxes,
            teamStats = teamStats,
            plays = plays.ifEmpty { allDrives.flatMap { it.plays } },
            scoringPlays = scoring,
            drives = allDrives,
            winProb = wp,
            shots = shots,
            officials = gi.arr("officials").mapNotNull { it.str("displayName") },
            attendance = gi.int("attendance")?.takeIf { it > 0 },
            venue = gi.obj("venue")?.str("fullName"),
            venueCity = gi.obj("venue")?.obj("address")?.let { listOfNotNull(it.str("city"), it.str("state")).joinToString(", ") },
            venueImage = gi.obj("venue")?.arr("images")?.firstOrNull()?.str("href"),
            injuries = injuries,
            seasonSeries = root.arr("seasonseries").firstOrNull()?.str("summary"),
            recapHeadline = article?.str("headline"),
            recapBody = recapBody,
            pregameWinPct = predictor,
            jerseys = root.arr("rosters").flatMap { it.arr("roster") }.mapNotNull { e ->
                val id = e.obj("athlete")?.str("id") ?: return@mapNotNull null
                (e.str("jersey") ?: e.obj("athlete")?.str("jersey"))?.let { id to it }
            }.toMap(),
            oddsDetail = oddsDetail,
            leaders = leaders,
        )
    }

    private fun signed(s: String) = s.substringBefore(".0").let { if (it.startsWith("-") || it.startsWith("+")) it else "+$it" }

    private fun stripHtml(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&quot;", "\"").replace("&#39;", "'").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

    private fun parseTable(s: JsonElement, league: League): StatTable? {
        val labels = s.strings("labels")
        val athletes = s.arr("athletes")
        if (labels.isEmpty() || athletes.isEmpty()) return null
        val title = s.str("name")?.let(::prettyName) ?: s.str("type")?.let(::prettyName)
            ?: if (league.kind == SportKind.BASKETBALL) "Players" else "Stats"
        val rows = athletes.map { a ->
            val ath = a.obj("athlete")
            StatRow(
                id = ath.str("id"),
                name = ath.str("displayName") ?: "",
                shortName = ath.str("shortName") ?: ath.str("displayName") ?: "",
                position = ath.obj("position")?.str("abbreviation") ?: a.obj("position")?.str("abbreviation"),
                jersey = ath.str("jersey"),
                headshot = ath.obj("headshot")?.str("href"),
                starter = a.bool("starter"),
                didNotPlay = a.bool("didNotPlay"),
                reason = a.str("reason"),
                stats = a.strings("stats"),
            )
        }
        return StatTable(title, labels, s.strings("descriptions"), rows, s.strings("totals").takeIf { it.any { v -> v.isNotEmpty() } })
    }

    private fun prettyName(n: String) = when (n) {
        "defensive" -> "Defense"; "kickReturns" -> "Kick Returns"; "puntReturns" -> "Punt Returns"
        "defenses" -> "Defense"; "forwards" -> "Forwards"; "goalies" -> "Goalies"
        else -> n.replaceFirstChar { it.uppercase() }
    }

    private fun parseTeamStats(root: JsonElement, game: Game): List<TeamStatLine> {
        val teams = root.obj("boxscore").arr("teams")
        if (teams.size < 2) return emptyList()
        fun flat(t: JsonElement): List<Pair<String, String>> = t.arr("statistics").flatMap { s ->
            val nested = s.arr("stats")
            if (nested.isNotEmpty()) nested.map { (it.str("displayName") ?: it.str("abbreviation") ?: "") to (it.str("displayValue") ?: "") }
            else listOf((s.str("label") ?: s.str("displayName") ?: "") to (s.str("displayValue") ?: ""))
        }.filter { it.first.isNotEmpty() }
        val a = teams.firstOrNull { it.obj("team")?.str("id") == game.away.team.id } ?: teams[0]
        val h = teams.firstOrNull { it.obj("team")?.str("id") == game.home.team.id } ?: teams[1]
        val hm = flat(h).toMap()
        val skip = setOf("Games Played", "Team Games Played", "GP", "G")
        return flat(a).filter { it.first !in skip }.distinctBy { it.first }.mapNotNull { (k, v) -> hm[k]?.let { TeamStatLine(k, v, it) } }
    }

    private fun parsePlay(p: JsonElement, league: League): Play {
        val per = p.obj("period")
        val periodLabel = per.str("displayValue") ?: per.int("number")?.let { n ->
            when (league.kind) {
                SportKind.FOOTBALL, SportKind.BASKETBALL -> if (league == League.CBB) (if (n <= 2) "${ordinal(n)} Half" else "OT") else if (n <= 4) "${ordinal(n)} Quarter" else "OT"
                SportKind.HOCKEY -> if (n <= 3) "${ordinal(n)} Period" else "OT"
                else -> ordinal(n)
            }
        } ?: ""
        val coord = p.obj("coordinate")
        val shot = if (league.kind == SportKind.BASKETBALL && p.bool("shootingPlay") && coord != null) {
            val x = coord.float("x"); val y = coord.float("y")
            if (x != null && y != null && x in -5f..55f && y in -5f..95f && !(p.obj("type")?.str("text") ?: "").contains("Free Throw", true))
                Shot(x, y, p.bool("scoringPlay"), p.obj("team")?.str("id")) else null
        } else null
        val typeText = p.obj("type")?.str("text")
        val football = if (league.kind == SportKind.FOOTBALL && p.obj("start") != null) {
            val st = p.obj("start"); val en = p.obj("end")
            FootballPlay(
                startYtg = st.int("yardsToEndzone"), endYtg = en.int("yardsToEndzone"),
                downText = st.str("downDistanceText"), offenseId = st.obj("team")?.str("id"),
                yards = p.int("statYardage"), isScore = p.bool("scoringPlay"), turnover = p.bool("isTurnover"),
            )
        } else null
        val pitch = p.obj("pitchCoordinate")?.let { c ->
            val x = c.float("x"); val y = c.float("y")
            if (x == null || y == null) null
            else Pitch(x, y, p.obj("pitchType")?.str("text"), p.int("pitchVelocity"),
                p.obj("resultCount")?.int("balls") ?: 0, p.obj("resultCount")?.int("strikes") ?: 0, typeText ?: "")
        }
        val rink = if (league.kind == SportKind.HOCKEY && coord != null) {
            val kind = when (typeText) { "Goal" -> RinkKind.GOAL; "Shot" -> RinkKind.SHOT; "Missed" -> RinkKind.MISS; "Blocked" -> RinkKind.BLOCK; else -> null }
            val x = coord.float("x"); val y = coord.float("y")
            if (kind != null && x != null && y != null && x in -100f..100f) RinkEvent(x, y, kind) else null
        } else null
        return Play(
            text = p.str("text") ?: typeText ?: "",
            period = periodLabel,
            clock = p.obj("clock")?.str("displayValue"),
            awayScore = p.int("awayScore"),
            homeScore = p.int("homeScore"),
            scoring = p.bool("scoringPlay"),
            teamId = p.obj("team")?.str("id") ?: p.obj("start")?.obj("team")?.str("id"),
            shot = shot,
            type = typeText,
            periodNumber = per.int("number") ?: 0,
            half = per.str("type")?.takeIf { league.kind == SportKind.BASEBALL },
            football = football,
            pitch = pitch,
            atBatId = p.str("atBatId"),
            rink = rink,
            athletes = p.arr("participants").mapNotNull { it.obj("athlete")?.str("id") },
            roles = p.arr("participants").filter { it.obj("athlete")?.str("id") != null }.map { it.str("type") },
        )
    }

    private fun ordinal(n: Int) = "$n" + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }

    // ---------------------------------------------------------------- standings

    /** [divisions] = true asks ESPN for the deepest grouping (e.g. NHL Central, NFC North). */
    suspend fun standings(league: League, divisions: Boolean = false): List<StandingsGroup> {
        val dir = directory(league)
        return standingsRaw(league, divisions).map { g -> g.copy(rows = g.rows.map { it.copy(team = it.team.enrich(dir)) }) }
    }

    private suspend fun standingsRaw(league: League, divisions: Boolean): List<StandingsGroup> {
        val extra = when {
            league == League.CFB -> "?group=80"
            league == League.CBB -> "?group=50"
            divisions -> "?level=3"
            else -> ""
        }
        val root = get("$SITE_V2/${league.path}/standings$extra")
        val groups = mutableListOf<StandingsGroup>()
        fun walk(node: JsonElement, prefix: String?) {
            val name = node.str("name") ?: ""
            val st = node.obj("standings")
            val entries = st.arr("entries")
            if (entries.isNotEmpty()) groups += parseStandings(league, if (prefix != null && league.kind != SportKind.BASKETBALL) name else name, entries)
            node.arr("children").forEach { walk(it, name) }
        }
        walk(root, null)
        return groups
    }

    private fun parseStandings(league: League, name: String, entries: List<JsonElement>): StandingsGroup {
        // Choose a compact, sport-appropriate set of columns.
        val cols: List<Pair<String, List<String>>> = when (league.kind) {
            SportKind.HOCKEY -> listOf("GP" to listOf("gamesPlayed"), "W" to listOf("wins"), "L" to listOf("losses"), "OTL" to listOf("otLosses", "overtimeLosses"), "PTS" to listOf("points"), "DIFF" to listOf("pointDifferential", "differential"), "STRK" to listOf("streak"))
            SportKind.FOOTBALL -> if (league == League.CFB) listOf("CONF" to listOf("vs. Conf."), "OVR" to listOf("overall"), "PF" to listOf("pointsFor"), "PA" to listOf("pointsAgainst"), "STRK" to listOf("streak"))
                else listOf("W" to listOf("wins"), "L" to listOf("losses"), "T" to listOf("ties"), "PCT" to listOf("winPercent"), "DIV" to listOf("divisionRecord", "vs. Div."), "DIFF" to listOf("pointDifferential", "differential"), "STRK" to listOf("streak"))
            SportKind.BASEBALL -> listOf("W" to listOf("wins"), "L" to listOf("losses"), "PCT" to listOf("winPercent"), "GB" to listOf("gamesBehind"), "L10" to listOf("Last Ten Games"), "STRK" to listOf("streak"))
            else -> if (league == League.CBB) listOf("CONF" to listOf("vs. Conf."), "OVR" to listOf("overall"), "STRK" to listOf("streak"))
                else listOf("W" to listOf("wins"), "L" to listOf("losses"), "PCT" to listOf("winPercent"), "GB" to listOf("gamesBehind"), "HOME" to listOf("Home"), "AWAY" to listOf("Road"), "L10" to listOf("Last Ten Games"), "STRK" to listOf("streak"))
        }
        val rows = entries.map { e ->
            val stats = e.arr("stats")
            fun find(keys: List<String>): String = keys.firstNotNullOfOrNull { k ->
                stats.firstOrNull { it.str("name") == k || it.str("type") == k || it.str("abbreviation") == k || it.str("displayName") == k }?.let { it.str("displayValue") ?: it.str("summary") }
            } ?: "–"
            val seed = stats.firstOrNull { it.str("name") == "playoffSeed" }?.str("displayValue")
            val clinch = stats.firstOrNull { it.str("name") == "clincher" }?.str("displayValue")
            StandingsRow(parseTeam(e.obj("team")), seed, cols.map { find(it.second) }, clinch)
        }.let { r ->
            val seeded = r.all { it.seed?.toIntOrNull() != null }
            if (seeded) r.sortedBy { it.seed!!.toInt() } else r
        }
        return StandingsGroup(name, cols.map { it.first }, rows)
    }

    // ---------------------------------------------------------------- rankings

    suspend fun rankings(league: League): List<Poll> {
        val root = get("$SITE/${league.path}/rankings")
        return root.arr("rankings").filter { r ->
            val n = r.str("name") ?: ""
            !n.contains("FCS") && !n.contains("Division II") && !n.contains("Division III")
        }.map { r ->
            Poll(
                name = r.str("name") ?: "",
                headline = r.str("headline"),
                entries = r.arr("ranks").map { k ->
                    PollEntry(
                        rank = k.int("current") ?: 0,
                        previous = k.int("previous") ?: 0,
                        team = parseTeam(k.obj("team")),
                        record = k.str("recordSummary"),
                        points = k.str("points")?.substringBefore(".0"),
                        firstPlaceVotes = k.int("firstPlaceVotes") ?: 0,
                        trend = k.str("trend") ?: "-",
                    )
                },
                others = r.arr("others").take(12).joinToString(", ") { o -> "${o.obj("team")?.str("nickname") ?: o.obj("team")?.str("location")} ${o.str("points")?.substringBefore(".0")}" }.ifEmpty { null },
            )
        }
    }

    // ---------------------------------------------------------------- projections (ESPN FPI / BPI)

    suspend fun projections(league: League): List<ProjectionTable> {
        val root = get("$FITT/${league.path}/powerindex?limit=1000")
        val cats = root.arr("categories")
        fun idx(cat: String, name: String) = cats.firstOrNull { it.str("name") == cat }?.strings("names")?.indexOf(name) ?: -1
        val teams = root.arr("teams")
        fun v(t: JsonElement, cat: String, name: String): Float? {
            val i = idx(cat, name); if (i < 0) return null
            val values = t.arr("categories").firstOrNull { it.str("name") == cat }?.arr("values") ?: return null
            return values.getOrNull(i)?.content?.toFloatOrNull()
        }
        val dir = directory(league)
        fun parseTeam(t: JsonElement?) = this.parseTeam(t).enrich(dir)
        fun f1(x: Float?) = x?.let { "%.1f".format(it) } ?: "–"
        fun rec(w: Float?, l: Float?) = if (w == null) "–" else "${w.toInt()}-${l?.toInt() ?: 0}"
        fun proj(w: Float?, l: Float?) = if (w == null) "–" else "%.1f-%.1f".format(w, l ?: 0f)

        return when (league) {
            League.NFL -> {
                val rows = teams.map { t ->
                    Projection(parseTeam(t.obj("team")), f1(v(t, "fpi", "fpi")), v(t, "fpi", "fpirank")?.toInt(),
                        rec(v(t, "fpi", "numwins"), v(t, "fpi", "numlosses")),
                        proj(v(t, "projections", "projectedw"), v(t, "projections", "projectedl")),
                        listOf("PLAYOFF" to v(t, "projections", "probmakeplayoffs"), "DIV" to v(t, "projections", "probwindiv"), "SB" to v(t, "projections", "probmaketitlegame"), "SB WIN" to v(t, "projections", "probwintitle")))
                }.sortedByDescending { it.columns[0].second ?: 0f }
                listOf(ProjectionTable("Playoff Odds", "FPI", listOf("PLAYOFF", "DIV", "SB", "WIN SB"), rows,
                    "ESPN's Football Power Index simulates the rest of the season 10,000 times."))
            }
            League.CFB -> {
                val rows = teams.map { t ->
                    Projection(parseTeam(t.obj("team")), f1(v(t, "fpi", "fpi")), v(t, "fpi", "fpirank")?.toInt(),
                        rec(v(t, "fpi", "numwins"), v(t, "fpi", "numlosses")),
                        proj(v(t, "fpi", "projectedw"), v(t, "fpi", "projectedl")),
                        listOf("CONF" to v(t, "fpi", "probwinconf"), "CFP" to v(t, "fpi", "probmakeplayoffs"), "TITLE GM" to v(t, "fpi", "probmaketitlegame"), "WIN" to v(t, "fpi", "probwintitle")))
                }.sortedByDescending { it.columns[1].second ?: 0f }
                listOf(ProjectionTable("College Football Playoff Odds", "FPI", listOf("CONF", "CFP", "NC GM", "TITLE"), rows,
                    "Chance to win the conference, make the 12-team CFP, reach and win the title game."))
            }
            League.NBA -> {
                val rows = teams.map { t ->
                    Projection(parseTeam(t.obj("team")), f1(v(t, "bpi", "bpi")), v(t, "bpi", "bpirank")?.toInt(),
                        rec(v(t, "bpi", "numwins"), v(t, "bpi", "numlosses")),
                        proj(v(t, "projections", "projectedw"), v(t, "projections", "projectedl")),
                        listOf("PLAYOFF" to v(t, "projections", "probmakeplayoffs"), "TOP 6" to v(t, "projections", "top6seed"), "FINALS" to v(t, "playoffs", "probmaketitlegame"), "TITLE" to v(t, "playoffs", "probwintitle")),
                        extra = v(t, "projections", "probno1draftpick")?.takeIf { it >= 1f }?.let { "#1 pick ${"%.0f".format(it)}%" })
                }.sortedByDescending { it.columns[0].second ?: 0f }
                listOf(ProjectionTable("Playoff Odds", "BPI", listOf("PLAYOFF", "TOP 6", "FINALS", "TITLE"), rows,
                    "ESPN's Basketball Power Index projects every remaining game."))
            }
            League.CBB -> {
                val rows = teams.map { t ->
                    val seed = v(t, "tournament", "projectedtournamentseed")
                    Projection(parseTeam(t.obj("team")), f1(v(t, "bpi", "bpi")), v(t, "bpi", "bpirank")?.toInt(),
                        rec(v(t, "bpi", "wins"), v(t, "bpi", "losses")),
                        proj(v(t, "bpi", "projtotalwins"), v(t, "bpi", "projtotallosses")),
                        listOf("S16" to v(t, "tournament", "chancesweet16"), "F4" to v(t, "tournament", "chancefinal4"), "TITLE" to v(t, "tournament", "chancencaachampion")),
                        extra = seed?.takeIf { it > 0 }?.let { "Proj. seed ${it.toInt()}" })
                }.sortedBy { it.ratingRank ?: 999 }
                listOf(ProjectionTable("March Madness Outlook", "BPI", listOf("SWEET 16", "FINAL 4", "TITLE"), rows,
                    "Projected NCAA tournament seed and chance to advance, from ESPN's BPI."))
            }
            else -> emptyList()
        }
    }

    // ---------------------------------------------------------------- team

    private val directories = java.util.concurrent.ConcurrentHashMap<League, Map<String, Team>>()

    /** Full team list for a league (colours/logos), fetched once – schedule feeds omit team colours. */
    internal suspend fun directory(league: League): Map<String, Team> = directories[league] ?: runCatching {
        get("$SITE/${league.path}/teams?limit=1000").arr("sports").firstOrNull().arr("leagues").firstOrNull().arr("teams")
            .map { parseTeam(it.obj("team")) }.associateBy { it.id }
    }.getOrDefault(emptyMap()).also { if (it.isNotEmpty()) directories[league] = it }

    internal fun Team.enrich(dir: Map<String, Team>): Team {
        val d = dir[id] ?: return this
        return if (color == NO_COLOR) copy(color = d.color, altColor = d.altColor, logo = logo ?: d.logo, darkLogo = darkLogo ?: d.darkLogo) else this
    }

    suspend fun team(league: League, teamId: String): TeamInfo {
        var root = get("$SITE/${league.path}/teams/$teamId/schedule")
        // Before the regular season starts ESPN defaults to an empty preseason schedule.
        if (root.arr("events").isEmpty() || root.obj("season")?.int("type") == 1) {
            val reg = get("$SITE/${league.path}/teams/$teamId/schedule?seasontype=2")
            if (reg.arr("events").isNotEmpty()) root = reg
        }
        val dir = directory(league)
        val team = parseTeam(root.obj("team")).enrich(dir)
        val items = root.arr("events").mapNotNull { e ->
            runCatching {
                val raw = parseEvent(league, e)
                val g = raw.copy(away = raw.away.copy(team = raw.away.team.enrich(dir)), home = raw.home.copy(team = raw.home.team.enrich(dir)))
                val me = if (g.home.team.id == teamId) g.home else g.away
                val opp = if (g.home.team.id == teamId) g.away else g.home
                val result = if (g.state == GameState.POST && me.score != null) {
                    val w = me.winner || (me.score.toDoubleOrNull() ?: 0.0) > (opp.score?.toDoubleOrNull() ?: 0.0)
                    "${if (w) "W" else "L"} ${me.score}-${opp.score}"
                } else null
                TeamScheduleItem(g, result)
            }.getOrNull()
        }
        return TeamInfo(team, root.obj("team")?.str("recordSummary"), root.obj("team")?.str("standingSummary"), items)
    }

    fun fixIso(s: String): String = if (s.length == 17 && s.endsWith("Z")) s.dropLast(1) + ":00Z" else s
}
