package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.icons.R1Icons

/**
 * Compose renderer for the pure [MarkdownNode] tree produced by [parseMarkdown].
 * Deliberately thin: all of the parsing decisions live in MarkdownAst.kt (and
 * are unit-tested there); this file only maps each node onto a widget. Images
 * route through [HuiImage] (so HA media-source / auth URLs resolve), icons
 * through [R1Icons], and links fire [onOpenLink] so the host can launch the URL.
 */
@Composable
fun MarkdownView(
    nodes: List<MarkdownNode>,
    modifier: Modifier = Modifier,
    onOpenLink: (String) -> Unit = {},
) {
    Column(modifier = modifier) {
        nodes.forEachIndexed { index, node ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            MarkdownBlock(node, onOpenLink)
        }
    }
}

@Composable
private fun MarkdownBlock(node: MarkdownNode, onOpenLink: (String) -> Unit) {
    when (node) {
        is MarkdownNode.Heading -> {
            val (size, weight) = when (node.level) {
                1 -> 20.sp to FontWeight.Bold
                2 -> 17.sp to FontWeight.Bold
                3 -> 15.sp to FontWeight.SemiBold
                else -> 14.sp to FontWeight.SemiBold
            }
            InlineText(node.content, R1.body.copy(fontSize = size, fontWeight = weight), R1.Ink, onOpenLink)
        }

        is MarkdownNode.Paragraph ->
            InlineRowWithIcons(node.content, R1.body, R1.Ink, onOpenLink)

        is MarkdownNode.ListBlock -> Column(Modifier.fillMaxWidth()) {
            node.items.forEachIndexed { i, item ->
                if (i > 0) Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Top) {
                    val marker = when {
                        item.task != null -> if (item.task) "[x] " else "[ ] "
                        node.ordered -> "${item.ordinal ?: (i + 1)}. "
                        else -> "· "
                    }
                    Text(marker, style = R1.body, color = R1.InkSoft)
                    InlineText(item.content, R1.body, R1.Ink, onOpenLink)
                }
            }
        }

        is MarkdownNode.BlockQuote -> Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .background(R1.Hairline),
            )
            Spacer(Modifier.width(8.dp))
            InlineText(node.content, R1.body, R1.InkSoft, onOpenLink)
        }

        is MarkdownNode.Alert -> AlertCallout(node, onOpenLink)

        is MarkdownNode.CodeBlock -> Box(
            Modifier
                .fillMaxWidth()
                .clip(R1.ShapeS)
                .background(R1.Hairline)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(node.code, style = R1.body.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = R1.Ink)
        }

        is MarkdownNode.Table -> MarkdownTable(node)

        MarkdownNode.HorizontalRule -> Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(R1.Hairline),
        )

        is MarkdownNode.QrCode -> Box(
            Modifier
                .fillMaxWidth()
                .clip(R1.ShapeS)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .padding(8.dp),
        ) {
            // No native QR encoder is bundled (would pull a dependency); render a
            // labelled placeholder carrying the data so the card still conveys it.
            Text("QR: ${node.data}", style = R1.labelMicro, color = R1.InkSoft)
        }
    }
}

/** Map an alert type to its accent colour. */
private fun alertColor(type: MarkdownAlertType): Color = when (type) {
    MarkdownAlertType.INFO -> R1.AccentCool
    MarkdownAlertType.SUCCESS -> R1.AccentGreen
    MarkdownAlertType.WARNING -> R1.StatusAmber
    MarkdownAlertType.ERROR -> R1.StatusRed
}

@Composable
private fun AlertCallout(node: MarkdownNode.Alert, onOpenLink: (String) -> Unit) {
    val color = alertColor(node.type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.5f), R1.ShapeS)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(3.dp).height(18.dp).background(color))
        Spacer(Modifier.width(8.dp))
        InlineText(node.content, R1.body, R1.Ink, onOpenLink)
    }
}

@Composable
private fun MarkdownTable(node: MarkdownNode.Table) {
    Column(Modifier.fillMaxWidth().border(1.dp, R1.Hairline, R1.ShapeS)) {
        TableRow(node.header, header = true)
        node.rows.forEach { row -> TableRow(row, header = false) }
    }
}

@Composable
private fun TableRow(cells: List<List<MarkdownInline>>, header: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEach { cell ->
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                InlineText(
                    cell,
                    if (header) R1.bodyEmph else R1.body,
                    if (header) R1.Ink else R1.InkSoft,
                    {},
                )
            }
        }
    }
}

/**
 * Render an inline run. Icons are flattened into the annotated string as their
 * slug text would otherwise need inline content; for runs that contain an icon
 * we fall back to a Row so the glyph renders. The common (no-icon) path stays a
 * single Text for crisp wrapping.
 */
@Composable
private fun InlineText(
    inlines: List<MarkdownInline>,
    style: TextStyle,
    color: Color,
    onOpenLink: (String) -> Unit,
) {
    if (inlines.any { it is MarkdownInline.Icon || it is MarkdownInline.Image }) {
        InlineRowWithIcons(inlines, style, color, onOpenLink)
        return
    }
    val linkUrls = remember(inlines) { inlines.filterIsInstance<MarkdownInline.Link>().map { it.url } }
    val annotated = remember(inlines, color) { buildInlineString(inlines, color) }
    // Whole-run tap opens the (single) link. The dashboards layer has no
    // per-span hit testing today; a one-link run is the overwhelming case, so a
    // run carrying exactly one link gets a click target that launches its URL.
    val clickable = if (linkUrls.size == 1) {
        Modifier.r1Pressable(onClick = { onOpenLink(linkUrls.first()) })
    } else {
        Modifier
    }
    Text(annotated, style = style, modifier = clickable)
}

/** A Row that can interleave text with inline icons / images. */
@Composable
private fun InlineRowWithIcons(
    inlines: List<MarkdownInline>,
    style: TextStyle,
    color: Color,
    onOpenLink: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // Render images on their own row; everything else folds into one text.
        val images = inlines.filterIsInstance<MarkdownInline.Image>()
        val nonImage = inlines.filterNot { it is MarkdownInline.Image }
        Row(verticalAlignment = Alignment.CenterVertically) {
            nonImage.forEach { inline ->
                when (inline) {
                    is MarkdownInline.Icon -> {
                        val vector = R1Icons.forMdi(inline.slug)
                        if (vector != null) {
                            Icon(vector, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                        } else {
                            Text(inline.slug, style = style, color = R1.InkMuted)
                        }
                    }
                    else -> Text(buildInlineString(listOf(inline), color), style = style)
                }
            }
        }
        images.forEach { img ->
            Spacer(Modifier.height(4.dp))
            HuiImage(
                imageUrl = img.src,
                contentDescription = img.alt.ifBlank { null },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Build a styled [AnnotatedString] from inline nodes (no icons/images). */
private fun buildInlineString(inlines: List<MarkdownInline>, baseColor: Color): AnnotatedString =
    buildAnnotatedString {
        inlines.forEach { inline ->
            when (inline) {
                is MarkdownInline.Text -> append(inline.text)
                is MarkdownInline.Styled -> {
                    val span = SpanStyle(
                        fontWeight = if (inline.bold) FontWeight.Bold else null,
                        fontStyle = if (inline.italic) FontStyle.Italic else null,
                        textDecoration = if (inline.strike) TextDecoration.LineThrough else null,
                        fontFamily = if (inline.code) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                    )
                    withStyle(span) { append(inline.text) }
                }
                is MarkdownInline.Link ->
                    withStyle(SpanStyle(color = R1.AccentWarm, textDecoration = TextDecoration.Underline)) {
                        append(inline.label)
                    }
                is MarkdownInline.Image -> append(inline.alt)
                is MarkdownInline.Icon -> Unit
                MarkdownInline.LineBreak -> append('\n')
            }
        }
    }
