package com.dez.sports.model

import androidx.compose.ui.graphics.Color

enum class SportKind { BASKETBALL, FOOTBALL, BASEBALL, HOCKEY, GOLF }

enum class League(
    val label: String,
    val shortLabel: String,
    val path: String,
    val kind: SportKind,
    val accent: Color,
    /** Extra scoreboard query (e.g. all FBS / all D1 instead of ESPN's top-25 default). */
    val scoreboardQuery: String = "",
    val weekly: Boolean = false,
    val hasRankings: Boolean = false,
    val hasProjections: Boolean = false,
    val hasStandings: Boolean = true,
) {
    NFL("NFL", "NFL", "football/nfl", SportKind.FOOTBALL, Color(0xFF013369), weekly = true, hasProjections = true),
    CFB("College Football", "CFB", "football/college-football", SportKind.FOOTBALL, Color(0xFF8B5A2B), "groups=80&limit=300", weekly = true, hasRankings = true, hasProjections = true),
    NBA("NBA", "NBA", "basketball/nba", SportKind.BASKETBALL, Color(0xFFC9082A), hasProjections = true),
    CBB("College Basketball", "CBB", "basketball/mens-college-basketball", SportKind.BASKETBALL, Color(0xFFF58426), "groups=50&limit=400", hasRankings = true, hasProjections = true),
    NHL("NHL", "NHL", "hockey/nhl", SportKind.HOCKEY, Color(0xFF8E9AAF)),
    MLB("MLB", "MLB", "baseball/mlb", SportKind.BASEBALL, Color(0xFF1E5AA8)),
    PGA("Golf", "PGA", "golf/pga", SportKind.GOLF, Color(0xFF2E8B57), hasStandings = false);

    val periodName: String
        get() = when (kind) {
            SportKind.BASEBALL -> "Inning"
            SportKind.HOCKEY -> "Period"
            SportKind.BASKETBALL -> if (this == CBB) "Half" else "Quarter"
            else -> "Quarter"
        }

    companion object {
        fun fromPath(path: String) = entries.first { it.path == path }
    }
}
