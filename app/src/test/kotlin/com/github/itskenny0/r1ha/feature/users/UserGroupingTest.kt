package com.github.itskenny0.r1ha.feature.users

import com.github.itskenny0.r1ha.core.ha.HaUser
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UserGroupingTest {

    private fun user(
        id: String = "id",
        name: String = "Name",
        systemGenerated: Boolean = false,
        isActive: Boolean = true,
        localOnly: Boolean = false,
        groupIds: List<String> = listOf(GROUP_USERS),
    ) = HaUser(
        id = id,
        name = name,
        systemGenerated = systemGenerated,
        isActive = isActive,
        localOnly = localOnly,
        groupIds = groupIds,
    )

    @Test fun `system-admin group is admin`() {
        assertThat(isAdmin(listOf(GROUP_ADMIN))).isTrue()
        assertThat(isAdmin(listOf("system-users"))).isFalse()
        assertThat(isAdmin(emptyList())).isFalse()
    }

    @Test fun `admin detection is case insensitive`() {
        assertThat(isAdmin(listOf("System-Admin"))).isTrue()
    }

    @Test fun `system-generated row always buckets to SYSTEM even when admin`() {
        assertThat(sectionFor(systemGenerated = true, isAdmin = true))
            .isEqualTo(UserSection.SYSTEM)
        assertThat(sectionFor(systemGenerated = true, isAdmin = false))
            .isEqualTo(UserSection.SYSTEM)
    }

    @Test fun `human admin buckets to ADMINS, human non-admin to USERS`() {
        assertThat(sectionFor(systemGenerated = false, isAdmin = true))
            .isEqualTo(UserSection.ADMINS)
        assertThat(sectionFor(systemGenerated = false, isAdmin = false))
            .isEqualTo(UserSection.USERS)
    }

    @Test fun `blank name falls back to placeholder`() {
        assertThat(displayNameFor(user(name = ""))).isEqualTo("(no name)")
        assertThat(displayNameFor(user(name = "Alice"))).isEqualTo("Alice")
    }

    @Test fun `rowModel derives flags from the user`() {
        val row = rowModelFor(
            user = user(
                id = "u1",
                name = "Bob",
                isActive = false,
                localOnly = true,
                groupIds = listOf(GROUP_ADMIN),
            ),
        )
        assertThat(row.id).isEqualTo("u1")
        assertThat(row.displayName).isEqualTo("Bob")
        assertThat(row.isAdmin).isTrue()
        assertThat(row.isActive).isFalse()
        assertThat(row.localOnly).isTrue()
        assertThat(row.isOwner).isFalse()
        assertThat(row.section).isEqualTo(UserSection.ADMINS)
    }

    @Test fun `rowModel carries linked person fields`() {
        val now = java.time.Instant.ofEpochSecond(1_700_000_000)
        val row = rowModelFor(
            user = user(),
            linkedPersonName = "Carol",
            linkedPersonState = "home",
            linkedPersonSince = now,
        )
        assertThat(row.linkedPersonName).isEqualTo("Carol")
        assertThat(row.linkedPersonState).isEqualTo("home")
        assertThat(row.linkedPersonSince).isEqualTo(now)
    }

    @Test fun `groupUsers returns sections in fixed order and drops empties`() {
        val rows = listOf(
            rowModelFor(user(id = "a", name = "Zoe", groupIds = listOf(GROUP_ADMIN))),
            rowModelFor(user(id = "b", name = "Amy", groupIds = listOf(GROUP_USERS))),
            rowModelFor(user(id = "c", name = "Supervisor", systemGenerated = true)),
        )
        val grouped = groupUsers(rows)
        assertThat(grouped.map { it.first })
            .containsExactly(UserSection.ADMINS, UserSection.USERS, UserSection.SYSTEM)
            .inOrder()
    }

    @Test fun `groupUsers omits a section with no members`() {
        val rows = listOf(
            rowModelFor(user(id = "b", name = "Amy", groupIds = listOf(GROUP_USERS))),
        )
        val grouped = groupUsers(rows)
        assertThat(grouped.map { it.first }).containsExactly(UserSection.USERS)
    }

    @Test fun `groupUsers sorts within a section by name case-insensitively`() {
        val rows = listOf(
            rowModelFor(user(id = "1", name = "charlie", groupIds = listOf(GROUP_USERS))),
            rowModelFor(user(id = "2", name = "Alice", groupIds = listOf(GROUP_USERS))),
            rowModelFor(user(id = "3", name = "Bob", groupIds = listOf(GROUP_USERS))),
        )
        val users = groupUsers(rows).single { it.first == UserSection.USERS }.second
        assertThat(users.map { it.displayName })
            .containsExactly("Alice", "Bob", "charlie")
            .inOrder()
    }

    @Test fun `sectionTitle maps each bucket`() {
        assertThat(sectionTitle(UserSection.ADMINS)).isEqualTo("Admins")
        assertThat(sectionTitle(UserSection.USERS)).isEqualTo("Users")
        assertThat(sectionTitle(UserSection.SYSTEM)).isEqualTo("System")
    }
}
