package com.evanrobinson.watchapk.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-process bus so InstallActivity can show what InstallResultReceiver learned. */
object InstallEvents {
    sealed interface State {
        data object Idle : State
        data object Installing : State
        data class Success(val packageName: String?) : State
        data class Failure(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun post(state: State) {
        _state.value = state
    }
}
