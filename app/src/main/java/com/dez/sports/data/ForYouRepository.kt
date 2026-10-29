package com.dez.sports.data

import com.dez.sports.model.*
import com.dez.sports.ui.components.localDate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate

data class FavoriteCard(val league: League, val team: Team, val record: String?, val standing: String?, val featured: Game?, val featuredLabel: String, val next: Game?, val form: List<String>)
data class ForYou(val favorites: List<FavoriteCard>, val live: List<Game>, val upNext: List<Game>, val golf: List<GolfEvent>, val tv: TvTonight = TvTonight(emptyList(), emptyList()))

/** Builds the "For You" feed: each favourite team's live/today/last/next game plus what's live everywhere. Shared by the app and the widget. */
object ForYouRepository {
    /** [leagues] limits which scoreboards are fetched (the widget only needs its favourites' leagues). */
    suspend fun load(leagues: Collection<League> = League.entries): ForYou = coroutineScope {
        val favs = Favorites.teams.value
        val boards = leagues.map { l -> async { l to runCatching { EspnApi.scoreboard(l) }.getOrNull() } }.awaitAll().toMap()
        val cards = favs.map { f ->
            async {
                runCatching {
                    val info = EspnApi.team(f.league, f.teamId)
                    val games = info.schedule.map { it.game }
                    val now = LocalDate.now()
                    // Prefer the live/today version from the scoreboard (has situation + leaders). Older games come from the
                    // team schedule, which only lists this team's leaders, so fetch the full event for both sides.
                    suspend fun fresh(g: Game) = boards[f.league]?.games?.firstOrNull { it.id == g.id }
                        ?: (if (g.state != GameState.PRE) runCatching { EspnApi.event(f.league, g.id) }.getOrNull() else null) ?: g
                    val live = games.firstOrNull { it.state == GameState.LIVE }
                    val todays = games.firstOrNull { localDate(it.dateIso) == now }
                    val last = games.lastOrNull { it.state == GameState.POST }
                    val next = games.firstOrNull { it.state == GameState.PRE }
                    val recentFinal = last?.takeIf { g -> localDate(g.dateIso)?.let { !it.isBefore(now.minusDays(if (f.league.weekly) 3 else 1)) } == true }
                    val (featured, label) = when {
                        live != null -> fresh(live) to "LIVE"
                        todays != null -> fresh(todays) to if (todays.state == GameState.POST) "FINAL" else "TODAY"
                        recentFinal != null -> fresh(recentFinal) to "LAST GAME"
                        next != null -> next to "NEXT GAME"
                        else -> last?.let { fresh(it) } to "LAST GAME"
                    }
                    val form = info.schedule.filter { it.result != null }.takeLast(5).map { it.result!!.take(1) }
                    FavoriteCard(f.league, info.team, info.record, info.standingSummary, featured, label,
                        next?.takeIf { it.id != featured?.id }, form)
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
        val all = boards.values.filterNotNull().flatMap { it.games }
        val tv = BigGames.pick(all, isFavorite = { g -> favs.any { it.league == g.league && g.involves(it.teamId) } })
        val featured = tv.big.map { it.game.id }.toSet()
        val live = all.filter { it.state == GameState.LIVE && it.id !in featured }.sortedBy { it.league.ordinal }
        val today = LocalDate.now()
        val upNext = all.filter { it.state == GameState.PRE && it.id !in featured && localDate(it.dateIso) == today && (it.league !in setOf(League.CFB, League.CBB) || it.home.rank != null || it.away.rank != null) }
            .sortedBy { it.dateIso }.take(12)
        ForYou(cards, live, upNext, boards[League.PGA]?.golf ?: emptyList(), tv)
    }
}
