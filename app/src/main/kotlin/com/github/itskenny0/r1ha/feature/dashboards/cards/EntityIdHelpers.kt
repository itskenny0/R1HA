package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityId

/**
 * Safe-construct an [EntityId] from a raw `domain.object_id` string.
 * Returns null when the value isn't shaped right OR the domain isn't
 * in R1HA's supported set (the constructor throws on either; we treat
 * both as "render the card with no live state" rather than crashing).
 *
 * Lives in the dashboards card package because the lovelace renderer
 * works with raw strings from HA's config and needs a lenient path to
 * the typed EntityId; the core EntityId constructor's `require`-throw
 * semantics are correct everywhere else in the app.
 */
internal fun safeEntityId(raw: String): EntityId? =
    runCatching { EntityId(raw) }.getOrNull()
