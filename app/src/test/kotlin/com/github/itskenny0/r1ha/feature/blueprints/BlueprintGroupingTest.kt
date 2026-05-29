package com.github.itskenny0.r1ha.feature.blueprints

import com.github.itskenny0.r1ha.core.ha.BlueprintInfo
import com.github.itskenny0.r1ha.feature.blueprints.BlueprintGrouping.sortedByName
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure Blueprints fold / grouping helpers in
 * BlueprintGrouping.kt. Cover the partial-failure tolerance, stable ordering,
 * input-chip pluralization, and the install-gate predicate.
 */
class BlueprintGroupingTest {

    private fun bp(
        name: String,
        domain: String = "automation",
        path: String = "user/$name.yaml",
        rawYaml: String? = null,
        validationErrors: String? = null,
        inputCount: Int = 0,
    ) = BlueprintInfo(
        domain = domain,
        path = path,
        name = name,
        description = "",
        sourceUrl = null,
        inputCount = inputCount,
        rawYaml = rawYaml,
        validationErrors = validationErrors,
    )

    @Test
    fun `group splits buckets and reports no error when both succeed`() {
        val grouped = BlueprintGrouping.group(
            automationResult = Result.success(listOf(bp("motion"))),
            scriptResult = Result.success(listOf(bp("greet", domain = "script"))),
        )
        assertThat(grouped.automations.map { it.name }).containsExactly("motion")
        assertThat(grouped.scripts.map { it.name }).containsExactly("greet")
        assertThat(grouped.error).isNull()
        assertThat(grouped.totalCount).isEqualTo(2)
    }

    @Test
    fun `group sorts each bucket case-insensitively by name`() {
        val grouped = BlueprintGrouping.group(
            automationResult = Result.success(
                listOf(bp("Zebra"), bp("apple"), bp("Mango")),
            ),
            scriptResult = Result.success(emptyList()),
        )
        assertThat(grouped.automations.map { it.name })
            .containsExactly("apple", "Mango", "Zebra")
            .inOrder()
    }

    @Test
    fun `group breaks name ties by path so order is deterministic`() {
        val grouped = BlueprintGrouping.group(
            automationResult = Result.success(
                listOf(
                    bp("Dup", path = "z/second.yaml"),
                    bp("Dup", path = "a/first.yaml"),
                ),
            ),
            scriptResult = Result.success(emptyList()),
        )
        assertThat(grouped.automations.map { it.path })
            .containsExactly("a/first.yaml", "z/second.yaml")
            .inOrder()
    }

    @Test
    fun `partial failure still renders the bucket that loaded`() {
        val grouped = BlueprintGrouping.group(
            automationResult = Result.success(listOf(bp("motion"))),
            scriptResult = Result.failure(RuntimeException("script bucket refused")),
        )
        assertThat(grouped.automations).hasSize(1)
        assertThat(grouped.scripts).isEmpty()
        assertThat(grouped.error).isNull()
    }

    @Test
    fun `error surfaces only when both buckets fail`() {
        val grouped = BlueprintGrouping.group(
            automationResult = Result.failure(RuntimeException("ws down")),
            scriptResult = Result.failure(RuntimeException("ws down too")),
        )
        assertThat(grouped.automations).isEmpty()
        assertThat(grouped.scripts).isEmpty()
        assertThat(grouped.error).isEqualTo("ws down")
    }

    @Test
    fun `firstErrorMessage falls back to a label for a blank exception message`() {
        val msg = BlueprintGrouping.firstErrorMessage(
            listOf(
                Result.success(Unit),
                Result.failure<Unit>(RuntimeException("")),
            ),
        )
        assertThat(msg).isEqualTo("Unknown error")
    }

    @Test
    fun `firstErrorMessage is null when nothing failed`() {
        val msg = BlueprintGrouping.firstErrorMessage(
            listOf(
                Result.success(Unit),
                Result.success(1),
            ),
        )
        assertThat(msg).isNull()
    }

    @Test
    fun `sortedByName extension orders a standalone list`() {
        val sorted = listOf(bp("beta"), bp("Alpha")).sortedByName()
        assertThat(sorted.map { it.name }).containsExactly("Alpha", "beta").inOrder()
    }

    @Test
    fun `inputChipLabel pluralizes and skips zero`() {
        assertThat(BlueprintGrouping.inputChipLabel(0)).isNull()
        assertThat(BlueprintGrouping.inputChipLabel(-1)).isNull()
        assertThat(BlueprintGrouping.inputChipLabel(1)).isEqualTo("1 INPUT")
        assertThat(BlueprintGrouping.inputChipLabel(3)).isEqualTo("3 INPUTS")
    }

    @Test
    fun `canInstall requires yaml, path, and no validation errors`() {
        // Happy path: HA returned YAML + a suggested filename, no errors.
        assertThat(
            BlueprintGrouping.canInstall(
                bp("ok", rawYaml = "blueprint: {}", path = "user/ok.yaml"),
            ),
        ).isTrue()
    }

    @Test
    fun `canInstall is false without raw yaml`() {
        assertThat(
            BlueprintGrouping.canInstall(bp("noyaml", rawYaml = null, path = "user/x.yaml")),
        ).isFalse()
    }

    @Test
    fun `canInstall is false with a blank path`() {
        assertThat(
            BlueprintGrouping.canInstall(bp("nopath", rawYaml = "blueprint: {}", path = "")),
        ).isFalse()
    }

    @Test
    fun `canInstall is false when validation errors are present`() {
        assertThat(
            BlueprintGrouping.canInstall(
                bp(
                    "bad",
                    rawYaml = "blueprint: {}",
                    path = "user/bad.yaml",
                    validationErrors = "missing input",
                ),
            ),
        ).isFalse()
    }

    @Test
    fun `canInstall is false for a null preview`() {
        assertThat(BlueprintGrouping.canInstall(null)).isFalse()
    }
}
