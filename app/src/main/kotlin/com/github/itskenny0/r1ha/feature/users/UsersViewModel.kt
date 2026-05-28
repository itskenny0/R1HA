package com.github.itskenny0.r1ha.feature.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.HaUser
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the read-only Users browser. Fires `config/auth/list` over the
 * WebSocket and surfaces the rows; the screen renders nothing editable
 * because user / credential mutation is intentionally out of scope (HA's
 * own auth surface handles password resets, MFA, etc. and we don't want
 * to be the second source-of-truth).
 *
 * Non-admin tokens fail the call server-side. We classify the failure
 * into [permissionDenied] (true means "show needs-admin empty state",
 * false means "show generic error") so the UI distinguishes between
 * "you can't see this" and "we couldn't reach HA".
 */
class UsersViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val users: List<HaUser> = emptyList(),
        /** True when the load failed because the token isn't admin. Drives a
         *  distinct empty-state copy. */
        val permissionDenied: Boolean = false,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null, permissionDenied = false)
            haRepository.listAuthUsers().fold(
                onSuccess = { users ->
                    R1Log.i("Users", "fetched ${users.size} user(s)")
                    _ui.value = _ui.value.copy(
                        loading = false,
                        users = users,
                        error = null,
                        permissionDenied = false,
                    )
                },
                onFailure = { t ->
                    val msg = t.message.orEmpty()
                    val denied = msg.contains("unauthorized", ignoreCase = true) ||
                        msg.contains("permission", ignoreCase = true) ||
                        msg.contains("admin", ignoreCase = true) ||
                        msg.contains("auth_error", ignoreCase = true) ||
                        msg.contains("not_allowed", ignoreCase = true)
                    R1Log.w("Users", "fetch failed (denied=$denied): $msg")
                    _ui.value = _ui.value.copy(
                        loading = false,
                        error = if (denied) null else msg,
                        permissionDenied = denied,
                    )
                },
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { UsersViewModel(haRepository) }
        }
    }
}
