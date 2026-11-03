package com.dez.sports.model

enum class GameState { PRE, LIVE, POST }

data class Team(
    val id: String,
    val name: String,          // "Jazz"
    val location: String,      // "Utah"
    val displayName: String,   // "Utah Jazz"
    val abbreviation: String,
    val color: Long,
    val altColor: Long,
    val logo: String?,
    val darkLogo: String?,
)

data class Side(
    val team: Team,
    val score: String?,
    val record: String?,
    val rank: Int?,
    val winner: Boolean,
    val homeAway: String,
    val linescores: List<String>,
    val timeouts: Int? = null,
    /** College only: matches [com.dez.sports.data.Conference.id]. */
    val conferenceId: String? = null,
)

data class FootballSituation(
    val downDistance: String?,      // "1st & 10 at DET 35"
    val possessionTeamId: String?,
    val isRedZone: Boolean,
    val spot: String?,              // "DET 35" – side of field + yard line
    val lastPlay: String?,
)

data class BaseballSituation(
    val balls: Int,
    val strikes: Int,
    val outs: Int,
    val onFirst: Boolean,
    val onSecond: Boolean,
    val onThird: Boolean,
    val batter: String?,
    val pitcher: String?,
    val lastPlay: String?,
)

/** A TV/stream listing. [national] = carried nationwide (not a regional sports network). */
data class Channel(val name: String, val national: Boolean, val streaming: Boolean)

data class Leader(val category: String, val player: String, val line: String, val headshot: String?, val teamId: String?, val athleteId: String? = null)

data class Game(
    val id: String,
    val league: League,
    val dateIso: String,
    val state: GameState,
    val statusDetail: String,     // "Final", "Q3 5:21", "7:30 PM MDT"
    val statusShort: String,
    val clock: String?,
    val period: Int,
    val away: Side,
    val home: Side,
    val broadcasts: List<String>,
    val venue: String?,
    val note: String?,            // "World Series - Game 3", bowl names, etc.
    val seriesSummary: String?,
    val odds: String?,            // "LAL -6.5 · O/U 237.5"
    val football: FootballSituation? = null,
    val baseball: BaseballSituation? = null,
    val lastPlay: String? = null,
    val leaders: List<Leader> = emptyList(),
    val isTbd: Boolean = false,
    val conferenceGame: Boolean = false,
    val channels: List<Channel> = emptyList(),
) {
    val sides get() = listOf(away, home)
    fun involves(teamId: String) = away.team.id == teamId || home.team.id == teamId
}

data class GolfPlayer(
    val position: String,
    val name: String,
    val flag: String?,
    val toPar: String,
    val today: String?,
    val thru: String?,
    val rounds: List<String>,
)

data class GolfEvent(
    val id: String,
    val name: String,
    val state: GameState,
    val detail: String,
    val dateRange: String,
    val broadcasts: List<String>,
    val venue: String?,
    val players: List<GolfPlayer>,
)

data class Scoreboard(
    val games: List<Game>,
    val golf: List<GolfEvent> = emptyList(),
    val weeks: List<WeekOption> = emptyList(),
    val currentWeekKey: String? = null,
)

data class WeekOption(val key: String, val label: String, val detail: String, val seasonType: String, val week: String)

// ---------- Game detail ----------

data class StatTable(
    val title: String,           // "Passing", "Batting", "Skaters"
    val labels: List<String>,
    val descriptions: List<String>,
    val rows: List<StatRow>,
    val totals: List<String>?,
    /** Sub-headers inside the table, keyed by the index of the first row under each. */
    val groups: Map<Int, String> = emptyMap(),
)

data class StatRow(
    val id: String?,
    val name: String,
    val shortName: String,
    val position: String?,
    val jersey: String?,
    val headshot: String?,
    val starter: Boolean,
    val didNotPlay: Boolean,
    val reason: String?,
    val stats: List<String>,
)

data class TeamBox(val team: Team, val tables: List<StatTable>)

data class TeamStatLine(val label: String, val away: String, val home: String)

data class Play(
    val text: String,
    val period: String,
    val clock: String?,
    val awayScore: Int?,
    val homeScore: Int?,
    val scoring: Boolean,
    val teamId: String?,
    val shot: Shot? = null,
    val type: String? = null,
    val periodNumber: Int = 0,
    val half: String? = null,               // baseball "Top" / "Bottom"
    val football: FootballPlay? = null,
    val pitch: Pitch? = null,
    val atBatId: String? = null,
    val rink: RinkEvent? = null,
    /** Player ids involved, in ESPN's order (shooter first, then assister/blocker/goalie…), with their role when given. */
    val athletes: List<String> = emptyList(),
    val roles: List<String?> = emptyList(),
)

data class Shot(val x: Float, val y: Float, val made: Boolean, val teamId: String?)

/** Yards-to-endzone before/after the snap, from the offence's point of view. */
data class FootballPlay(val startYtg: Int?, val endYtg: Int?, val downText: String?, val offenseId: String?, val yards: Int?, val isScore: Boolean, val turnover: Boolean)

/** ESPN pitch coordinates: strike zone is roughly x 87..143, y 134..196 (catcher's view). */
data class Pitch(val x: Float, val y: Float, val type: String?, val mph: Int?, val balls: Int, val strikes: Int, val result: String)

enum class RinkKind { GOAL, SHOT, MISS, BLOCK }
/** NHL rink coordinates: x -100..100 (end boards), y -42.5..42.5. */
data class RinkEvent(val x: Float, val y: Float, val kind: RinkKind)

data class Drive(val teamId: String?, val description: String, val result: String, val plays: List<Play>)

data class WinProbPoint(val homePct: Float)

data class Injury(val teamId: String, val player: String, val status: String, val detail: String?)

data class GameDetail(
    val game: Game,
    val boxes: List<TeamBox>,
    val teamStats: List<TeamStatLine>,
    val plays: List<Play>,
    val scoringPlays: List<Play>,
    val drives: List<Drive>,
    val winProb: List<WinProbPoint>,
    val shots: List<Shot>,
    val officials: List<String>,
    val attendance: Int?,
    val venue: String?,
    val venueCity: String?,
    val venueImage: String?,
    val injuries: List<Injury>,
    val seasonSeries: String?,
    val recapHeadline: String?,
    val recapBody: String?,
    val pregameWinPct: Pair<Float, Float>?,   // away, home predictor %
    val oddsDetail: List<Pair<String, String>>,
    val leaders: List<Leader>,
    /** Athlete id → jersey number from the game rosters (MLB box scores leave jerseys out). */
    val jerseys: Map<String, String> = emptyMap(),
)

// ---------- Standings / rankings / projections ----------

data class StandingsGroup(val name: String, val columns: List<String>, val rows: List<StandingsRow>)
data class StandingsRow(val team: Team, val seed: String?, val values: List<String>, val clincher: String?)

data class Poll(val name: String, val headline: String?, val entries: List<PollEntry>, val others: String?)
data class PollEntry(val rank: Int, val previous: Int, val team: Team, val record: String?, val points: String?, val firstPlaceVotes: Int, val trend: String)

data class Projection(
    val team: Team,
    val rating: String,
    val ratingRank: Int?,
    val record: String,
    val projRecord: String,
    val columns: List<Pair<String, Float?>>, // label -> percent
    val extra: String? = null,
)

data class ProjectionTable(val title: String, val ratingName: String, val columns: List<String>, val rows: List<Projection>, val blurb: String)

data class TeamScheduleItem(val game: Game, val result: String?)
data class TeamInfo(val team: Team, val record: String?, val standingSummary: String?, val schedule: List<TeamScheduleItem>)
