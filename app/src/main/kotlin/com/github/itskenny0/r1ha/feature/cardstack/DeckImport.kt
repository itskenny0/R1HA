package com.github.itskenny0.r1ha.feature.cardstack

import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceView
import com.github.itskenny0.r1ha.core.lovelace.encodeCardJson

/**
 * Pure mapping from a server Lovelace view to a cardstack page spec, used by
 * the dashboard-import flow. Kept free of Compose / repository types so the
 * mapping rules are unit-testable.
 */

/** What one imported page will contain: a tab name + the cards' raw configs. */
data class ImportablePage(
    val name: String,
    val cardBlobs: List<String>,
)

/** Page-name cap. Matches the 20-char limit TabManageDialog enforces on
 *  hand-typed names so imported tabs obey the same chip budget. */
private const val PAGE_NAME_MAX = 20

/**
 * Display/tab name for an imported view: title first, then path, then a
 * positional fallback. Uppercased + trimmed to the tab-chip budget so the
 * imported tab reads like a hand-made one.
 */
fun importPageName(view: LovelaceView, index: Int): String {
    val base = view.title?.takeIf { it.isNotBlank() }
        ?: view.path.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
        ?: "VIEW ${index + 1}"
    return base.trim().uppercase().take(PAGE_NAME_MAX)
}

/** A view's cards as storable pinned-card blobs (verbatim raw configs, so the
 *  import round-trips options R1HA doesn't model). */
fun viewCardBlobs(view: LovelaceView): List<String> =
    view.cards.map { encodeCardJson(it.raw) }

/** Map [views] to page specs, skipping views with no renderable cards (a
 *  strategy view that failed to expand, or an empty tab mid-edit on HA's
 *  side) so the import never creates dead tabs. */
fun viewsToImportablePages(views: List<LovelaceView>): List<ImportablePage> =
    views.mapIndexedNotNull { index, view ->
        val blobs = viewCardBlobs(view)
        if (blobs.isEmpty()) null else ImportablePage(importPageName(view, index), blobs)
    }

/** Storable blob for a single picked card (the from-dashboard add path). */
fun cardBlob(card: LovelaceCard): String = encodeCardJson(card.raw)
