package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.itskenny0.r1ha.core.ha.ExtEntityRegistryOptions
import com.github.itskenny0.r1ha.core.ha.HaRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide TTL cache over [HaRepository.getExtendedEntityRegistryOptions].
 *
 * The favorite-position / favorite-colour / default-code card-features all read
 * the same `config/entity_registry/get` payload, and several features can target
 * one entity on a dashboard, so this coalesces to one WS round trip per entity
 * per TTL window. Concurrent callers for the same id wait on a shared mutex so a
 * card with two favorite features fires one fetch, not two. A failed or
 * unsupported fetch is cached as [ExtEntityRegistryOptions.EMPTY] for the window
 * so an old server isn't re-polled on every recomposition.
 */
object EntityRegistryOptionsCache {
    private data class Entry(val value: ExtEntityRegistryOptions, val fetchedAtMs: Long)

    private const val TTL_MS = 60_000L
    private val mutex = Mutex()
    private val cache = HashMap<String, Entry>()

    /** Drop everything (used after a registry-changing action). */
    fun invalidate() {
        synchronized(cache) { cache.clear() }
    }

    /** Drop one entity's cached entry so the next read re-fetches it (used after
     *  a successful favourites write from the more-info sheet). */
    fun invalidate(entityId: String) {
        synchronized(cache) { cache.remove(entityId) }
    }

    suspend fun get(repo: HaRepository, entityId: String, nowMs: Long): ExtEntityRegistryOptions {
        synchronized(cache) {
            cache[entityId]?.let { if (nowMs - it.fetchedAtMs < TTL_MS) return it.value }
        }
        return mutex.withLock {
            synchronized(cache) {
                cache[entityId]?.let { if (nowMs - it.fetchedAtMs < TTL_MS) return it.value }
            }
            val fresh = repo.getExtendedEntityRegistryOptions(entityId)
                .getOrDefault(ExtEntityRegistryOptions.EMPTY)
            synchronized(cache) { cache[entityId] = Entry(fresh, nowMs) }
            fresh
        }
    }
}

/**
 * Resolve [entityId]'s registry options, returning [ExtEntityRegistryOptions.EMPTY]
 * until the first fetch lands. Backed by [EntityRegistryOptionsCache] so the
 * fetch is shared across every feature targeting the same entity.
 */
@Composable
fun rememberEntityRegistryOptions(
    repo: HaRepository?,
    entityId: String,
): ExtEntityRegistryOptions {
    var options by remember(entityId) { mutableStateOf(ExtEntityRegistryOptions.EMPTY) }
    if (repo != null) {
        LaunchedEffect(entityId) {
            options = EntityRegistryOptionsCache.get(repo, entityId, System.currentTimeMillis())
        }
    }
    return options
}
