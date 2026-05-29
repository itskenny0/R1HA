package com.github.itskenny0.r1ha.core.ha

import androidx.compose.runtime.Stable
import java.time.Instant

/**
 * One row from HA's `/api/logbook` endpoint — a timestamped record of
 * something noteworthy happening (state change, automation trigger,
 * script invocation, scene activation). HA's response is sparse: most
 * fields are optional and depend on the integration that produced the
 * event. We carry every field nullable rather than coercing to defaults
 * so the UI can suppress chrome (entity-id label, domain accent) when
 * the underlying event doesn't include it.
 *
 * @Stable so the LazyColumn item in [LogbookScreen] can skip
 *  recomposition when the row reference is unchanged across a refresh.
 */
@Stable
data class LogbookEntry(
    val timestamp: Instant,
    val name: String,
    val message: String,
    /** entity_id if HA included one — most state-change events do, automation
     *  triggers usually do, some "context" events don't. */
    val entityId: EntityId?,
    /** Domain prefix from HA's payload (e.g. "light", "automation"). Kept
     *  raw rather than parsed to a [Domain] so HA-side domains we don't
     *  support yet still show up in the log (e.g. `weather`, `person`). */
    val domain: String?,
    /** Final state after the event ("on", "off", "playing"). Useful for the
     *  small chip on the right of each row that previews the post-event
     *  state at a glance. Null when the event is stateless (e.g. an
     *  automation trigger). */
    val state: String?,
    /** HA's `context_user_id`: the user account that initiated whatever caused
     *  this event. Present when a person (not an automation) flipped the switch.
     *  Opaque GUID; we surface it only as a last-resort attribution when no
     *  human-readable [contextName] is available. */
    val contextUserId: String? = null,
    /** HA's `context_entity_id`: the entity that triggered this event (e.g. the
     *  motion sensor whose state change ran an automation that turned on a
     *  light). Carried as a raw String rather than an [EntityId] because context
     *  sources are routinely from domains we don't model (`person.*`, `sun.sun`,
     *  `device_tracker.*`) and would fail EntityId's domain validation. */
    val contextEntityId: String? = null,
    /** Human-readable label for the triggering context, when HA resolves one
     *  (`context_entity_id_name` / `context_name`). Preferred over the raw id
     *  for the "by …" / "via …" attribution suffix on the row. */
    val contextName: String? = null,
)
