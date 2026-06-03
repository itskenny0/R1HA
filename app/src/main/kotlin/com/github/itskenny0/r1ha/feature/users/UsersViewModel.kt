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
import kotlinx.serialization.json.JsonPrimitive

/**
 * Drives the read-only Users browser. Fires `config/auth/list` over the
 * WebSocket and surfaces the rows; the screen renders nothing editable
 * because user / credential mutation is intentionally out of scope (HA's
 * own auth surface handles password resets, MFA, etc. and we don't want
 * to be the second source-of-truth).
 *
 * On top of the auth list we pull `person.*` entities and link each one
 * back to its owning user via the person's `user_id` attribute. That lets
 * the row show the linked person's friendly name and current presence with
 * a "since X" timestamp, which is the most useful at-a-glance answer to
 * "who is this account and are they home". The person fetch is best-effort:
 * if it fails (or returns nothing) the user rows still render, just without
 * the presence annotation.
 *
 * Non-admin tokens fail the auth call server-side. We classify the failure
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
        /** Sections in fixed display order (Admins, Users, System); empty
         *  sections are dropped by [groupUsers]. */
        val sections: List<Pair<UserSection, List<UserRowModel>>> = emptyList(),
        /** Flat count across all sections, for the header summary. */
        val totalCount: Int = 0,
        /** True when the load failed because the token isn't admin. Drives a
         *  distinct empty-state copy. */
        val permissionDenied: Boolean = false,
        val error: String? = null,
    )

    /** Per-user linkage built from `person.*` entities keyed by their
     *  `user_id` attribute. */
    private data class PersonLink(
        val name: String,
        val state: String,
        val since: java.time.Instant?,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null, permissionDenied = false)
            haRepository.listAuthUsers().fold(
                onSuccess = { users ->
                    R1Log.i("Users", "fetched ${users.size} user(s)")
                    val links = loadPersonLinks()
                    val rows = buildRows(users, links)
                    val sections = groupUsers(rows)
                    _ui.value = _ui.value.copy(
                        loading = false,
                        sections = sections,
                        totalCount = rows.size,
                        error = null,
                        permissionDenied = false,
                    )
                },
                onFailure = { t ->
                    val msg = t.message.orEmpty()
                    // Classify a not-an-admin rejection vs a generic transport
                    // failure. We deliberately do NOT key off any mention of
                    // "admin": HA's own error copy ("config/auth/list requires
                    // admin") would match on a transport error that merely
                    // echoes the command name. Match the actual HA permission
                    // error codes / phrases instead.
                    val denied = msg.contains("unauthorized", ignoreCase = true) ||
                        msg.contains("permission", ignoreCase = true) ||
                        msg.contains("auth_error", ignoreCase = true) ||
                        msg.contains("not_allowed", ignoreCase = true) ||
                        msg.contains("admin required", ignoreCase = true) ||
                        msg.contains("requires admin", ignoreCase = true) ||
                        msg.contains("unauthorized_admin", ignoreCase = true)
                    R1Log.w("Users", "fetch failed (denied=$denied): $msg")
                    _ui.value = _ui.value.copy(
                        loading = false,
                        sections = emptyList(),
                        totalCount = 0,
                        error = if (denied) null else msg,
                        permissionDenied = denied,
                    )
                },
            )
        }
    }

    /**
     * Best-effort fetch of `person.*` entities, keyed by their `user_id`
     * attribute. A person without a `user_id` (a manually-tracked person not
     * tied to a login) is skipped. Failures return an empty map so the user
     * list still renders.
     */
    private suspend fun loadPersonLinks(): Map<String, PersonLink> {
        val result = haRepository.listRawEntitiesByDomain("person")
        val rows = result.getOrElse {
            R1Log.w("Users", "person link fetch failed: ${it.message}")
            return emptyMap()
        }
        return rows.mapNotNull { row ->
            val userId = (row.attributes["user_id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            userId to PersonLink(
                name = row.friendlyName,
                state = row.state,
                since = row.lastChanged,
            )
        }.toMap()
    }

    private fun buildRows(users: List<HaUser>, links: Map<String, PersonLink>): List<UserRowModel> =
        users.map { user ->
            val link = links[user.id]
            rowModelFor(
                user = user,
                // `config/auth/list` does include `is_owner`, but HaUser
                // (core/ha) doesn't carry the field, so the value can't reach
                // here. Until HaUser/listAuthUsers gains an `isOwner` field this
                // stays false and the OWNER chip never lights. This is the single
                // line to flip once that field exists.
                isOwner = false,
                linkedPersonName = link?.name,
                linkedPersonState = link?.state,
                linkedPersonSince = link?.since,
            )
        }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { UsersViewModel(haRepository) }
        }
    }
}
