package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import com.github.itskenny0.r1ha.ui.icons.R1IconSet

/**
 * Renderer for HA's `empty-state` card: a centred message tile shown when a
 * new HA instance has no devices in a domain. Renders the optional [icon],
 * [title], and [content] text. When [LovelaceCard.EmptyState.contentOnly] is
 * true, the card chrome (surface background, border) is omitted and only the
 * message body is shown, matching HA's `content_only` flag which renders the
 * card without a ha-card shell.
 */
@Composable
fun EmptyStateCard(
    card: LovelaceCard.EmptyState,
    modifier: Modifier = Modifier,
) {
    val icon = R1Icons.forMdi(card.icon) ?: R1IconSet.Generic
    val title = card.title?.takeUnless { it.isBlank() }
    val content = card.content?.takeUnless { it.isBlank() }

    val innerContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = R1.InkSoft,
                modifier = Modifier.size(40.dp),
            )
            if (title != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = title,
                    style = R1.titleCard,
                    color = R1.Ink,
                    textAlign = TextAlign.Center,
                )
            }
            if (content != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = content,
                    style = R1.body,
                    color = R1.InkMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (card.contentOnly) {
        Column(modifier = modifier.fillMaxWidth()) {
            innerContent()
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(R1.ShapeM)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeM),
        ) {
            innerContent()
        }
    }
}
