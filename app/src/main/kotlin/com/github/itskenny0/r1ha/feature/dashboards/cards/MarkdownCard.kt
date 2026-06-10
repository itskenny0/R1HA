package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.lovelace.CardActions
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.MarkdownView
import com.github.itskenny0.r1ha.ui.components.parseMarkdown
import kotlinx.coroutines.launch

/**
 * Renderer for HA's `markdown` card. Mirrors hui-markdown-card.ts: the card's
 * [LovelaceCard.Markdown.content] is treated as a Jinja template and subscribed
 * over the `render_template` WebSocket command, so the body re-renders live as
 * entities change. The rendered string is parsed into a Markdown AST
 * ([parseMarkdown]) and drawn by [MarkdownView] (tables, lists, code, alerts,
 * ha-icon / ha-alert / ha-qr-code extensions).
 *
 * A static (non-templated) body skips the subscription entirely and renders its
 * literal content. The last rendered result is cached so a scroll-off / scroll-on
 * (the composable detaches and reattaches) shows the cached output rather than
 * flashing the raw source while the new subscription warms up.
 */
@Composable
fun MarkdownCard(
    card: LovelaceCard.Markdown,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = LocalHaRepository.current
    val userName by (repo?.currentUserName ?: remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) })
        .collectAsState()

    // Cache key per card identity; survives detach/reattach via rememberSaveable
    // so the rendered text persists across a scroll the way HA's CacheManager does.
    val cacheKey = remember(card.content, card.entityIds, userName) {
        MarkdownTemplate.cacheKey(card.content, card.entityIds, userName)
    }

    // Whether the content is a Jinja template at all. A static body needs no
    // subscription and renders immediately as its own literal text.
    val templated = remember(card.content) { MarkdownTemplate.looksTemplated(card.content) }

    var rendered by rememberSaveable(cacheKey) {
        mutableStateOf(if (templated) null else card.content)
    }
    var failure by remember(cacheKey) { mutableStateOf<MarkdownTemplate.Result.Failed?>(null) }

    if (templated && repo != null) {
        val scope = rememberCoroutineScope()
        DisposableEffect(cacheKey, repo) {
            var sub: HaRepository.TemplateSubscription? = null
            val job = scope.launch {
                val variables = MarkdownTemplate.buildVariables(card.raw, userName)
                repo.subscribeTemplateDetailed(
                    template = card.content,
                    variables = variables,
                    entityIds = card.entityIds,
                    strict = true,
                    reportErrors = true,
                    onRender = { event ->
                        when (event) {
                            is HaRepository.TemplateRender.Ok -> {
                                rendered = event.result
                                failure = null
                            }
                            is HaRepository.TemplateRender.Error -> {
                                val level = if (event.level.equals("WARNING", true)) {
                                    MarkdownTemplate.ErrorLevel.WARNING
                                } else {
                                    MarkdownTemplate.ErrorLevel.ERROR
                                }
                                failure = MarkdownTemplate.selectError(
                                    failure,
                                    MarkdownTemplate.Result.Failed(event.message, level),
                                )
                            }
                        }
                    },
                ).onSuccess { sub = it }
                    .onFailure {
                        // Subscribe failed (offline / unsupported): fall back to the
                        // literal content like HA's frontend does on a connect error.
                        if (rendered == null) rendered = card.content
                    }
            }
            onDispose {
                job.cancel()
                val handle = sub
                if (handle != null) {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        runCatching { handle.cancel() }
                    }
                }
            }
        }
    }

    // show_empty: false hides the whole card once an empty result lands.
    if (MarkdownTemplate.shouldHide(rendered, card.showEmpty)) return

    val nodes = remember(rendered) { rendered?.let { parseMarkdown(it) } ?: emptyList() }
    val actions = CardActions(
        tap = card.tapAction,
        hold = card.holdAction,
        doubleTap = card.doubleTapAction,
    )

    val container = if (card.textOnly) {
        // Chromeless: no surface, no border, title suppressed.
        modifier
            .fillMaxWidth()
            .r1CardActions(actions = actions, onAction = onAction)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    } else {
        modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .r1CardActions(actions = actions, onAction = onAction)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    }

    Column(modifier = container) {
        failure?.let { err ->
            Text(text = err.message, style = R1.labelMicro, color = R1.StatusRed)
            Spacer(Modifier.height(6.dp))
        }
        if (!card.textOnly && !card.title.isNullOrBlank()) {
            Text(text = card.title, style = R1.sectionHeader, color = R1.InkSoft)
            Spacer(Modifier.height(8.dp))
        }
        MarkdownView(
            nodes = nodes,
            onOpenLink = { url -> onAction(LovelaceAction.Url(url)) },
        )
    }
}
