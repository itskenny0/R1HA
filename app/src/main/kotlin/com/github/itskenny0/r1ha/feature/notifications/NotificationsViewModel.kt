package com.github.itskenny0.r1ha.feature.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.PersistentNotification
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Drives the HA Notifications (persistent_notification.*) surface.
 * Pulls notifications via [HaRepository.listPersistentNotifications]
 * and dispatches dismiss actions via
 * [HaRepository.dismissPersistentNotification]. After a successful
 * dismiss we optimistically remove the row from the in-memory list so
 * the UI updates without waiting for the next refresh — HA's own
 * persistent_notification.dismiss is essentially synchronous so we
 * shouldn't see ghost entries.
 */
class NotificationsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val notifications: List<PersistentNotification> = emptyList(),
        val error: String? = null,
        /** Set of notification IDs whose dismiss is in flight — drives
         *  per-row 'DISMISSING…' affordance and prevents double-tap. */
        val pendingDismiss: Set<String> = emptySet(),
        /** True while a persistent_notification.create call is in flight,
         *  so the create affordance can disable its button + show progress. */
        val creating: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listPersistentNotifications().fold(
                onSuccess = { notifications ->
                    R1Log.i("Notifications", "loaded ${notifications.size}")
                    _ui.value = _ui.value.copy(
                        loading = false,
                        notifications = notifications,
                        error = null,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Notifications", "list failed: ${t.message}")
                    Toaster.error("Notifications load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(
                        loading = false,
                        error = t.message ?: "Failed to load",
                    )
                },
            )
        }
    }

    /** Bulk dismiss every loaded notification. Fires one
     *  persistent_notification.dismiss per row in parallel; each one
     *  shows up in `pendingDismiss` while in flight so the UI greys
     *  the row + button consistently with the single-dismiss path. */
    fun dismissAll() {
        val all = _ui.value.notifications.map { it.notificationId }
        if (all.isEmpty()) return
        _ui.value = _ui.value.copy(pendingDismiss = _ui.value.pendingDismiss + all.toSet())
        viewModelScope.launch {
            // Fire all in parallel. Optimistic UI removal happens per
            // success; failures restore the row + surface a toast.
            kotlinx.coroutines.coroutineScope {
                for (id in all) {
                    launch {
                        haRepository.dismissPersistentNotification(id).fold(
                            onSuccess = {
                                _ui.value = _ui.value.copy(
                                    notifications = _ui.value.notifications.filterNot {
                                        it.notificationId == id
                                    },
                                    pendingDismiss = _ui.value.pendingDismiss - id,
                                )
                            },
                            onFailure = { t ->
                                R1Log.w("Notifications", "dismiss $id failed: ${t.message}")
                                _ui.value = _ui.value.copy(
                                    pendingDismiss = _ui.value.pendingDismiss - id,
                                )
                            },
                        )
                    }
                }
            }
            Toaster.show("Dismissed ${all.size} notification${if (all.size == 1) "" else "s"}")
        }
    }

    fun dismiss(notification: PersistentNotification) {
        if (notification.notificationId in _ui.value.pendingDismiss) return
        _ui.value = _ui.value.copy(
            pendingDismiss = _ui.value.pendingDismiss + notification.notificationId,
        )
        viewModelScope.launch {
            haRepository.dismissPersistentNotification(notification.notificationId).fold(
                onSuccess = {
                    R1Log.i("Notifications", "dismissed ${notification.notificationId}")
                    // Optimistic remove — HA's dismiss is synchronous so the row
                    // really is gone by now.
                    _ui.value = _ui.value.copy(
                        notifications = _ui.value.notifications.filterNot {
                            it.notificationId == notification.notificationId
                        },
                        pendingDismiss = _ui.value.pendingDismiss - notification.notificationId,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Notifications", "dismiss failed: ${t.message}")
                    Toaster.error("Dismiss failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(
                        pendingDismiss = _ui.value.pendingDismiss - notification.notificationId,
                    )
                },
            )
        }
    }

    /**
     * Create a persistent notification via `persistent_notification.create`.
     * Mirrors what an automation or the HA frontend's developer tools would
     * do; handy for verifying the dismiss path end to end without waiting for
     * a real integration to raise one. [title] is optional (HA defaults to
     * "Notification"); [message] is required by the service. On success we
     * refresh so the new row appears in the list.
     */
    fun create(title: String, message: String) {
        if (_ui.value.creating) return
        val payload = buildCreatePayload(title, message) ?: run {
            Toaster.error("Message can't be empty")
            return
        }
        _ui.value = _ui.value.copy(creating = true)
        viewModelScope.launch {
            haRepository.callRawService("persistent_notification", "create", payload).fold(
                onSuccess = {
                    R1Log.i("Notifications", "created notification")
                    Toaster.show("Notification created")
                    _ui.value = _ui.value.copy(creating = false)
                    refresh()
                },
                onFailure = { t ->
                    R1Log.w("Notifications", "create failed: ${t.message}")
                    Toaster.error("Create failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(creating = false)
                },
            )
        }
    }

    companion object {
        /**
         * Build the `persistent_notification.create` service payload from a
         * raw [title]/[message] pair. Pure + side-effect free so it can be
         * unit-tested: trims both fields, drops a blank title (HA supplies a
         * default), and returns null when the message is blank since the
         * service rejects an empty body. Kept out of the instance method so
         * the validation rules are testable without a repository.
         */
        fun buildCreatePayload(title: String, message: String): JsonObject? {
            val trimmedMessage = message.trim()
            if (trimmedMessage.isEmpty()) return null
            val trimmedTitle = title.trim()
            return buildJsonObject {
                put("message", JsonPrimitive(trimmedMessage))
                if (trimmedTitle.isNotEmpty()) {
                    put("title", JsonPrimitive(trimmedTitle))
                }
            }
        }

        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { NotificationsViewModel(haRepository) }
        }
    }
}
