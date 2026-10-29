package com.dez.sports.ui.game

import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dez.sports.data.EspnApi
import com.dez.sports.model.GameDetail
import com.dez.sports.model.GameState
import com.dez.sports.model.League
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GameUiState(val detail: GameDetail? = null, val error: String? = null)

class GameViewModel(handle: SavedStateHandle) : ViewModel() {
    val league: League = League.valueOf(handle["league"]!!)
    private val id: String = handle["id"]!!
    private val _state = MutableStateFlow(GameUiState())
    val state: StateFlow<GameUiState> = _state
    private var job: Job? = null

    fun retry() { _state.value = GameUiState(); setVisible(true) }

    fun setVisible(v: Boolean) {
        job?.cancel()
        if (!v) return
        job = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                runCatching { EspnApi.summary(league, id) }
                    .onSuccess { _state.value = GameUiState(it) }
                    .onFailure { if (_state.value.detail == null) _state.value = GameUiState(error = it.message) }
                val live = _state.value.detail?.game?.state == GameState.LIVE
                if (!live && _state.value.detail != null) break
                delay(if (live) 12_000 else if (_state.value.detail == null) 5_000 else 30_000)
            }
        }
    }
}
