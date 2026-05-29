package com.github.itskenny0.r1ha.feature.dashboards.cards

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.itskenny0.r1ha.core.lovelace.LOVELACE_EDIT_JSON
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Fallback for card types R1HA doesn't natively render, with a best-effort
 * tier so most cards show something useful rather than a bare placeholder:
 *
 *  - `iframe` cards (a captured [LovelaceCard.Unsupported.url]) embed a
 *    sandboxed WebView at the card's aspect ratio.
 *  - cards that carry entity refs (`entity` / `entities`, the common
 *    `custom:*` shape) render a generic tile per entity with live state +
 *    tap-to-toggle, under a subtle humanized provenance label. (Recognised
 *    custom cards are mapped to native cards upstream in the parser; this
 *    fallback only catches the custom cards we don't model.)
 *  - everything else keeps the original placeholder + expandable raw-JSON
 *    body so a power user can see why a card isn't rendering and (if useful)
 *    re-author it as a supported type.
 */
@Composable
fun UnsupportedCard(
    card: LovelaceCard.Unsupported,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        card.url != null -> IframeCard(card, modifier)
        card.entityRefs.isNotEmpty() -> CustomEntityCard(card, stateMap, onAction, modifier)
        else -> RawJsonCard(card, modifier)
    }
}

/**
 * Embedded WebView for an `iframe` card. Sandboxed sensibly: JavaScript on
 * (most embeds need it), file + content access off so a hostile URL can't
 * read local files. Aspect ratio honours the card's `aspect_ratio` when set,
 * else a 16:9 default. Caption keeps the source URL visible.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun IframeCard(card: LovelaceCard.Unsupported, modifier: Modifier = Modifier) {
    val rawUrl = card.url ?: return
    // HA's iframe `url` is often relative (e.g. "/local/panel.html"). A relative
    // string handed to WebView.loadUrl renders blank, so resolve it against the
    // configured HA server origin the way the picture cards resolve images.
    val serverUrl = com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl.current
    val url = remember(rawUrl, serverUrl) { resolveIframeUrl(rawUrl, serverUrl) } ?: return
    val ratio = remember(card.raw) { parseAspectRatio(card.raw["aspect_ratio"]?.let { aspectString(it) }) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .padding(8.dp),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(R1.ShapeM),
            factory = { ctx ->
                WebView(ctx).apply {
                    // Match the parent so the AndroidView's aspectRatio constraint
                    // gives the WebView a real, non-zero height. Without explicit
                    // layout params a freshly-constructed WebView can measure to 0,
                    // which renders as a blank card.
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    // Most embeds (Grafana panels, weather widgets, HA add-on UIs)
                    // need DOM storage; without it many render blank or error out.
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    // A dashboard served over https embedding an http panel (or the
                    // reverse on a LAN install) is blocked by the default
                    // MIXED_CONTENT_NEVER_ALLOW, leaving the card blank. Compatibility
                    // mode loads the secure content and upgrades/allows the rest the
                    // way a normal browser does.
                    settings.mixedContentMode =
                        android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    @Suppress("DEPRECATION")
                    run {
                        settings.allowFileAccessFromFileURLs = false
                        settings.allowUniversalAccessFromFileURLs = false
                    }
                }
            },
            update = { web ->
                // Only (re)load when the target actually changed. web.url is null
                // before the first load and tracks redirects after, so the initial
                // composition always loads once.
                if (web.url != url) web.loadUrl(url)
            },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = url,
            style = R1.labelMicro,
            color = R1.InkMuted,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/**
 * Best-effort render of a custom card via its entity refs: one generic tile
 * row per entity (name + live state + tap-to-toggle for toggleable domains),
 * under a subtle humanized label so the user knows it's a fallback rather than
 * a first-class render, without the shouty raw "type:" string.
 */
@Composable
private fun CustomEntityCard(
    card: LovelaceCard.Unsupported,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    CardSurface(modifier = modifier) {
        card.entityRefs.forEachIndexed { idx, ref ->
            if (idx > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(R1.Hairline),
                )
            }
            GenericEntityRow(ref = ref, stateMap = stateMap, onAction = onAction)
        }
        Spacer(Modifier.height(4.dp))
        // Subtle, humanized provenance label. Recognised custom cards are mapped
        // to native cards upstream (see LovelaceParser.mapCustomCard); this
        // fallback only fires for custom cards we don't map, so a quiet
        // "Mushroom Light" reads better than the shouty raw "type:" string.
        Text(
            text = humanizeCardType(card.friendlyType),
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
    }
}

/**
 * Turn a card type token ("mushroom-light-card", "my-slider-button") into a
 * tasteful Title Case label ("Mushroom Light Card"). Drops a leading "custom:"
 * if it survived, splits on dashes/underscores, and capitalises each word.
 */
internal fun humanizeCardType(type: String): String =
    type.removePrefix("custom:")
        .split('-', '_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        .ifBlank { type }

@Composable
private fun GenericEntityRow(
    ref: String,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
) {
    val state = stateMap.byRaw(ref)
    val name = resolveName(null, state, ref)
    val stateText = state?.let { compactStateText(it) }?.takeUnless { it.isBlank() }
    val accent = stateAccentFor(ref, state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick = { onAction(defaultTapAction(ref)) })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = R1.bodyEmph,
            color = R1.Ink,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (stateText != null) {
            Spacer(Modifier.width(10.dp))
            StateChip(text = stateText, accent = accent)
        }
    }
}

/**
 * Original placeholder for cards with no entity refs and no url. Surfaces the
 * card's type string + an expandable raw-JSON body.
 */
@Composable
private fun RawJsonCard(card: LovelaceCard.Unsupported, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val prettyJson = remember(card.raw) {
        runCatching { LOVELACE_EDIT_JSON.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), card.raw) }
            .getOrElse { card.raw.toString() }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, R1.StatusAmber.copy(alpha = 0.6f), R1.ShapeM)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row {
            Text(
                text = "UNSUPPORTED CARD",
                style = R1.sectionHeader,
                color = R1.StatusAmber,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "HIDE" else "SHOW JSON",
                style = R1.labelMicro,
                color = R1.InkSoft,
                modifier = Modifier.r1Pressable(onClick = { expanded = !expanded }),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = humanizeCardType(card.type),
            style = R1.body,
            color = R1.Ink,
        )
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeM)
                    .background(R1.SurfaceMuted)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = prettyJson,
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                    ),
                    color = R1.InkSoft,
                )
            }
        }
    }
}

private fun aspectString(el: kotlinx.serialization.json.JsonElement): String? =
    (el as? kotlinx.serialization.json.JsonPrimitive)?.content

/**
 * Resolve an iframe card `url` into something [WebView.loadUrl] can load.
 * Absolute http(s) URLs pass through; a server-relative path ("/local/...")
 * is joined onto the HA origin; anything else (blank, unsupported scheme with
 * no origin to anchor it) returns null so the card renders its placeholder
 * rather than a blank WebView.
 */
internal fun resolveIframeUrl(raw: String, serverUrl: String?): String? {
    val s = raw.trim()
    return when {
        s.isEmpty() -> null
        s.startsWith("http://") || s.startsWith("https://") -> s
        s.startsWith("/") && !serverUrl.isNullOrBlank() -> serverUrl.trimEnd('/') + s
        else -> null
    }
}

/**
 * Parse HA's `aspect_ratio` ("16:9", "50%", "1.5") into a width/height ratio
 * for [aspectRatio]. Falls back to 16:9 on anything unparseable.
 */
internal fun parseAspectRatio(raw: String?): Float {
    val fallback = 16f / 9f
    if (raw.isNullOrBlank()) return fallback
    val s = raw.trim()
    return when {
        ':' in s -> {
            val parts = s.split(':')
            val w = parts.getOrNull(0)?.trim()?.toFloatOrNull()
            val h = parts.getOrNull(1)?.trim()?.toFloatOrNull()
            if (w != null && h != null && w > 0f && h > 0f) w / h else fallback
        }
        s.endsWith("%") -> {
            val pct = s.dropLast(1).trim().toFloatOrNull()
            // A percentage is height/width in HA; convert to width/height.
            if (pct != null && pct > 0f) 100f / pct else fallback
        }
        else -> s.toFloatOrNull()?.takeIf { it > 0f } ?: fallback
    }
}
