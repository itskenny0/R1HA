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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Renderer for HA's `markdown` card. We render the raw [LovelaceCard.Markdown.content]
 * string via a minimal Markdown-to-AnnotatedString converter (headings,
 * bold, italic, inline code, lists, paragraphs). Jinja templating is
 * deliberately NOT executed. the dashboards layer doesn't have a
 * template subscription wired for cards yet; the renderer surfaces the
 * literal `{{ ... }}` text instead.
 *
 * The reason for rolling a tiny renderer (rather than dropping in a
 * dependency) is The Unlicense constraint plus the small surface: the
 * dashboards layer doesn't need full CommonMark. bold + italic +
 * headings + lists cover ~95% of the markdown HA users actually paste
 * into a card.
 */
@Composable
fun MarkdownCard(card: LovelaceCard.Markdown, modifier: Modifier = Modifier) {
    val rendered = remember(card.content) { renderMarkdown(card.content) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (!card.title.isNullOrBlank()) {
            Text(
                text = card.title,
                style = R1.sectionHeader,
                color = R1.InkSoft,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = rendered,
            style = R1.body,
            color = R1.Ink,
        )
    }
}

/**
 * Very-small-surface Markdown → AnnotatedString converter. Handles:
 *  - `#`, `##`, `###` headings (rendered in titleCard style + bold)
 *  - `**bold**`, `*italic*`, `` `inline code` ``
 *  - `- ` / `* ` bulleted lists (rendered with a leading bullet)
 *  - blank-line-separated paragraphs
 *
 * Anything outside that subset (links, images, tables, blockquotes,
 * code fences) renders as plain text. That's a deliberate scope cap;
 * dashboards rarely use those constructs and the renderer stays
 * predictable + bounded.
 */
internal fun renderMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    val lines = source.split('\n')
    lines.forEachIndexed { idx, raw ->
        if (idx > 0) append('\n')
        val trimmed = raw.trimEnd()
        when {
            trimmed.startsWith("### ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    appendInline(trimmed.removePrefix("### "))
                }
            }
            trimmed.startsWith("## ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = androidx.compose.ui.unit.TextUnit.Unspecified)) {
                    appendInline(trimmed.removePrefix("## "))
                }
            }
            trimmed.startsWith("# ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInline(trimmed.removePrefix("# "))
                }
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                append("· ")
                appendInline(trimmed.drop(2))
            }
            trimmed.isBlank() -> Unit // blank line
            else -> appendInline(trimmed)
        }
    }
}

/**
 * Inline-style scanner: emits the input string with bold / italic /
 * inline-code spans applied. Single-pass, depth-1 (no nested emphasis
 * markup); markup that doesn't pair off gets emitted as plain text.
 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(text: String) {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(R1.monoSpan) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}
