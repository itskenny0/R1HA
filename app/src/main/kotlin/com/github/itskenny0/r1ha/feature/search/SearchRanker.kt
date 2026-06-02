package com.github.itskenny0.r1ha.feature.search

import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState

/**
 * Pure search filter + sort, extracted from SearchViewModel so it can be unit-tested
 * (and stress-tested) without spinning up a full ViewModel + repository graph.
 *
 *  - Substring match on friendlyName / entity_id / area / domain (case-insensitive).
 *    Domain matching lets "climate", "binary sensor", "automation" etc. surface every
 *    entity of that kind, mirroring HA's quick-bar which weights a domain-name match.
 *  - Bucket filter pinned to a caller-supplied (Domain → Bucket) mapper so this stays
 *    independent of the ViewModel's bucketOf instance method.
 *  - Sort by "name/id prefix-match first, then friendlyName" so a query that opens a
 *    name (or the id's object part) floats above mid-string and domain-only matches.
 *  - Caps the result list to `resultCap` so a thousand-match query returns predictably.
 */
internal object SearchRanker {
    fun filter(
        all: List<EntityState>,
        query: String,
        bucket: SearchViewModel.Bucket,
        bucketOf: (Domain) -> SearchViewModel.Bucket,
        resultCap: Int,
    ): List<EntityState> {
        // Locale-invariant fold (Locale.ROOT) so matching never depends on the device
        // locale. Default lowercase() would fold a dotted/dotless "I" differently under a
        // Turkish locale, so "Light" typed on a tr-TR device could miss "light.*" entities.
        val q = query.trim().lowercase(java.util.Locale.ROOT)
        if (q.isBlank() && bucket == SearchViewModel.Bucket.ALL) return emptyList()
        // Lowercase each entity's searchable fields exactly once. Previously the
        // filter predicate and the sort comparator both called lowercase() per
        // entity, and the comparator runs O(n log n) times: a 2000-entity sort
        // re-lowercased every friendlyName ~11x. Caching into a small Scored
        // holder collapses that to one lowercase() per field per entity, then
        // sorts on the precomputed strings before unwrapping back to EntityState.
        val scored = ArrayList<Scored>(if (all.size < resultCap) all.size else resultCap.coerceAtLeast(16))
        for (e in all) {
            val matchesBucket = bucket == SearchViewModel.Bucket.ALL || bucketOf(e.id.domain) == bucket
            if (!matchesBucket) continue
            val nameLower = e.friendlyName.lowercase(java.util.Locale.ROOT)
            val idLower = e.id.value.lowercase(java.util.Locale.ROOT)
            if (q.isNotBlank()) {
                // Domain match mirrors HA's quick-bar domain-name key: the raw HA domain
                // prefix (e.g. "binary_sensor") with underscores read as spaces, so both
                // "binary" and "binary sensor" surface every binary_sensor entity. The
                // id already carries the prefix, but idLower.contains(q) alone would also
                // hit on the object-id half; matching the prefix explicitly keeps the
                // intent ("show me all X") distinct from a substring fluke.
                val domainPrefix = e.id.domain.prefix
                val matchesDomain = domainPrefix.isNotEmpty() && (
                    domainPrefix.contains(q) ||
                        // Only pay the underscore->space rewrite for multi-word domains
                        // (binary_sensor, media_player, …); single-word ones can't gain a
                        // match from it and shouldn't allocate on the hot path.
                        (domainPrefix.indexOf('_') >= 0 && domainPrefix.replace('_', ' ').contains(q))
                    )
                val matchesQuery = nameLower.contains(q) ||
                    idLower.contains(q) ||
                    (e.area?.lowercase(java.util.Locale.ROOT)?.contains(q) ?: false) ||
                    matchesDomain
                if (!matchesQuery) continue
            }
            val prefixMatch = q.isNotBlank() && (
                nameLower.startsWith(q) || idLower.substringAfter('.').startsWith(q)
                )
            scored.add(Scored(e, nameLower, prefixMatch))
        }
        scored.sortWith(
            compareByDescending<Scored> { it.prefixMatch }.thenBy { it.nameLower },
        )
        return if (scored.size <= resultCap) {
            scored.map { it.entity }
        } else {
            scored.subList(0, resultCap).map { it.entity }
        }
    }

    /** Per-entity scratch holder so the hot sort path reads precomputed lowercased
     *  strings instead of re-lowercasing on every comparator invocation. */
    private class Scored(
        val entity: EntityState,
        val nameLower: String,
        val prefixMatch: Boolean,
    )
}
