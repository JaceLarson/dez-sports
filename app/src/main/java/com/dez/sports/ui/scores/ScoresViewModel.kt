package com.dez.sports.ui.scores

import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dez.sports.data.EspnApi
import com.dez.sports.data.ForYou
import com.dez.sports.data.ForYouRepository
import com.dez.sports.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

/** null league = "For You" feed. */
data class ScoresState(
    val league: League? = null,
    val date: LocalDate = LocalDate.now(),
    val week: WeekOption? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val board: Scoreboard? = null,
    val feed: ForYou? = null,
    val error: String? = null,
)

class ScoresViewModel : ViewModel() {
    private val _state = MutableStateFlow(ScoresState())
    val state: StateFlow<ScoresState> = _state
    private var poll: Job? = null
    private var visible = true

    init { load() }

    fun selectLeague(l: League?) {
        if (l == _state.value.league) return
        _state.value = _state.value.copy(league = l, week = null, date = LocalDate.now(), board = null, loading = true, error = null)
        load()
    }

    fun selectDate(d: LocalDate) { _state.value = _state.value.copy(date = d, board = null, loading = true); load() }
    fun selectWeek(w: WeekOption) { _state.value = _state.value.copy(week = w, board = null, loading = true); load() }
    fun refresh() { _state.value = _state.value.copy(refreshing = true); load() }
    fun setVisible(v: Boolean) { visible = v; if (v) load() else poll?.cancel() }

    private fun load() {
        poll?.cancel()
        poll = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val s = _state.value
                val ok = runCatching {
                    if (s.league == null) {
                        val feed = loadForYou()
                        _state.value = _state.value.copy(feed = feed, loading = false, refreshing = false, error = null)
                    } else {
                        val today = s.date == LocalDate.now()
                        val b = EspnApi.scoreboard(s.league, date = if (s.league.weekly || (today && s.league == League.PGA)) null else s.date, week = s.week)
                        if (_state.value.league == s.league) _state.value = _state.value.copy(board = b, loading = false, refreshing = false, error = null)
                    }
                }
                if (ok.isFailure) _state.value = _state.value.copy(loading = false, refreshing = false, error = ok.exceptionOrNull()?.message ?: "Couldn't load")
                val anyLive = _state.value.board?.games?.any { it.state == GameState.LIVE } == true ||
                    _state.value.board?.golf?.any { it.state == GameState.LIVE } == true ||
                    _state.value.feed?.live?.isNotEmpty() == true
                if (!visible) break
                delay(if (anyLive) 15_000 else 90_000)
            }
        }
    }

    private suspend fun loadForYou(): ForYou = ForYouRepository.load()
}
