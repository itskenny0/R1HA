package com.github.itskenny0.r1ha.feature.scenes

import org.junit.Assert.assertEquals
import org.junit.Test

class ScenesA11yTest {

    @Test
    fun kindWord_isLowercaseHumanLabel() {
        assertEquals("scene", sceneKindWord(ScenesViewModel.Kind.SCENE))
        assertEquals("script", sceneKindWord(ScenesViewModel.Kind.SCRIPT))
    }

    @Test
    fun fireActionLabel_usesActivateForScene() {
        assertEquals(
            "Activate Movie Night scene",
            sceneFireActionLabel("Movie Night", ScenesViewModel.Kind.SCENE),
        )
    }

    @Test
    fun fireActionLabel_usesRunForScript() {
        assertEquals(
            "Run Nightly Backup script",
            sceneFireActionLabel("Nightly Backup", ScenesViewModel.Kind.SCRIPT),
        )
    }

    @Test
    fun inFlightLabel_distinguishesSceneAndScript() {
        assertEquals(
            "Activating Movie Night",
            sceneInFlightLabel("Movie Night", ScenesViewModel.Kind.SCENE),
        )
        assertEquals(
            "Running Nightly Backup",
            sceneInFlightLabel("Nightly Backup", ScenesViewModel.Kind.SCRIPT),
        )
    }

    @Test
    fun rowLabel_idle_withoutLastActivated_isJustTheAction() {
        assertEquals(
            "Activate Movie Night scene",
            sceneRowLabel(
                name = "Movie Night",
                kind = ScenesViewModel.Kind.SCENE,
                firing = false,
                lastActivatedSpoken = null,
            ),
        )
    }

    @Test
    fun rowLabel_idle_appendsLastActivated() {
        assertEquals(
            "Activate Movie Night scene, last activated 5 minutes ago",
            sceneRowLabel(
                name = "Movie Night",
                kind = ScenesViewModel.Kind.SCENE,
                firing = false,
                lastActivatedSpoken = "5 minutes ago",
            ),
        )
    }

    @Test
    fun rowLabel_blankLastActivated_isOmitted() {
        assertEquals(
            "Run Nightly Backup script",
            sceneRowLabel(
                name = "Nightly Backup",
                kind = ScenesViewModel.Kind.SCRIPT,
                firing = false,
                lastActivatedSpoken = "   ",
            ),
        )
    }

    @Test
    fun rowLabel_firing_announcesInProgressAndKeepsLastActivated() {
        assertEquals(
            "Activating Movie Night, last activated 5 minutes ago",
            sceneRowLabel(
                name = "Movie Night",
                kind = ScenesViewModel.Kind.SCENE,
                firing = true,
                lastActivatedSpoken = "5 minutes ago",
            ),
        )
    }

    @Test
    fun rowLabel_firingScript_usesRunningVerb() {
        assertEquals(
            "Running Nightly Backup",
            sceneRowLabel(
                name = "Nightly Backup",
                kind = ScenesViewModel.Kind.SCRIPT,
                firing = true,
                lastActivatedSpoken = null,
            ),
        )
    }
}
