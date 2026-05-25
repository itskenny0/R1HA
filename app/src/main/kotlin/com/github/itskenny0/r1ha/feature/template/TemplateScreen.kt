package com.github.itskenny0.r1ha.feature.template

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.util.Toaster
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Templates evaluator — type a Jinja2 template, tap RENDER, see HA's
 * output (or syntax error). Mirrors HA's frontend template editor in
 * function while staying inside the R1 idiom.
 *
 * Output panel renders below the editor; on syntax error HA's
 * traceback is shown verbatim so the user can iterate without
 * leaving the screen. The default template (`{{ now().isoformat() }}`)
 * is a one-keystroke "is this connected?" smoke test.
 */
@Composable
fun TemplateScreen(
    haRepository: HaRepository,
    settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
    wheelInput: com.github.itskenny0.r1ha.core.input.WheelInput,
    onBack: () -> Unit,
) {
    val vm: TemplateViewModel = viewModel(
        factory = TemplateViewModel.factory(haRepository, settings),
    )
    val ui by vm.ui.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(title = "TEMPLATES", onBack = onBack)
        AdaptiveContent(modifier = Modifier.weight(1f)) {
        // Wheel scroll for the form area — long Jinja templates +
        // RECENT history can exceed one screen.
        val scrollState = rememberScrollState()
        com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState(
            wheelInput = wheelInput,
            scrollState = scrollState,
            settings = settings,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(scrollState),
        ) {
            // Example chips — one-tap insertion of common templates so the
            // user can iterate without having to remember the Jinja2 syntax
            // for the bits HA cares about (states.* tree, state_attr(), etc.).
            ExampleChips(onPick = { vm.setTemplate(it); vm.render() })
            Spacer(Modifier.padding(top = 10.dp))
            Text(text = "TEMPLATE (JINJA2)", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.padding(top = 4.dp))
            // Multi-line monospace editor. heightIn keeps a sensible minimum
            // even when the field is empty so the tap target is generous.
            R1TextField(
                value = ui.template,
                onValueChange = { vm.setTemplate(it) },
                placeholder = "{{ states.sun.sun.attributes.elevation }}",
                monospace = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
            )
            Spacer(Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                R1Button(
                    text = if (ui.inFlight) "RENDERING…" else "RENDER",
                    onClick = { vm.render() },
                    enabled = ui.template.isNotBlank() && !ui.inFlight && !ui.live,
                )
                Spacer(Modifier.width(8.dp))
                // LIVE toggle — subscribes to HA's render_template WS command,
                // streaming re-renders on every relevant state change. The
                // manual RENDER button is disabled while LIVE is on (the
                // subscription owns the rendered value).
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(if (ui.live) R1.AccentCool.copy(alpha = 0.18f) else R1.SurfaceMuted)
                        .border(
                            1.dp,
                            if (ui.live) R1.AccentCool.copy(alpha = 0.6f) else R1.Hairline,
                            R1.ShapeS,
                        )
                        .r1Pressable(onClick = { vm.setLive(!ui.live) })
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = if (ui.live) "LIVE · ON" else "LIVE",
                        style = R1.labelMicro,
                        color = if (ui.live) R1.AccentCool else R1.AccentWarm,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (ui.live) "subscribed to render_template" else "POSTs /api/template",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            Spacer(Modifier.padding(top = 12.dp))
            // Output panel — switches between OK / ERROR styling. Empty
            // string while we're waiting for the first render so the
            // panel doesn't show "OK" with no body.
            when {
                ui.error != null -> ResultPanel(
                    heading = "ERROR",
                    body = ui.error!!,
                    accent = R1.StatusRed,
                )
                ui.rendered.isNotEmpty() -> ResultPanel(
                    heading = "RENDERED",
                    body = ui.rendered,
                    accent = R1.AccentWarm,
                )
                else -> Text(
                    text = "Hit RENDER to evaluate against live HA state.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            // Recent templates — newest first; tap to recall + re-render.
            if (ui.recent.isNotEmpty()) {
                Spacer(Modifier.padding(top = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "RECENT", style = R1.labelMicro, color = R1.InkSoft)
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .r1Pressable(onClick = { vm.clearRecent() })
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(text = "CLEAR", style = R1.labelMicro, color = R1.InkSoft)
                    }
                }
                Spacer(Modifier.padding(top = 4.dp))
                for (t in ui.recent) {
                    RecentTemplateRow(t, onPick = { vm.setTemplate(t); vm.render() })
                    Spacer(Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.padding(top = 24.dp))
        }
        } // AdaptiveContent
    }
}

@Composable
private fun RecentTemplateRow(template: String, onPick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .r1Pressable(onClick = onPick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        // Two-line preview is enough — most templates are short, and the
        // user can tap to reload the full text into the editor.
        Text(
            text = template,
            style = R1.body,
            color = R1.Ink,
            maxLines = 2,
        )
    }
}

/** A horizontally-scrollable strip of "try this" templates. Picking a
 *  chip both overwrites the editor AND fires a render so the user
 *  sees the live output on a single tap. */
@Composable
private fun ExampleChips(onPick: (String) -> Unit) {
    val examples = listOf(
        "Now" to "{{ now().isoformat() }}",
        "Sun" to "{{ state_attr('sun.sun','elevation') }}°",
        "On lights" to "{{ states.light | selectattr('state','eq','on') | list | count }}",
        "States count" to "{{ states | count }}",
        "Unavailable" to "{{ states | selectattr('state','in',['unavailable','unknown']) | map(attribute='entity_id') | list }}",
        "Areas" to "{{ areas() }}",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "TRY", style = R1.labelMicro, color = R1.InkMuted)
        Spacer(Modifier.width(4.dp))
        for ((label, template) in examples) {
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = { onPick(template) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(text = label, style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
}

@Composable
private fun ResultPanel(heading: String, body: String, accent: androidx.compose.ui.graphics.Color) {
    val clipboard = LocalClipboardManager.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = heading, style = R1.labelMicro, color = accent)
            Spacer(Modifier.width(8.dp))
            // Tap-to-copy chip — convenient for piping a rendered value
            // into HA's automation YAML or a Discord/issue post.
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = {
                        clipboard.setText(AnnotatedString(body))
                        Toaster.show("Copied")
                    })
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(text = "COPY", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
        Spacer(Modifier.padding(top = 4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(text = body, style = R1.body, color = R1.Ink)
        }
    }
}
