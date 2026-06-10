package com.github.itskenny0.r1ha.feature.dashboards.cards

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    // A handful of first-class HA card types aren't in R1HA's typed
    // [LovelaceCard] model (the parser keeps them as Unsupported, preserving
    // the lowercased `type` string + the entity refs it scraped). Dispatch
    // those to their dedicated renderers here so they don't fall through to
    // the generic entity-tile / raw-JSON fallback. Type strings match HA's
    // card registry (hui-todo-list-card / hui-entity-card / hui-toggle-group-card).
    when (card.type) {
        "todo-list",
        // shopping-list is HA's legacy alias for the same card (renamed to
        // todo-list in 2023.11 once the entity platform was unified). Dispatch
        // both to the same renderer so older dashboard configs still work.
        "shopping-list" -> {
            TodoListCard(card, stateMap, modifier)
            return
        }
        "toggle-group" -> {
            ToggleGroupCard(card, stateMap, onAction, modifier)
            return
        }
        "entity" -> {
            EntityCard(card, stateMap, onAction, modifier)
            return
        }
        "plant-status" -> {
            PlantStatusCard(card, stateMap, onAction, modifier)
            return
        }
        "discovered-devices" -> {
            DiscoveredDevicesCard(card, modifier)
            return
        }
        // HA emits an `error` card ({type: error, error: "...", origConfig: {...}})
        // when a card fails to build. Render it with the message + collapsible raw
        // config, matching hui-error-card's role.
        "error" -> {
            ErrorCard(card, modifier)
            return
        }
    }
    when {
        card.url != null -> IframeCard(card, modifier)
        card.entityRefs.isNotEmpty() -> CustomEntityCard(card, stateMap, onAction, modifier)
        else -> RawJsonCard(card, modifier)
    }
}

/**
 * Embedded WebView for an `iframe` / `webpage` card. Sandboxed sensibly:
 * JavaScript on (most embeds need it), file + content access off so a hostile
 * URL can't read local files. Aspect ratio honours the card's `aspect_ratio`
 * when set, else a 16:9 default. Links open in the card (a WebViewClient keeps
 * navigation in-frame). A url that can't be resolved into something the WebView
 * can load degrades to a labeled placeholder rather than a silent blank card,
 * and a slow or failed load surfaces a loading / error line.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun IframeCard(card: LovelaceCard.Unsupported, modifier: Modifier = Modifier) {
    val rawUrl = card.url
    // HA's iframe `url` is often relative (e.g. "/local/panel.html"). A relative
    // string handed to WebView.loadUrl renders blank, so resolve it against the
    // configured HA server origin the way the picture cards resolve images.
    val serverUrl = com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl.current
    val url = remember(rawUrl, serverUrl) { rawUrl?.let { resolveIframeUrl(it, serverUrl) } }
    val ratio = remember(card.raw) { parseAspectRatio(card.raw["aspect_ratio"]?.let { aspectString(it) }) }
    val title = card.iframeTitle?.takeUnless { it.isBlank() }
    val description = title ?: card.friendlyType.ifBlank { "Embedded web content" }
    // hide_background drops the card surface (background, border, padding) so the
    // frame floats transparently, mirroring ha-card.hide-background.
    val surface = if (card.hideBackground) {
        Modifier
    } else {
        Modifier
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .padding(8.dp)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(surface),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = R1.titleCard,
                color = R1.Ink,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
        }
        if (url == null) {
            // Unresolvable url (blank, unsupported scheme, or a relative path with
            // no server origin to anchor it). Show why instead of a blank box.
            IframePlaceholder(
                message = iframeStatusMessage(rawUrl, serverUrl),
                ratio = ratio,
            )
            return@Column
        }
        EmbeddedWebView(url = url, ratio = ratio, contentDescription = description)
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
 * The WebView surface itself plus its loading / error overlay. Pulled out of
 * [IframeCard] so the load-state plumbing stays readable. The host Activity is
 * never captured (applicationContext is used) so the view can't leak it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbeddedWebView(url: String, ratio: Float, contentDescription: String) {
    var loading by remember(url) { mutableStateOf(true) }
    var failed by remember(url) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(R1.ShapeM)
            .background(R1.SurfaceMuted)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
            factory = { ctx ->
                WebView(ctx.applicationContext).apply {
                    // Match the parent so the AndroidView's aspectRatio constraint
                    // gives the WebView a real, non-zero height. Without explicit
                    // layout params a freshly-constructed WebView can measure to 0,
                    // which renders as a blank card.
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            // Keep navigation inside the card instead of leaving
                            // the app for a system browser.
                            view.loadUrl(request.url.toString())
                            return true
                        }

                        override fun onPageStarted(view: WebView, target: String?, favicon: Bitmap?) {
                            loading = true
                            failed = false
                        }

                        override fun onPageFinished(view: WebView, target: String?) {
                            loading = false
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            // Only a main-frame failure flips the card to its error
                            // state; a failed sub-resource shouldn't blank the page.
                            if (request.isForMainFrame) {
                                loading = false
                                failed = true
                            }
                        }
                    }
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
                    loadUrl(url)
                }
            },
            update = { web ->
                // Only (re)load when the target actually changed. web.url is null
                // before the first load and tracks redirects after, so a target
                // change reloads while normal navigation does not.
                if (web.url != url && !failed) web.loadUrl(url)
            },
        )
        when {
            failed -> Text(
                text = "Could not load this page.",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
            loading -> Text(
                text = "Loading...",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
    }
}

@Composable
private fun IframePlaceholder(message: String, ratio: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(R1.ShapeM)
            .background(R1.SurfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/**
 * Human-readable reason an iframe / webpage card can't load its [rawUrl].
 * Pure (no Compose / Android) so the placeholder copy is unit testable.
 *
 *  - blank url            -> "No web address set for this card."
 *  - relative path, no HA origin to anchor it -> server-not-set message.
 *  - anything else (unsupported scheme)       -> generic can't-display message.
 *
 * Only called when [resolveIframeUrl] already returned null, so a value that
 * would have resolved never reaches here.
 */
internal fun iframeStatusMessage(rawUrl: String?, serverUrl: String?): String {
    val trimmed = rawUrl?.trim().orEmpty()
    return when {
        trimmed.isEmpty() -> "No web address set for this card."
        trimmed.startsWith("/") && serverUrl.isNullOrBlank() ->
            "Set the Home Assistant server address to load this page."
        else -> "Can't display this web address."
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
 * Placeholder for cards with no entity refs and no url. Surfaces the
 * card's type string + an expandable raw-JSON body.
 *
 * HA's hui-error-card shows the error title only to admins and the full
 * message only in editor preview. R1HA shows the type and the SHOW JSON
 * expander to every user because the audience of the R1 companion is the
 * HA owner, who is always an admin; there is no multi-tenant guest audience
 * to gate detail from. This is intentional: surfacing config problems helps
 * the owner fix them.
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
            // Only offer the JSON expander when there is a raw payload to show;
            // a config-error card (non-object entry) has an empty raw object.
            if (card.raw.isNotEmpty()) {
                Text(
                    text = if (expanded) "HIDE" else "SHOW JSON",
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    modifier = Modifier.r1Pressable(onClick = { expanded = !expanded }),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            // friendlyType carries a human-readable label (e.g. the config-error
            // message "Config is not an object: ...") when it differs from the raw
            // type; fall back to the humanized type token otherwise.
            text = card.friendlyType.takeUnless { it == card.type }
                ?: humanizeCardType(card.type),
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

/**
 * Renderer for HA's `error` card (hui-error-card.ts), which HA emits in place of
 * a card that failed to build. Shows the `error` message prominently in the
 * error (amber) severity plus a collapsible view of the original card config
 * (`origConfig`). On the personal-device R1 the audience is the HA owner, so the
 * detail is shown rather than admin-gated.
 */
@Composable
private fun ErrorCard(card: LovelaceCard.Unsupported, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val message = (card.raw["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        ?.takeUnless { it.isBlank() }
        ?: card.friendlyType.takeUnless { it == card.type }
        ?: "Card error"
    val origConfig = card.raw["origConfig"] as? kotlinx.serialization.json.JsonObject
    val prettyJson = remember(origConfig) {
        origConfig?.let {
            runCatching { LOVELACE_EDIT_JSON.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), it) }
                .getOrElse { _ -> it.toString() }
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, R1.StatusRed.copy(alpha = 0.6f), R1.ShapeM)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row {
            Text(text = "CARD ERROR", style = R1.sectionHeader, color = R1.StatusRed, modifier = Modifier.weight(1f))
            if (prettyJson != null) {
                Text(
                    text = if (expanded) "HIDE" else "SHOW CONFIG",
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    modifier = Modifier.r1Pressable(onClick = { expanded = !expanded }),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(text = message, style = R1.body, color = R1.Ink)
        if (expanded && prettyJson != null) {
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
