package com.github.itskenny0.r1ha.core.ha

@JvmInline
value class EntityId(val value: String) {
    init {
        // Require only the shape domain.object_id. We deliberately no longer reject unsupported
        // domains: an entity from a domain the app has no card archetype for (device_tracker,
        // zone, calendar, ...) is still a real entity the user owns and must be findable in
        // Universal Search. Its [domain] resolves to [Domain.OTHER]; surfaces that only handle
        // archetypes (favourites picker, card stack) filter OTHER out at their own boundaries.
        val dot = value.indexOf('.')
        require(dot > 0 && dot < value.length - 1) { "entity_id must be 'domain.object_id': '$value'" }
    }
    val domain: Domain get() = Domain.fromPrefixOrOther(value.substringBefore('.'))
    val objectId: String get() = value.substringAfter('.')
    override fun toString(): String = value
}
