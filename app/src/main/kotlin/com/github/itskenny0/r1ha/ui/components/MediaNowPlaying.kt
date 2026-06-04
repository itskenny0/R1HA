package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.LocalHaBearerToken
import com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl
import com.github.itskenny0.r1ha.core.theme.R1
import java.time.Duration
import java.time.Instant

/**
 * Responsive geometry for the now-playing header, derived purely from a
 * [WindowTier]. Kept as a small value so the layout decision can be unit-tested
 * without a Compose runtime or a measured width.
 *
 * The header is always an art-left row (album art, then a tight title / artist /
 * album column). What scales per tier is how much space the art and the gutter
 * claim and the vertical rhythm between the text lines:
 *
 * @param artwork edge length of the square album art. Smaller on the narrow
 *   tiers so the header stays compact and never eats vertical space.
 * @param gap horizontal space between the art and the metadata column.
 * @param metadataSpacing vertical rhythm between the title / artist / album
 *   lines; a touch looser on wide tiers that have the room.
 */
data class MediaHeaderLayout(
    val artwork: Dp,
    val gap: Dp,
    val metadataSpacing: Dp,
)

/**
 * Maps a [WindowTier] to the now-playing header geometry. Pure and side-effect
 * free so it can be exercised directly in a unit test.
 *
 * - [WindowTier.R1]: the Rabbit R1's ~240-340 dp panel. Tiny art and a minimal
 *   gutter so the block leaves room for the progress bar.
 * - [WindowTier.COMPACT]: phones in portrait. Slightly larger art.
 * - [WindowTier.MEDIUM] and up: tablets / landscape. Larger art and a wider
 *   gutter spend the extra width without stretching the text lines.
 */
fun mediaHeaderLayoutFor(tier: WindowTier): MediaHeaderLayout = when (tier) {
    WindowTier.R1 -> MediaHeaderLayout(
        artwork = 48.dp,
        gap = 10.dp,
        metadataSpacing = 0.dp,
    )
    WindowTier.COMPACT -> MediaHeaderLayout(
        artwork = 56.dp,
        gap = 12.dp,
        metadataSpacing = 1.dp,
    )
    WindowTier.MEDIUM -> MediaHeaderLayout(
        artwork = 72.dp,
        gap = 16.dp,
        metadataSpacing = 2.dp,
    )
    WindowTier.EXPANDED,
    WindowTier.EXTRA_LARGE -> MediaHeaderLayout(
        artwork = 88.dp,
        gap = 20.dp,
        metadataSpacing = 3.dp,
    )
}

/**
 * Now-playing block shared by every theme's media_player card. Album art on the
 * left (when [picture] is non-null), a tight title / artist / album hierarchy in
 * the middle, a live-ticking progress bar at the bottom.
 *
 * Responsive: the art size, gutter, and line spacing are driven by the active
 * [WindowTier] (read from [LocalWindowTier], overridable via [tier] for previews
 * and tests) through [mediaHeaderLayoutFor]. Narrow panels (R1, phone portrait)
 * get a compact row with small art and minimal vertical padding; wider windows
 * reflow into a roomier art-left row. Every metadata line is clamped to a single
 * line and ellipsized, so a long title / artist / album can never overflow or
 * push the surrounding layout.
 *
 * Progress bar uses [positionUpdatedAt] as the anchor and interpolates forward
 * once a second when [isPlaying]; freezes at the anchor when paused. The 1 Hz
 * loop only runs while the composable is in composition and there's actually a
 * position to advance, so idle cards in the deck cost nothing.
 *
 * Themes can wrap this for additional treatment but the inner layout stays
 * consistent so the user always knows where to find title / artist / album /
 * progress regardless of skin.
 */
@Composable
fun MediaNowPlayingCompact(
    title: String?,
    artist: String?,
    album: String?,
    picture: String?,
    durationSec: Int?,
    positionSec: Int?,
    positionUpdatedAt: Instant?,
    isPlaying: Boolean,
    accent: Color,
    source: String? = null,
    tier: WindowTier = LocalWindowTier.current.tier,
) {
    val layout = mediaHeaderLayoutFor(tier)
    val serverUrl = LocalHaServerUrl.current
    // Authenticate the album-art fetch — HA's entity_picture URLs come in two flavours
    // depending on the integration, and a previously-hardcoded `null` here left covers
    // blank for the half that needs a Bearer header (plain `/api/...` paths, anything
    // from an integration that doesn't bake a `?token=...` into the URL itself). The
    // header is harmless when the URL already carries a token query parameter; HA
    // ignores it in that case.
    val bearerToken = LocalHaBearerToken.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(layout.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!picture.isNullOrBlank()) {
                AsyncBitmap(
                    url = picture,
                    serverUrl = serverUrl,
                    bearerToken = bearerToken,
                    modifier = Modifier
                        .size(layout.artwork)
                        .clip(R1.ShapeS),
                    contentDescription = "Album art",
                )
            }
            MediaMetadata(
                title = title,
                artist = artist,
                album = album,
                source = source,
                accent = accent,
                lineSpacing = layout.metadataSpacing,
                modifier = Modifier.weight(1f),
            )
        }
        if (durationSec != null && durationSec > 0 && positionSec != null) {
            Spacer(Modifier.height(8.dp))
            val live = rememberLivePosition(positionSec, positionUpdatedAt, durationSec, isPlaying)
            val fraction = (live.toFloat() / durationSec.toFloat()).coerceIn(0f, 1f)
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(R1.SurfaceMuted),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(2.dp)
                        .background(accent),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = formatHms(live), style = R1.labelMicro, color = R1.InkMuted)
                Spacer(Modifier.weight(1f))
                Text(text = formatHms(durationSec), style = R1.labelMicro, color = R1.InkMuted)
            }
        }
    }
}

/**
 * The metadata column: an optional source eyebrow ("SPOTIFY", "HDMI 1") on top, then
 * title, artist, and album, each clamped to a single line and ellipsized so long strings
 * never reflow the card. Blank fields are skipped so the block collapses to exactly the
 * lines it has.
 */
@Composable
private fun MediaMetadata(
    title: String?,
    artist: String?,
    album: String?,
    source: String?,
    accent: Color,
    lineSpacing: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (!source.isNullOrBlank()) {
            Text(
                text = source.uppercase(),
                style = R1.labelMicro,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (lineSpacing > 0.dp) Spacer(Modifier.height(lineSpacing))
        }
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = R1.bodyEmph,
                color = R1.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!artist.isNullOrBlank()) {
            if (lineSpacing > 0.dp) Spacer(Modifier.height(lineSpacing))
            Text(
                text = artist,
                style = R1.body,
                color = R1.InkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!album.isNullOrBlank()) {
            if (lineSpacing > 0.dp) Spacer(Modifier.height(lineSpacing))
            Text(
                text = album,
                style = R1.labelMicro,
                color = R1.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun rememberLivePosition(
    positionSec: Int,
    positionUpdatedAt: Instant?,
    durationSec: Int,
    isPlaying: Boolean,
): Int {
    val live = remember { mutableIntStateOf(positionSec) }
    LaunchedEffect(positionSec, positionUpdatedAt, isPlaying, durationSec) {
        if (!isPlaying || positionUpdatedAt == null) {
            live.intValue = positionSec.coerceIn(0, durationSec)
            return@LaunchedEffect
        }
        while (true) {
            val elapsed = Duration.between(positionUpdatedAt, Instant.now())
                .seconds
                .toInt()
                .coerceAtLeast(0)
            live.intValue = (positionSec + elapsed).coerceIn(0, durationSec)
            kotlinx.coroutines.delay(1_000)
        }
    }
    return live.intValue
}

private fun formatHms(totalSec: Int): String {
    if (totalSec < 0) return "0:00"
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
