package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.LocalHaBearerToken
import com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl
import com.github.itskenny0.r1ha.core.theme.R1
import java.time.Duration
import java.time.Instant

/**
 * Now-playing block shared by every theme's media_player card. Album art on the
 * left (when [picture] is non-null), title / artist / album text in the middle,
 * a live-ticking progress bar at the bottom.
 *
 * Progress bar uses [positionUpdatedAt] as the anchor and interpolates forward
 * once a second when [isPlaying]; freezes at the anchor when paused. The 1 Hz
 * loop only runs while the composable is in composition and there's actually a
 * position to advance, so idle cards in the deck cost nothing.
 *
 * Compact mode is the only mode for now (no separate sizes). Themes can wrap it
 * for additional treatment but the inner layout stays consistent so the user
 * always knows where to find the title / artist / album / progress.
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
) {
    val serverUrl = LocalHaServerUrl.current
    // Authenticate the album-art fetch — HA's entity_picture URLs come in two flavours
    // depending on the integration, and a previously-hardcoded `null` here left covers
    // blank for the half that needs a Bearer header (plain `/api/...` paths, anything
    // from an integration that doesn't bake a `?token=...` into the URL itself). The
    // header is harmless when the URL already carries a token query parameter; HA
    // ignores it in that case.
    val bearerToken = LocalHaBearerToken.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!picture.isNullOrBlank()) {
                AsyncBitmap(
                    url = picture,
                    serverUrl = serverUrl,
                    bearerToken = bearerToken,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(R1.ShapeS),
                    contentDescription = "Album art",
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
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
                    Text(
                        text = artist,
                        style = R1.body,
                        color = R1.InkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!album.isNullOrBlank()) {
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
