package com.github.itskenny0.r1ha.wear.feature.cardstack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.input.WheelEvent
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * UI state for the Wear card stack pager.
 *
 * [cards] is ordered by the user's favourites list. [optimisticPercents] holds
 * immediately-applied value overrides that will be cleared when HA confirms the
 * change via a state_changed event.
 */
data class WearCardStackUiState(
    val cards: List<EntityState> = emptyList(),
    val currentIndex: Int = 0,
    val optimisticPercents: Map<EntityId, Int> = emptyMap(),
    val settingsLoaded: Boolean = false,
    val favouritesCount: Int = 0,
)

/**
 * Simplified card stack ViewModel for the Wear OS app.
 *
 * Mirrors the phone [CardStackViewModel] logic for the features that make sense
 * on a watch:
 *
 * - Observes the user's favourites list from [SettingsRepository]
 * - Feeds the entity IDs to [HaRepository.observe] for live state updates
 * - Handles tap → toggle service calls
 * - Handles wheel → scalar adjustment (brightness, fan speed, cover, media volume)
 *   with optimistic UI updates identical to the phone implementation
 *
 * The wheel subscription is always active: on the watch the card at [currentIndex]
 * is the "focused" entity, so any rotary/rim event applies to it. There is no
 * per-screen focus management needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearCardStackViewModel(
    private val haRepository: HaRepository,
    private val settings: SettingsRepository,
    private val wheelInput: WheelInput,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WearCardStackUiState())
    val uiState: StateFlow<WearCardStackUiState> = _uiState

    init {
        // Keep a local snapshot of settings for the wheel handler (called from a
        // hot flow callback that can't suspend to read DataStore).
        settings.settings
            .onEach { _latestSettings = it }
            .launchIn(viewModelScope)

        observeFavorites()
        collectWheelEvents()
        collectCallFailures()
    }

    // ── Favourites observation ───────────────────────────────────────────────

    private fun observeFavorites() {
        settings.settings
            .map { s ->
                // Use the first page's favourites list (main/default page).
                val favIds = s.pages.firstOrNull()?.favorites.orEmpty()
                Pair(favIds, s.wheel)
            }
            .distinctUntilChanged()
            .flatMapLatest { (favIds, _) ->
                _uiState.value = _uiState.value.copy(
                    settingsLoaded = true,
                    favouritesCount = favIds.size,
                )
                if (favIds.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    haRepository.observe(favIds.toSet())
                }
            }
            .onEach { stateMap ->
                val orderedIds = latestSettings
                    .pages.firstOrNull()?.favorites.orEmpty()
                val orderedCards = orderedIds.mapNotNull { stateMap[it] }
                _uiState.value = _uiState.value.copy(
                    cards = orderedCards,
                    // Clear any optimistic overrides that HA has now confirmed.
                    optimisticPercents = _uiState.value.optimisticPercents
                        .filterKeys { id -> stateMap[id]?.let { it.percent } == null },
                )
            }
            .launchIn(viewModelScope)
    }

    // ── Wheel events ─────────────────────────────────────────────────────────

    private fun collectWheelEvents() {
        var windowStart = 0L
        var windowCount = 0
        val windowMs = 250L

        wheelInput.events
            .onEach { event ->
                val now = event.timestampMillis
                if (now - windowStart > windowMs) {
                    windowStart = now
                    windowCount = 0
                }
                windowCount++
                val ratePerSec = (windowCount / (windowMs / 1000.0))

                val wheelSettings = latestSettings.wheel
                val step = WheelInput.effectiveStep(
                    base = wheelSettings.stepPercent,
                    ratePerSec = ratePerSec,
                    accelerate = wheelSettings.acceleration,
                    curve = wheelSettings.accelerationCurve,
                )

                val direction = WheelInput.applyDirection(
                    d = event.direction,
                    invert = wheelSettings.invertDirection,
                )

                val state = _uiState.value
                val entity = state.cards.getOrNull(state.currentIndex) ?: return@onEach
                if (!entity.supportsScalar) return@onEach

                val currentPct = state.optimisticPercents[entity.id]
                    ?: entity.percent
                    ?: return@onEach

                val newPct = (currentPct + direction * step).coerceIn(0, 100)
                if (newPct == currentPct) return@onEach

                R1Log.d("WearCardStack.wheel", "${entity.id} $currentPct→$newPct (step=$step)")
                _uiState.value = state.copy(
                    optimisticPercents = state.optimisticPercents + (entity.id to newPct),
                )
                viewModelScope.launch {
                    haRepository.call(ServiceCall.setPercent(entity.id, newPct))
                }
            }
            .launchIn(viewModelScope)
    }

    // ── Call-failure rollback ────────────────────────────────────────────────

    private fun collectCallFailures() {
        haRepository.callFailures
            .onEach { failedId ->
                _uiState.value = _uiState.value.copy(
                    optimisticPercents = _uiState.value.optimisticPercents - failedId,
                )
            }
            .launchIn(viewModelScope)
    }

    // ── Public actions ───────────────────────────────────────────────────────

    fun onPageChanged(newIndex: Int) {
        _uiState.value = _uiState.value.copy(currentIndex = newIndex)
    }

    fun onCardTap(entity: EntityState) {
        viewModelScope.launch {
            haRepository.call(ServiceCall.tapAction(entity.id, entity.isOn))
        }
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    companion object {
        fun factory(
            haRepository: HaRepository,
            settings: SettingsRepository,
            wheelInput: WheelInput,
        ) = viewModelFactory {
            initializer {
                WearCardStackViewModel(haRepository, settings, wheelInput)
            }
        }
    }
}
