package com.github.itskenny0.r1ha.feature.backups

import com.github.itskenny0.r1ha.core.ha.BackupInfo
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure Backups helpers in BackupsLogic.kt: byte-size and
 * ISO-8601 date formatting, type labelling, and the sort orders. No Compose or
 * Android runtime touched.
 */
class BackupsLogicTest {

    private fun backup(
        id: String,
        name: String = id,
        createdAt: String? = null,
        size: Long? = null,
        protected: Boolean = false,
        type: String? = null,
    ) = BackupInfo(
        backupId = id,
        name = name,
        createdAt = createdAt,
        sizeBytes = size,
        protected = protected,
        type = type,
    )

    // --- formatSize ---

    @Test
    fun formatSize_picks_the_right_tier() {
        assertThat(BackupsLogic.formatSize(0)).isEqualTo("0 B")
        assertThat(BackupsLogic.formatSize(512)).isEqualTo("512 B")
        assertThat(BackupsLogic.formatSize(1024)).isEqualTo("1.0 KB")
        assertThat(BackupsLogic.formatSize(1_572_864)).isEqualTo("1.5 MB")
        assertThat(BackupsLogic.formatSize(1_610_612_736)).isEqualTo("1.5 GB")
    }

    @Test
    fun formatSize_null_or_negative_is_unknown() {
        assertThat(BackupsLogic.formatSize(null)).isEqualTo("Unknown size")
        assertThat(BackupsLogic.formatSize(-1)).isEqualTo("Unknown size")
    }

    // --- formatCreatedAt ---

    @Test
    fun formatCreatedAt_keeps_date_and_minute_drops_offset_and_fraction() {
        assertThat(BackupsLogic.formatCreatedAt("2024-06-12T08:31:45.123456+00:00"))
            .isEqualTo("2024-06-12 08:31")
        assertThat(BackupsLogic.formatCreatedAt("2024-06-12T08:31:45Z"))
            .isEqualTo("2024-06-12 08:31")
        // A non-UTC offset is normalized to UTC (08:31 minus 05:00 = 13:31 UTC),
        // not silently dropped, so the displayed time is the real instant.
        assertThat(BackupsLogic.formatCreatedAt("2024-06-12T08:31:45-05:00"))
            .isEqualTo("2024-06-12 13:31")
    }

    @Test
    fun formatCreatedAt_null_blank_and_unparseable() {
        assertThat(BackupsLogic.formatCreatedAt(null)).isEqualTo("Unknown date")
        assertThat(BackupsLogic.formatCreatedAt("   ")).isEqualTo("Unknown date")
        // No 'T' separator: returned verbatim rather than mangled.
        assertThat(BackupsLogic.formatCreatedAt("not-a-date")).isEqualTo("not-a-date")
    }

    // --- typeLabel ---

    @Test
    fun typeLabel_capitalises_or_defaults_to_manual() {
        assertThat(BackupsLogic.typeLabel(null)).isEqualTo("Manual")
        assertThat(BackupsLogic.typeLabel("")).isEqualTo("Manual")
        assertThat(BackupsLogic.typeLabel("automatic")).isEqualTo("Automatic")
        assertThat(BackupsLogic.typeLabel("manual")).isEqualTo("Manual")
    }

    // --- sortBackups ---

    @Test
    fun sortBackups_newest_first_with_nulls_last() {
        val list = listOf(
            backup("a", createdAt = "2024-01-01T00:00:00+00:00"),
            backup("b", createdAt = null),
            backup("c", createdAt = "2024-06-01T00:00:00+00:00"),
        )
        val sorted = BackupsLogic.sortBackups(list, BackupsLogic.Sort.NEWEST_FIRST)
        assertThat(sorted.map { it.backupId }).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    fun sortBackups_oldest_first_with_nulls_last() {
        val list = listOf(
            backup("a", createdAt = "2024-01-01T00:00:00+00:00"),
            backup("b", createdAt = null),
            backup("c", createdAt = "2024-06-01T00:00:00+00:00"),
        )
        val sorted = BackupsLogic.sortBackups(list, BackupsLogic.Sort.OLDEST_FIRST)
        assertThat(sorted.map { it.backupId }).containsExactly("a", "c", "b").inOrder()
    }

    @Test
    fun sortBackups_by_name_is_case_insensitive() {
        val list = listOf(
            backup("1", name = "zeta"),
            backup("2", name = "Alpha"),
            backup("3", name = "beta"),
        )
        val sorted = BackupsLogic.sortBackups(list, BackupsLogic.Sort.NAME)
        assertThat(sorted.map { it.name }).containsExactly("Alpha", "beta", "zeta").inOrder()
    }

    @Test
    fun sortBackups_by_size_desc_with_nulls_last() {
        val list = listOf(
            backup("a", size = 100),
            backup("b", size = null),
            backup("c", size = 5000),
        )
        val sorted = BackupsLogic.sortBackups(list, BackupsLogic.Sort.SIZE_DESC)
        assertThat(sorted.map { it.backupId }).containsExactly("c", "a", "b").inOrder()
    }
}
