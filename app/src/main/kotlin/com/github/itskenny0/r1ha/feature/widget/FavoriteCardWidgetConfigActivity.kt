package com.github.itskenny0.r1ha.feature.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.MainActivity
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.ThemeId
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.R1ThemeHost
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1Section
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.flow.first

/**
 * android:configure target for [FavoriteCardWidgetProvider]: the launcher
 * starts this with the fresh widget id, the user picks one favorite (grouped
 * by card-stack page, with their rename overrides applied), and the binding
 * lands in [FavoriteCardWidgetStore] before RESULT_OK hands the widget back
 * to the host.
 *
 * RESULT_CANCELED is set up-front per the appwidget contract: backing out of
 * an unfinished configuration tells the host to discard the placed widget,
 * so no instance ever exists without a binding (the provider still renders a
 * "tap to set up" card defensively if a host misbehaves).
 */
class FavoriteCardWidgetConfigActivity : ComponentActivity() {

    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, resultIntent())
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val graph = (application as App).graph
        setContent {
            // Block on the first settings emission inside produceState rather
            // than collecting the flow: the screen is a one-shot picker, and a
            // null initial frame just shows the themed background for the few
            // ms DataStore needs.
            val settings by produceState<AppSettings?>(initialValue = null) {
                value = graph.settings.settings.first()
            }
            val current = settings
            // Friendly names are cosmetic here, so the one-shot fetch is
            // best-effort: a dead network still leaves a usable picker with
            // prettified object ids.
            val friendlyNames by produceState(initialValue = emptyMap<String, String>(), current) {
                if (current?.server != null) {
                    value = graph.haRepository.listAllEntitiesForSearch().getOrNull()
                        ?.associate { it.id.value to it.friendlyName }
                        .orEmpty()
                }
            }
            // Compute the grouped listing once per (settings, friendly-name)
            // change rather than on every recomposition: it was previously built
            // twice per frame (the empty-check guard and the PickerPane argument).
            val favoritePages = remember(current, friendlyNames) {
                current?.let { buildWidgetFavoritePages(it, friendlyNames) }.orEmpty()
            }
            R1ThemeHost(themeId = current?.theme ?: ThemeId.PRAGMATIC_HYBRID) {
                Box(Modifier.fillMaxSize().background(R1.Bg)) {
                    when {
                        current == null -> Unit
                        current.server == null -> MessagePane(
                            message = "Sign in to Home Assistant in R1HA first, then add this widget again.",
                        )
                        favoritePages.isEmpty() -> MessagePane(
                            message = "No favorites yet. Add favorites to the card stack in R1HA, then add this widget again.",
                        )
                        else -> PickerPane(
                            pages = favoritePages,
                            onPick = ::completeConfiguration,
                        )
                    }
                }
            }
        }
    }

    private fun completeConfiguration(entityId: String) {
        FavoriteCardWidgetStore.bind(this, widgetId, entityId)
        FavoriteCardWidgetProvider.requestUpdate(this, widgetId)
        setResult(RESULT_OK, resultIntent())
        finish()
    }

    private fun resultIntent(): Intent =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    @Composable
    private fun MessagePane(message: String) {
        Column(Modifier.fillMaxSize()) {
            R1TopBar(title = "FAVORITE CARD WIDGET")
            Column(
                modifier = Modifier.fillMaxWidth().padding(R1.space.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = message,
                    style = R1.body,
                    color = R1.InkSoft,
                )
                Spacer(Modifier.height(R1.space.l))
                R1Button(
                    text = "OPEN R1HA",
                    onClick = {
                        startActivity(
                            Intent(this@FavoriteCardWidgetConfigActivity, MainActivity::class.java),
                        )
                        finish()
                    },
                )
            }
        }
    }

    @Composable
    private fun PickerPane(
        pages: List<WidgetFavoritePage>,
        onPick: (String) -> Unit,
    ) {
        Column(Modifier.fillMaxSize()) {
            R1TopBar(title = "PICK A FAVORITE")
            LazyColumn(Modifier.fillMaxSize()) {
                pages.forEachIndexed { index, page ->
                    item(key = "header_${page.pageId.ifEmpty { index.toString() }}") {
                        R1Section(
                            title = page.pageName,
                            count = page.entries.size,
                            topSpace = if (index == 0) R1.space.s else R1.space.xl,
                        ) {}
                    }
                    items(
                        count = page.entries.size,
                        key = { i -> "${page.pageId}_${page.entries[i].entityId}_$i" },
                    ) { i ->
                        FavoriteRow(
                            entry = page.entries[i],
                            // Page accent (the tab-chip colour) tints the row dot so
                            // the groups read like their card-stack tabs.
                            accent = page.accentArgb?.let { Color(it) } ?: R1.AccentWarm,
                            onPick = onPick,
                        )
                    }
                }
                item(key = "bottom_pad") { Spacer(Modifier.height(R1.space.xl)) }
            }
        }
    }

    @Composable
    private fun FavoriteRow(
        entry: WidgetFavoriteEntry,
        accent: Color,
        onPick: (String) -> Unit,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = R1.MinTarget)
                .r1Pressable(
                    onClick = { onPick(entry.entityId) },
                    contentDescription = entry.displayName,
                )
                .padding(horizontal = R1.space.l, vertical = R1.space.xs),
        ) {
            // Small accent dot stands in for the entity glyph; the live glyph
            // needs state we don't fetch here and the dot keeps rows scannable.
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.7f)),
            )
            Spacer(Modifier.width(R1.space.m))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = R1.bodyEmph,
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.entityId,
                    style = R1.numeralS,
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
