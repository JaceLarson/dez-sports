package com.dez.sports.model

data class SummaryStat(val label: String, val value: String, val rank: String?)

data class Athlete(
    val id: String,
    val name: String,
    val headshot: String?,
    val jersey: String?,
    val position: String?,
    val team: Team?,
    val height: String?,
    val weight: String?,
    val age: Int?,
    val birthplace: String?,
    val draft: String?,
    val college: String?,
    val experience: String?,
    val injury: String?,
    val summaryTitle: String?,
    val summary: List<SummaryStat>,
)

/** A labelled run of columns, e.g. "Passing" spanning CMP..QBR in a football game log. */
data class ColumnGroup(val title: String, val count: Int)

data class GameLogRow(
    val eventId: String,
    val dateIso: String,
    val atVs: String,          // "vs" / "@"
    val opponent: Team,
    val result: String?,       // "W" / "L" / "T"
    val score: String?,        // "20-17"
    val stats: List<String>,
)

data class GameLogBlock(val title: String, val rows: List<GameLogRow>, val totals: List<String>?)

data class GameLogSection(
    val title: String,                    // "2026-27 Regular Season"
    val blocks: List<GameLogBlock>,       // by month (basketball/hockey) or a single block (football)
    val averages: List<String>?,
    val totals: List<String>?,
)

data class GameLog(
    val labels: List<String>,
    val descriptions: List<String>,
    val groups: List<ColumnGroup>,
    val seasons: List<Pair<String, String>>,   // value -> "2025-26"
    val selectedSeason: String?,
    val sections: List<GameLogSection>,
)

data class CareerRow(val season: String, val team: Team?, val stats: List<String>)
data class CareerTable(val title: String, val labels: List<String>, val descriptions: List<String>, val rows: List<CareerRow>, val totals: List<String>?)

data class RosterPlayer(val id: String, val name: String, val jersey: String?, val position: String?, val headshot: String?, val detail: String?, val injured: Boolean)
data class RosterGroup(val title: String?, val players: List<RosterPlayer>)

data class SeasonLine(val labels: List<String>, val descriptions: List<String>, val stats: List<String>)
