package com.github.itskenny0.r1ha.feature.quicktile

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import androidx.annotation.RequiresApi
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.R
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Shared logic backing the multiple `HaQuickTileService*` classes. Android requires one
 * concrete TileService class per Quick Settings tile (binding is by class name); rather
 * than duplicate ~80 lines of state-machine code per tile, every service delegates here
 * and provides a function that picks the right `quickTileEntityId*` slot from settings.
 *
 * The selector reads from [AppSettings.behavior] so a future schema migration that adds
 * a fifth slot can land by extending the selector function — the manifest service entry
 * + a new selector arg is all that's needed to expose another tile.
 */
internal object QuickTileLogic {

    /**
     * Set the tile subtitle (the small secondary line under the label) only on
     * API 29+, where `Tile.setSubtitle` exists. On API 26-28 the call is a no-op:
     * those devices show just the label, which already carries the entity name,
     * and the active/inactive tint conveys on/off state, so nothing functional is
     * lost. Keeps the tile feature usable down to the minSdk floor.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun Tile.setSubtitleCompat(text: CharSequence) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            subtitle = text
        }
    }

    /**
     * Refresh the visible label/state of a tile from the live entity cache. Safe to
     * call from any TileService callback that has access to its `qsTile`.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun refresh(
        context: Context,
        qsTile: Tile?,
        scope: CoroutineScope,
        selector: (AppSettings) -> String?,
    ) {
        val graph = (context.applicationContext as App).graph
        scope.launch {
            try {
                val tile = qsTile ?: return@launch
                val settings = graph.settings.settings.first()
                val rawId = selector(settings)?.takeIf { it.isNotBlank() }
                if (rawId.isNullOrBlank()) {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = "HA. Set entity"
                    tile.setSubtitleCompat("Tap to open app")
                    tile.icon = Icon.createWithResource(context, R.mipmap.ic_launcher)
                    tile.updateTile()
                    return@launch
                }
                // EntityId only validates the `domain.object_id` shape now (unknown domains
                // resolve to Domain.OTHER rather than being rejected), so this branch fires
                // only when the stored string is genuinely malformed.
                val entityId = runCatching { EntityId(rawId) }.getOrNull()
                if (entityId == null) {
                    tile.state = Tile.STATE_UNAVAILABLE
                    tile.label = "HA. Bad entity_id"
                    tile.setSubtitleCompat(rawId)
                    tile.icon = Icon.createWithResource(context, R.mipmap.ic_launcher)
                    tile.updateTile()
                    return@launch
                }
                val live = graph.haRepository.listAllEntities().getOrNull()
                    ?.firstOrNull { it.id == entityId }
                if (live == null) {
                    tile.state = Tile.STATE_UNAVAILABLE
                    tile.label = entityId.value
                    tile.setSubtitleCompat("not loaded yet")
                    tile.icon = Icon.createWithResource(context, R.mipmap.ic_launcher)
                    tile.updateTile()
                    return@launch
                }
                tile.label = live.friendlyName
                tile.icon = Icon.createWithResource(context, R.mipmap.ic_launcher)
                when {
                    // HA reported `unavailable` / `unknown`: surface that instead of a
                    // misleading OFF (isOn is false for an unavailable entity too).
                    !live.isAvailable -> {
                        tile.state = Tile.STATE_UNAVAILABLE
                        tile.setSubtitleCompat("unavailable")
                    }
                    // Scene / script / button are stateless fire-and-forget actions:
                    // ON/OFF is meaningless, so show a neutral inactive tile that just
                    // invites a tap to run.
                    live.id.domain.isAction -> {
                        tile.state = Tile.STATE_INACTIVE
                        tile.setSubtitleCompat("Tap to run")
                    }
                    else -> {
                        tile.setSubtitleCompat(if (live.isOn) "ON" else "OFF")
                        tile.state = if (live.isOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    }
                }
                tile.updateTile()
            } catch (t: Throwable) {
                R1Log.w("QuickTileLogic", "refresh failed: ${t.message}")
            }
        }
    }

    /**
     * Handle a tile tap: fetch the bound entity, dispatch a toggle (or action-fire for
     * scene/script/button), and refresh after a brief settle delay so the displayed
     * state matches what HA echoed back.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun click(
        context: Context,
        qsTile: Tile?,
        scope: CoroutineScope,
        selector: (AppSettings) -> String?,
        launchAppForSetup: () -> Unit,
    ) {
        val graph = (context.applicationContext as App).graph
        scope.launch {
            try {
                val settings = graph.settings.settings.first()
                val rawId = selector(settings)?.takeIf { it.isNotBlank() }
                if (rawId == null) {
                    R1Log.i("QuickTileLogic", "no entity bound; opening app")
                    launchAppForSetup()
                    return@launch
                }
                val entityId = runCatching { EntityId(rawId) }.getOrNull()
                if (entityId == null) {
                    R1Log.w("QuickTileLogic", "stored entity_id is malformed: $rawId")
                    return@launch
                }
                val live = graph.haRepository.listAllEntities().getOrNull()
                    ?.firstOrNull { it.id == entityId }
                if (live == null) {
                    R1Log.w("QuickTileLogic", "${entityId.value} not in entity map yet")
                    return@launch
                }
                // Don't toggle an unavailable entity: HA would reject the service call and
                // the tile would flicker. Actions stay tappable (they have no availability
                // gate that matters for a fire-and-forget run). Just repaint the tile so its
                // 'unavailable' subtitle is fresh.
                if (!live.isAvailable && !live.id.domain.isAction) {
                    R1Log.i("QuickTileLogic", "${entityId.value} unavailable; skipping toggle")
                    refresh(context, qsTile, scope, selector)
                    return@launch
                }
                val call = if (live.id.domain.isAction) {
                    ServiceCall(target = live.id, service = "turn_on", data = JsonObject(emptyMap()))
                } else {
                    ServiceCall.tapAction(live.id, live.isOn)
                }
                graph.haRepository.call(call)
                R1Log.i("QuickTileLogic", "tapped ${entityId.value} (was on=${live.isOn})")
                delay(600L)
                refresh(context, qsTile, scope, selector)
            } catch (t: Throwable) {
                R1Log.w("QuickTileLogic", "click failed: ${t.message}")
            }
        }
    }
}
