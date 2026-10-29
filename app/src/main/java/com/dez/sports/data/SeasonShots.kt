package com.dez.sports.data

import com.dez.sports.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.hypot

/**
 * One shot from anywhere in the season. Basketball: feet across the court (0..50) and from the rim line.
 * Hockey: folded onto one offensive zone, x 25..100 toward the goal at 89, y -42.5..42.5.
 */
data class SeasonShot(val x: Float, val y: Float, val made: Boolean, val teamId: String?, val shooter: String?, val three: Boolean)

data class ShotSet(val shots: List<SeasonShot>, val names: Map<String, String>, val games: Int)

/** Every shot a team or player took this season, stitched together from each game's play-by-play. */
object SeasonShots {
    private class GameShots(val shots: List<SeasonShot>, val names: Map<String, String>)
    private val cache = ConcurrentHashMap<String, GameShots>()

    fun supported(l: League) = l.kind == SportKind.BASKETBALL || l.kind == SportKind.HOCKEY

    suspend fun load(league: League, eventIds: List<String>, teamId: String?, athleteId: String?, onProgress: (Int) -> Unit = {}): ShotSet = coroutineScope {
        val gate = Semaphore(4)
        var done = 0
        val games = eventIds.map { id ->
            async {
                gate.withPermit {
                    (cache[id] ?: runCatching { extract(EspnApi.summary(league, id, final = true)) }.getOrNull()?.also { cache[id] = it })
                        .also { synchronized(this@SeasonShots) { done++; onProgress(done) } }
                }
            }
        }.awaitAll().filterNotNull()
        val shots = games.flatMap { g -> g.shots.filter { (teamId == null || it.teamId == teamId) && (athleteId == null || it.shooter == athleteId) } }
        ShotSet(shots, games.fold(HashMap()) { acc, g -> acc.putAll(g.names); acc }, games.size)
    }

    private fun extract(d: GameDetail): GameShots {
        val names = d.boxes.flatMap { b -> b.tables.flatMap { t -> t.rows } }.mapNotNull { r -> r.id?.let { it to r.shortName } }.toMap()
        val shots = when (d.game.league.kind) {
            SportKind.BASKETBALL -> d.plays.mapNotNull { p ->
                val s = p.shot ?: return@mapNotNull null
                if (p.text.contains("free throw", true)) return@mapNotNull null
                val three = p.text.contains("three point", true) || hypot(s.x - 25f, s.y) >= 22.5f
                SeasonShot(s.x, s.y, s.made, p.teamId ?: s.teamId, p.athletes.firstOrNull(), three)
            }
            SportKind.HOCKEY -> d.plays.mapNotNull { p ->
                val r = p.rink ?: return@mapNotNull null
                if (r.kind == RinkKind.BLOCK) return@mapNotNull null
                // Teams switch ends each period; fold every shot onto the same attacking zone.
                val flip = r.x < 0
                SeasonShot(abs(r.x), if (flip) -r.y else r.y, r.kind == RinkKind.GOAL, p.teamId, p.athletes.firstOrNull(), false)
            }
            else -> emptyList()
        }
        return GameShots(shots, names)
    }
}
