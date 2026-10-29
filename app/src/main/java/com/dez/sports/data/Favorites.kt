package com.dez.sports.data

import android.content.Context
import com.dez.sports.model.League
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class FavoriteTeam(val league: League, val teamId: String)

object Favorites {
    private val defaults = listOf(
        FavoriteTeam(League.NHL, "129764"),  // Utah Mammoth
        FavoriteTeam(League.NBA, "26"),      // Utah Jazz
        FavoriteTeam(League.NFL, "8"),       // Detroit Lions
        FavoriteTeam(League.CFB, "252"),     // BYU Cougars
        FavoriteTeam(League.CBB, "252"),     // BYU Cougars
    )

    private val _teams = MutableStateFlow(defaults)
    val teams: StateFlow<List<FavoriteTeam>> = _teams
    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
        prefs()?.let { _showDez.value = it.getBoolean("showDez", true); _showWatch.value = it.getBoolean("showWatch", true) }
        val saved = prefs()?.getString("favorites", null) ?: return
        _teams.value = saved.split(",").filter { it.contains(":") }.mapNotNull {
            val (l, id) = it.split(":")
            runCatching { FavoriteTeam(League.valueOf(l), id) }.getOrNull()
        }
    }

    fun isFavorite(league: League, teamId: String) = _teams.value.any { it.league == league && it.teamId == teamId }

    fun toggle(league: League, teamId: String) {
        val cur = _teams.value
        set(if (isFavorite(league, teamId)) cur.filterNot { it.league == league && it.teamId == teamId } else cur + FavoriteTeam(league, teamId))
    }

    /** Swap a team with its neighbour; My Teams follows this order. */
    fun move(team: FavoriteTeam, by: Int) {
        val list = _teams.value.toMutableList()
        val i = list.indexOf(team); val j = i + by
        if (i < 0 || j !in list.indices) return
        list[i] = list[j]; list[j] = team
        set(list)
    }

    fun clear() = set(emptyList())
    fun resetToDefaults() = set(defaults)

    private fun set(list: List<FavoriteTeam>) {
        _teams.value = list
        prefs()?.edit()?.putString("favorites", list.joinToString(",") { "${it.league.name}:${it.teamId}" })?.apply()
    }

    private fun prefs() = ctx?.getSharedPreferences("dez", Context.MODE_PRIVATE)

    /** Scores list style: compact one-line-per-team rows instead of the full score cards. */
    private val _compact = MutableStateFlow<Boolean?>(null)
    val compactScores: StateFlow<Boolean?> = _compact
    fun compact(): Boolean = _compact.value ?: (prefs()?.getBoolean("compactScores", false) ?: false).also { _compact.value = it }
    fun setCompact(v: Boolean) { _compact.value = v; prefs()?.edit()?.putBoolean("compactScores", v)?.apply() }

    /** Dez Scores on finished games and Watch scores on upcoming ones; both on unless turned off in Settings. */
    private val _showDez = MutableStateFlow(true)
    val showDez: StateFlow<Boolean> = _showDez
    private val _showWatch = MutableStateFlow(true)
    val showWatch: StateFlow<Boolean> = _showWatch
    fun setShowDez(v: Boolean) { _showDez.value = v; prefs()?.edit()?.putBoolean("showDez", v)?.apply() }
    fun setShowWatch(v: Boolean) { _showWatch.value = v; prefs()?.edit()?.putBoolean("showWatch", v)?.apply() }
}
