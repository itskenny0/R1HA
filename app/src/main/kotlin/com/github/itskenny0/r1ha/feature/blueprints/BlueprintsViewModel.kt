package com.github.itskenny0.r1ha.feature.blueprints

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.BlueprintInfo
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the native Blueprints browser. Fetches automation + script
 * blueprints in parallel (one WS round-trip each) so cold-start lands the
 * sectioned list inside one slowest-of-two window rather than serially.
 *
 * Import is two-stage: [previewImport] hits HA's `blueprint/import` to
 * validate and surface the metadata in a preview sheet, then [installImport]
 * commits via `blueprint/save`. The preview step is deliberately separate so
 * a typo'd URL or a YAML with validation errors never silently lands in the
 * user's HA config.
 */
class BlueprintsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val automations: List<BlueprintInfo> = emptyList(),
        val scripts: List<BlueprintInfo> = emptyList(),
        /** Per-section collapse state. Defaults to both expanded. */
        val automationsExpanded: Boolean = true,
        val scriptsExpanded: Boolean = true,
        val error: String? = null,
        /** Import flow lifecycle. NONE = no dialog open; URL_PROMPT = the
         *  user is typing a URL; IMPORTING = HA is fetching + validating;
         *  PREVIEW = HA returned metadata, awaiting INSTALL/CANCEL;
         *  INSTALLING = `blueprint/save` is in flight. */
        val importPhase: ImportPhase = ImportPhase.NONE,
        /** Current URL the user is typing into the import dialog. */
        val importUrl: String = "",
        /** Preview payload returned by `blueprint/import` once the user
         *  fires the URL. Null until the preview lands. */
        val previewBlueprint: BlueprintInfo? = null,
        /** Last user-facing import error (URL fetch failed, YAML
         *  validation failed, network blip during save). Cleared when the
         *  user retries or dismisses. */
        val importError: String? = null,
    ) {
        /** Combined count for the top-bar badge. */
        val totalCount: Int get() = automations.size + scripts.size
    }

    enum class ImportPhase { NONE, URL_PROMPT, IMPORTING, PREVIEW, INSTALLING }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun toggleAutomations() {
        _ui.value = _ui.value.copy(automationsExpanded = !_ui.value.automationsExpanded)
    }

    fun toggleScripts() {
        _ui.value = _ui.value.copy(scriptsExpanded = !_ui.value.scriptsExpanded)
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            // Parallel fan-out: each list call is independent and the
            // common HA install ships both blueprint domains. Awaits the
            // slowest of two rather than the sum.
            val autoDef = async { haRepository.listBlueprints("automation") }
            val scriptDef = async { haRepository.listBlueprints("script") }
            val results = awaitAll(autoDef, scriptDef)
            @Suppress("UNCHECKED_CAST")
            val autoRes = results[0] as Result<List<BlueprintInfo>>
            @Suppress("UNCHECKED_CAST")
            val scriptRes = results[1] as Result<List<BlueprintInfo>>

            // Fold the two domain results into the sectioned state. The pure
            // helper owns partial-failure tolerance + stable ordering so the
            // viewmodel only deals with the side-effects (log / toast / emit).
            val grouped = BlueprintGrouping.group(autoRes, scriptRes)
            if (grouped.error != null) {
                R1Log.w("Blueprints", "load failed: ${grouped.error}")
                Toaster.error("Blueprints load failed: ${grouped.error}")
                _ui.value = _ui.value.copy(loading = false, error = grouped.error)
            } else {
                R1Log.i(
                    "Blueprints",
                    "loaded ${grouped.automations.size} automation + " +
                        "${grouped.scripts.size} script blueprint(s)",
                )
                _ui.value = _ui.value.copy(
                    loading = false,
                    automations = grouped.automations,
                    scripts = grouped.scripts,
                    error = null,
                )
            }
        }
    }

    /** Open the URL-prompt dialog; called by the IMPORT FROM URL chip. */
    fun openImportDialog() {
        _ui.value = _ui.value.copy(
            importPhase = ImportPhase.URL_PROMPT,
            importUrl = "",
            previewBlueprint = null,
            importError = null,
        )
    }

    fun setImportUrl(url: String) {
        _ui.value = _ui.value.copy(importUrl = url)
    }

    /** Dismiss any in-progress import flow. Safe to call from a dialog's
     *  back-press / outside-tap. */
    fun cancelImport() {
        _ui.value = _ui.value.copy(
            importPhase = ImportPhase.NONE,
            importUrl = "",
            previewBlueprint = null,
            importError = null,
        )
    }

    /** Fire `blueprint/import` against the typed URL. On success, drop
     *  into the PREVIEW phase so the user gets a sanity check before
     *  anything lands on disk. */
    fun previewImport() {
        val url = _ui.value.importUrl.trim()
        if (url.isBlank()) {
            _ui.value = _ui.value.copy(importError = "Paste a URL first.")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(importPhase = ImportPhase.IMPORTING, importError = null)
            haRepository.importBlueprint(url).fold(
                onSuccess = { bp ->
                    R1Log.i(
                        "Blueprints",
                        "imported preview: ${bp.domain}/${bp.path} (${bp.inputCount} inputs)",
                    )
                    _ui.value = _ui.value.copy(
                        importPhase = ImportPhase.PREVIEW,
                        previewBlueprint = bp,
                        importError = bp.validationErrors,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Blueprints", "import preview failed: ${t.message}")
                    _ui.value = _ui.value.copy(
                        importPhase = ImportPhase.URL_PROMPT,
                        importError = t.message ?: "Import failed.",
                    )
                },
            )
        }
    }

    /** Commit a previously-previewed blueprint to disk via `blueprint/save`
     *  and refresh the list. Toasts on success/failure; rolls back to the
     *  PREVIEW phase if HA refuses the save so the user can retry without
     *  re-typing the URL. */
    fun installImport() {
        val bp = _ui.value.previewBlueprint ?: return
        val yaml = bp.rawYaml
        if (yaml.isNullOrBlank()) {
            _ui.value = _ui.value.copy(
                importError = "HA didn't return the blueprint YAML; upgrade HA Core to install.",
            )
            return
        }
        if (bp.path.isBlank()) {
            _ui.value = _ui.value.copy(importError = "Blueprint has no suggested filename.")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(importPhase = ImportPhase.INSTALLING, importError = null)
            haRepository.saveBlueprint(
                domain = bp.domain,
                path = bp.path,
                yaml = yaml,
                sourceUrl = bp.sourceUrl ?: _ui.value.importUrl.trim(),
            ).fold(
                onSuccess = {
                    Toaster.show("Installed ${bp.name}")
                    _ui.value = _ui.value.copy(
                        importPhase = ImportPhase.NONE,
                        importUrl = "",
                        previewBlueprint = null,
                        importError = null,
                    )
                    refresh()
                },
                onFailure = { t ->
                    R1Log.w("Blueprints", "save ${bp.path} failed: ${t.message}")
                    Toaster.errorExpandable(
                        shortText = "Install failed",
                        fullText = t.message ?: t.toString(),
                    )
                    _ui.value = _ui.value.copy(
                        importPhase = ImportPhase.PREVIEW,
                        importError = t.message ?: "Install failed.",
                    )
                },
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { BlueprintsViewModel(haRepository) }
        }
    }
}
