package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityState

/**
 * Resolve the state of the current user's person entity for the Lovelace
 * `location` condition: the `person.*` entity whose `user_id` attribute matches
 * [userId] (HA's getUserPerson), returning its state string. Returns null when
 * there is no current user, no entity map, or no matching person entity is in
 * the live set (the condition then fails closed, matching HA when getUserPerson
 * yields nothing).
 *
 * Only person entities already in the caller's entity stream are visible here;
 * a `location` gate over a person nobody subscribed evaluates as "unknown" until
 * that person is observed. Shared by the dashboards screen (its view-level
 * entity stream) and the card-stack deck (the pinned-card state union) so both
 * surfaces resolve the person identically.
 */
internal fun resolveUserPersonState(
    userId: String?,
    entities: Map<String, EntityState>?,
): String? {
    if (userId == null || entities == null) return null
    for ((rawId, state) in entities) {
        if (!rawId.startsWith("person.")) continue
        val attrUserId = (state.attributesJson?.get("user_id")
            as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (attrUserId == userId) return state.rawState
    }
    return null
}
