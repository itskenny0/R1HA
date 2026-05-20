package com.github.itskenny0.r1ha.wear.feature.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.github.itskenny0.r1ha.core.ha.HaRepository

/**
 * Wear OS Remote Control screen — placeholder for the hass-unified-remote
 * integration.
 *
 * TODO: Implement touchpad / volume / media controls using
 * `unified_remote.call` service calls via [haRepository.callRawService].
 *
 * Planned layout:
 *  - Centre zone = touch-drag trackpad (sends mouse move + click)
 *  - Bezel / crown = scroll wheel
 *  - Volume buttons = physical side keys (intercepted via KeyEvent)
 *  - Bottom strip = prev / play-pause / next media transport chips
 */
@Composable
fun WearRemoteScreen(
    haRepository: HaRepository,
    onBack: () -> Unit,
) {
    Scaffold(timeText = { TimeText() }) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(
                    text = "🖥",
                    style = MaterialTheme.typography.display3,
                )
                Text(
                    text = "Remote Control",
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Coming soon — touchpad, volume & media controls via Unified Remote",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                )
            }
        }
    }
}
