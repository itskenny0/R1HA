package com.github.itskenny0.r1ha.ui.components

import com.github.itskenny0.r1ha.core.ha.Domain
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The entity action card's fire-feedback verb tables ([actionTapHint] /
 * [actionSentLabel]) are pure, so the tense-matched pairing (affordance verb ->
 * past-tense confirmation) is asserted directly without a UI harness. The drawn
 * signal pulse it shares with the Lovelace button card is already covered by
 * pulseRing's own tests; here we only pin the domain-to-verb mapping.
 */
class ActionFireLogicTest {

    @Test fun `tap hints read as affordance verbs per action domain`() {
        assertEquals("TAP TO ACTIVATE", actionTapHint(Domain.SCENE))
        assertEquals("TAP TO RUN", actionTapHint(Domain.SCRIPT))
        assertEquals("TAP TO PRESS", actionTapHint(Domain.BUTTON))
        assertEquals("TAP TO PRESS", actionTapHint(Domain.INPUT_BUTTON))
    }

    @Test fun `sent labels are the past tense of the matching tap hint`() {
        assertEquals("ACTIVATED", actionSentLabel(Domain.SCENE))
        assertEquals("RAN", actionSentLabel(Domain.SCRIPT))
        assertEquals("PRESSED", actionSentLabel(Domain.BUTTON))
        assertEquals("PRESSED", actionSentLabel(Domain.INPUT_BUTTON))
    }

    @Test fun `a non-action domain falls back to a generic fire verb pair`() {
        // The action-card path only ever sees action domains, but the defensive
        // fallback keeps the face readable rather than crashing on a stray domain.
        assertEquals("TAP TO FIRE", actionTapHint(Domain.LIGHT))
        assertEquals("FIRED", actionSentLabel(Domain.LIGHT))
    }

    @Test fun `every verb is all-caps with no em-dash`() {
        // The R1 idiom: all-caps micro labels, never an em-dash.
        for (d in listOf(Domain.SCENE, Domain.SCRIPT, Domain.BUTTON, Domain.INPUT_BUTTON)) {
            for (v in listOf(actionTapHint(d), actionSentLabel(d))) {
                assertEquals(v, v.uppercase())
                assertEquals(false, v.contains('—'))
            }
        }
    }
}
