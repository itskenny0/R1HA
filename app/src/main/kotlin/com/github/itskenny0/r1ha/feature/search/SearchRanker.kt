package com.github.itskenny0.r1ha.feature.search

import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState

/**
 * Pure search filter + sort, extracted from SearchViewModel so it can be unit-tested
 * (and stress-tested) without spinning up a full ViewModel + repository graph.
 *
 *  - Substring match on friendlyName / entity_id / area (case-insensitive).
 *  - Bucket filter pinned to a caller-supplied (Domain → Bucket) mapper so this stays
 *    independent of the ViewModel's bucketOf instance method.
 *  - Sort by "prefix-match first, then friendlyName" so a query that exactly opens a
 *    name floats it to the top.
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
        val q = query.trim().lowercase()
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
            val nameLower = e.friendlyName.lowercase()
            val idLower = e.id.value.lowercase()
            if (q.isNotBlank()) {
                val matchesQuery = nameLower.contains(q) ||
                    idLower.contains(q) ||
                    (e.area?.lowercase()?.contains(q) ?: false)
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
