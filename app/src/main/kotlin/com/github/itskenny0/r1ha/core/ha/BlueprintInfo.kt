package com.github.itskenny0.r1ha.core.ha

import androidx.compose.runtime.Stable

/**
 * One blueprint as exposed by HA's `blueprint/list/<domain>` reply, plus the
 * import path used to identify it in `blueprint/save`. The native browser
 * surfaces both automation + script blueprints; [domain] distinguishes them
 * because the WS save path is domain-specific (HA writes the YAML under
 * `<config>/blueprints/<domain>/<path>`).
 *
 * Imports go through `blueprint/import {url}`. HA fetches + parses the YAML
 * (GitHub permalinks, gists, raw URLs) and returns the same metadata shape as
 * the list endpoint plus a `suggested_filename` we use as the install path.
 * Saving via `blueprint/save` is a separate confirmation step so the user
 * gets a preview sheet before touching their config.
 */
@Stable
data class BlueprintInfo(
    /** Domain bucket: "automation" or "script". Drives which save path HA
     *  writes to and which list reply this came from. */
    val domain: String,
    /** Stable filesystem-relative path under HA's blueprints/<domain> dir,
     *  e.g. "user/notify_motion.yaml". For freshly-imported blueprints
     *  this is HA's `suggested_filename`. Empty when the import preview
     *  hasn't resolved a target yet. */
    val path: String,
    /** Display name from the blueprint's metadata. */
    val name: String,
    /** Long-form description from metadata; renderable as multi-line body
     *  copy. Empty when not provided. */
    val description: String,
    /** Original `source_url` declared inside the blueprint YAML (or, for
     *  fresh imports, the URL the user pasted). Used to dedupe and to
     *  surface "where did this come from?" in the row. */
    val sourceUrl: String?,
    /** Count of declared `input:` slots the user would have to fill in
     *  when wiring an automation/script against this blueprint. Surfaced
     *  as a small chip so the user can spot trivial vs. setup-heavy
     *  blueprints at a glance. */
    val inputCount: Int,
    /** Raw YAML body returned by `blueprint/import`. Carried only for
     *  freshly-imported blueprints so `saveBlueprint` can hand it back to
     *  HA verbatim. Null for blueprints already on disk (HA doesn't ship
     *  the YAML on list responses). */
    val rawYaml: String? = null,
    /** Non-null when HA's import preview reported validation errors;
     *  the install path is disabled until the user resolves them in the
     *  source blueprint. */
    val validationErrors: String? = null,
)
