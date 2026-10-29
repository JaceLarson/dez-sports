package com.dez.sports.data

import com.dez.sports.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** One beat of a team's season, in date order. */
sealed class StoryBeat(val dateIso: String) {
    /** "Chapter 2 · The Streak": a run of games that belongs together. */
    class Chapter(dateIso: String, val number: Int, val title: String, val summary: String, val record: String) : StoryBeat(dateIso)
    class Result(dateIso: String, val item: TeamScheduleItem, val margin: Int, val recordAfter: String, val tags: List<String>) : StoryBeat(dateIso)
    class Milestone(dateIso: String, val emoji: String, val title: String, val detail: String) : StoryBeat(dateIso)
    class News(dateIso: String, val headline: String, val blurb: String?, val image: String?, val url: String?) : StoryBeat(dateIso)
}

data class SeasonStory(val headline: String, val subhead: String, val beats: List<StoryBeat>, val upcoming: TeamScheduleItem?)

/**
 * Turns a schedule (plus recent team news) into a season told as chapters: streaks, turning points,
 * best and worst nights, milestones. Works for any team – it only needs their schedule.
 */
object SeasonStories {
    private val day = DateTimeFormatter.ofPattern("MMM d")

    suspend fun build(league: League, info: TeamInfo, teamId: String): SeasonStory {
        val news = runCatching { news(league, teamId, info.team) }.getOrDefault(emptyList())
        return assemble(league, info, teamId, news)
    }

    private suspend fun news(league: League, teamId: String, team: Team): List<StoryBeat.News> {
        val root = EspnApi.get("https://site.api.espn.com/apis/site/v2/sports/${league.path}/news?team=$teamId&limit=40")
        return root.arr("articles").mapNotNull { a ->
            // Only pieces actually about this team, not league-wide roundups that mention them.
            val teams = a.arr("categories").filter { it.str("type") == "team" }
            val headline = a.str("headline").orEmpty()
            val tagged = teams.any { it.str("teamId") == teamId || it.obj("team")?.str("id") == teamId } &&
                (teams.size <= 2 || listOf(team.name, team.location).any { it.length > 2 && headline.contains(it, true) })
            if (!tagged || a.str("type") == "Media") return@mapNotNull null
            StoryBeat.News(a.str("published") ?: return@mapNotNull null, a.str("headline") ?: return@mapNotNull null, a.str("description"),
                a.arr("images").firstOrNull()?.str("url"), a.obj("links")?.obj("web")?.str("href"))
        }.distinctBy { it.headline }.take(12)
    }

    fun assemble(league: League, info: TeamInfo, teamId: String, news: List<StoryBeat.News>): SeasonStory {
        val games = info.schedule.filter { it.game.state == GameState.POST && it.result != null }.sortedBy { it.game.dateIso }
        val upcoming = info.schedule.filter { it.game.state != GameState.POST }.minByOrNull { it.game.dateIso }
        val name = info.team.name.ifEmpty { info.team.displayName }
        if (games.isEmpty()) return SeasonStory("$name: the story starts soon", "No games played yet this season", news.sortedByDescending { it.dateIso }, upcoming)

        fun me(g: Game) = if (g.home.team.id == teamId) g.home else g.away
        fun opp(g: Game) = if (g.home.team.id == teamId) g.away else g.home
        fun margin(i: TeamScheduleItem) = (me(i.game).score?.toIntOrNull() ?: 0) - (opp(i.game).score?.toIntOrNull() ?: 0)
        val big = when (league.kind) { SportKind.HOCKEY -> 4; SportKind.BASEBALL -> 6; SportKind.FOOTBALL -> 21; else -> 20 }
        val close = when (league.kind) { SportKind.HOCKEY -> 1; SportKind.BASEBALL -> 1; SportKind.FOOTBALL -> 3; else -> 3 }

        val beats = mutableListOf<StoryBeat>()
        var w = 0; var l = 0
        val best = games.maxByOrNull(::margin); val worst = games.minByOrNull(::margin)
        // Streak runs, so each chapter is a stretch of the season with one mood.
        data class Run(val start: Int, val end: Int, val win: Boolean)
        val runs = mutableListOf<Run>()
        games.forEachIndexed { i, g ->
            val win = g.result!!.startsWith("W")
            if (runs.isNotEmpty() && runs.last().win == win) runs[runs.lastIndex] = runs.last().copy(end = i) else runs += Run(i, i, win)
        }
        val longestWin = runs.filter { it.win }.maxByOrNull { it.end - it.start }
        val longestLoss = runs.filter { !it.win }.maxByOrNull { it.end - it.start }

        // Chapters: split at streaks of 3+ and at month boundaries for long seasons.
        val chapterStarts = sortedSetOf(0)
        runs.filter { it.end - it.start >= 2 }.forEach { chapterStarts += it.start; if (it.end + 1 < games.size) chapterStarts += it.end + 1 }
        if (games.size > 24) games.indices.filter { it > 0 && month(games[it].game.dateIso) != month(games[it - 1].game.dateIso) }.forEach { chapterStarts += it }
        // Avoid one-game chapters next to each other.
        val starts = chapterStarts.toList().fold(mutableListOf<Int>()) { acc, s -> if (acc.isEmpty() || s - acc.last() >= 2) acc += s; acc }

        var chapter = 0
        games.forEachIndexed { i, item ->
            if (i in starts) {
                val end = (starts.firstOrNull { it > i } ?: games.size) - 1
                val slice = games.subList(i, end + 1)
                val sw = slice.count { it.result!!.startsWith("W") }; val sl = slice.size - sw
                val run = runs.firstOrNull { it.start == i && it.end - it.start >= 2 && it.end == end }
                chapter++
                val title = when {
                    chapter == 1 && i == 0 && run == null -> "Opening ${if (league.kind == SportKind.FOOTBALL) "weeks" else "stretch"}"
                    run != null && run.win && run.end - run.start + 1 >= 5 -> "Rolling: ${run.end - run.start + 1} straight wins"
                    run != null && run.win -> "Winning streak"
                    run != null && !run.win && run.end - run.start + 1 >= 5 -> "Rock bottom: ${run.end - run.start + 1} straight losses"
                    run != null -> "Rough patch"
                    sw > sl -> "Finding a groove"
                    sl > sw -> "Struggling"
                    else -> "Back and forth"
                }
                val avg = slice.map(::margin).average()
                beats += StoryBeat.Chapter(item.game.dateIso, chapter, title,
                    "${day(slice.first().game.dateIso)} – ${day(slice.last().game.dateIso)} · %+.1f avg margin".format(avg), "$sw-$sl")
            }
            val win = item.result!!.startsWith("W")
            if (win) w++ else l++
            val m = margin(item)
            val tags = buildList {
                if (item == best && m >= big / 2) add("Best win")
                if (item == worst && m <= -big / 2) add("Worst loss")
                if (abs(m) <= close) add(if (win) "Nail-biter" else "Heartbreaker")
                if (opp(item.game).rank != null) add("vs #${opp(item.game).rank}")
                if (item.game.away.linescores.size > (if (league.kind == SportKind.BASEBALL) 9 else if (league == League.CBB) 2 else if (league.kind == SportKind.HOCKEY) 3 else 4)) add("OT")
                DezScores.of(item.game)?.takeIf { it.score >= 70 }?.let { add("Dez ${it.score}") }
            }
            beats += StoryBeat.Result(item.game.dateIso, item, m, "$w-$l", tags)
            // Milestones as they happen.
            if (win && w == 1) beats += StoryBeat.Milestone(item.game.dateIso, "🎉", "First win of the season", "Beat ${opp(item.game).team.displayName} ${me(item.game).score}-${opp(item.game).score}")
            if (win && w % 10 == 0) beats += StoryBeat.Milestone(item.game.dateIso, "🔟", "Win No. $w", "Now $w-$l")
            if (w == l && i >= 3 && (i == 0 || !games[i - 1].result!!.startsWith(item.result!!.take(1)))) beats += StoryBeat.Milestone(item.game.dateIso, "⚖️", "Back to .500", "Even at $w-$l")
            longestWin?.takeIf { it.end == i && it.end - it.start >= 3 }?.let { beats += StoryBeat.Milestone(item.game.dateIso, "🔥", "Season-best ${it.end - it.start + 1}-game win streak", "${day(games[it.start].game.dateIso)} – ${day(item.game.dateIso)}") }
            longestLoss?.takeIf { it.end == i && it.end - it.start >= 3 }?.let { beats += StoryBeat.Milestone(item.game.dateIso, "🧊", "Longest skid: ${it.end - it.start + 1} straight losses", "${day(games[it.start].game.dateIso)} – ${day(item.game.dateIso)}") }
        }
        // Weave news in by date.
        val all = (beats + news).sortedWith(compareBy<StoryBeat> { it.dateIso.take(10) }.thenBy { if (it is StoryBeat.Chapter) 0 else if (it is StoryBeat.News) 2 else 1 })
        val margin = games.map(::margin).average()
        val headline = when {
            w >= l * 2 && w >= 4 -> "$name are rolling"
            l >= w * 2 && l >= 4 -> "A tough year for the $name"
            longestWin != null && runs.last() == longestWin && longestWin.end - longestWin.start >= 2 -> "$name are heating up"
            longestLoss != null && runs.last() == longestLoss && longestLoss.end - longestLoss.start >= 2 -> "$name need a spark"
            else -> "The $name so far"
        }
        return SeasonStory(headline, "$w-$l · %+.1f average margin · ${games.size} games".format(margin), all.reversed(), upcoming)
    }

    private fun zoned(iso: String) = runCatching { Instant.parse(EspnApi.fixIso(iso)).atZone(ZoneId.systemDefault()) }.getOrNull()
    fun day(iso: String): String = zoned(iso)?.format(day) ?: iso.take(10)
    private fun month(iso: String) = zoned(iso)?.month?.getDisplayName(TextStyle.SHORT, Locale.US) ?: iso.take(7)
}
