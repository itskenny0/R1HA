package com.github.itskenny0.r1ha.ui.components

import com.github.itskenny0.r1ha.core.ha.Domain

/**
 * Pure fire-feedback verb selection for the entity ACTION card (scene / script /
 * button / input_button tiles). This is the entity-domain twin of the Lovelace
 * button card's [com.github.itskenny0.r1ha.feature.dashboards.cards.buttonTapHint]
 * / [com.github.itskenny0.r1ha.feature.dashboards.cards.buttonSentLabel]: the
 * Lovelace card resolves its verbs from a `LovelaceAction` (a service string),
 * but an entity card only has a [Domain], so the verb table keys off that
 * instead. The two read as one design language (affordance verb that flips to a
 * past-tense confirmation on fire); they share the actual drawn signal pulse via
 * [com.github.itskenny0.r1ha.feature.dashboards.cards.pulseRing], so only this
 * thin domain-to-verb mapping is re-stated here rather than the geometry.
 *
 * Tense-matched pairs so the footer reads "TAP TO ACTIVATE" then crossfades to
 * "ACTIVATED": a scene is ACTIVATEd, a script RUN, a button PRESSed. All-caps
 * micro labels, no em-dashes, R1's industrial-kiosk idiom.
 *
 * Pure (no Compose) so the verb tables are unit-tested without a UI harness.
 */

/** The all-caps affordance verb printed under the actuator at rest. */
fun actionTapHint(domain: Domain): String = when (domain) {
    Domain.SCENE -> "TAP TO ACTIVATE"
    Domain.SCRIPT -> "TAP TO RUN"
    Domain.BUTTON, Domain.INPUT_BUTTON -> "TAP TO PRESS"
    // Defensive: the action-card path only ever sees action domains, but a sane
    // generic verb keeps the face readable if a new domain slips through.
    else -> "TAP TO FIRE"
}

/**
 * The past-tense confirmation verb that crossfades in over [actionTapHint] for a
 * beat right after the trigger is dispatched. Tense-matched to the affordance
 * verb so "TAP TO RUN" flips to "RAN".
 */
fun actionSentLabel(domain: Domain): String = when (domain) {
    Domain.SCENE -> "ACTIVATED"
    Domain.SCRIPT -> "RAN"
    Domain.BUTTON, Domain.INPUT_BUTTON -> "PRESSED"
    else -> "FIRED"
}
