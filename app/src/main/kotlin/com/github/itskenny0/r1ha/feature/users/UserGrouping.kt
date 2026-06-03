package com.github.itskenny0.r1ha.feature.users

import com.github.itskenny0.r1ha.core.ha.HaUser
import java.util.Locale

/**
 * Pure grouping / flag derivation for the Users browser. Kept free of Compose
 * and Android types so it can be unit-tested directly and so the screen reads
 * as a thin renderer over these helpers.
 *
 * HA's `config/auth/list` reply carries a per-user `group_ids` list. The two
 * groups HA Core ships are `system-admin` (full access) and `system-users`
 * (standard). Membership in the admin group is what gates Settings, so we lift
 * it into an explicit [isAdmin] flag and use it to bucket rows into the
 * Admins section.
 */

/** HA Core's built-in administrator group id. Hyphenated on the wire. */
const val GROUP_ADMIN = "system-admin"

/** HA Core's built-in standard-user group id. */
const val GROUP_USERS = "system-users"

/** HA Core's built-in read-only group id. Members can view state but not
 *  call services; HA surfaces this as a distinct group, so we tag it too. */
const val GROUP_READ_ONLY = "system-read-only"

/** Which section a row sorts into. Order here is the on-screen order. */
enum class UserSection { ADMINS, USERS, SYSTEM }

/**
 * Display-ready flags for a single user row, derived once so the renderer
 * doesn't re-compute membership tests inline. [linkedPersonName] /
 * [linkedPersonState] / [linkedPersonSince] are filled in when a `person.*`
 * entity links back to this user via its `user_id` attribute.
 */
data class UserRowModel(
    val id: String,
    /** Friendly name, already defaulted away from blank. */
    val displayName: String,
    val isOwner: Boolean,
    val isAdmin: Boolean,
    /** True when the user is in HA's built-in read-only group: can view but
     *  not control. Mutually exclusive with [isAdmin] in practice. */
    val isReadOnly: Boolean,
    val isActive: Boolean,
    val systemGenerated: Boolean,
    val localOnly: Boolean,
    val groupIds: List<String>,
    /** Friendly name of the linked person entity, when one points back at
     *  this user id. Null when no person is linked (most system rows). */
    val linkedPersonName: String? = null,
    /** Raw HA state of the linked person ("home" / "not_home" / a zone). */
    val linkedPersonState: String? = null,
    /** When the linked person's presence last changed. */
    val linkedPersonSince: java.time.Instant? = null,
) {
    val section: UserSection = sectionFor(systemGenerated = systemGenerated, isAdmin = isAdmin)
}

/** The three group ids HA Core ships built-in. Membership in these is already
 *  conveyed by the ADMIN / READ-ONLY flag chips, so the per-row "GROUPS …" line
 *  only earns its space when a row also belongs to a custom (non-built-in)
 *  group the chips don't cover. */
private val BUILT_IN_GROUPS = setOf(GROUP_ADMIN, GROUP_USERS, GROUP_READ_ONLY)

/**
 * Group ids that aren't one of HA Core's built-ins, in display order. Returns
 * empty when a user is only in the standard system groups (the common case), so
 * the renderer can skip the redundant "GROUPS · Admin, Users" line entirely.
 */
fun customGroupIds(groupIds: List<String>): List<String> =
    groupIds.filterNot { it.lowercase(Locale.US) in BUILT_IN_GROUPS }

/** True when [groupIds] grants administrator access. */
fun isAdmin(groupIds: List<String>): Boolean = groupIds.any { it.equals(GROUP_ADMIN, ignoreCase = true) }

/** True when [groupIds] is the built-in read-only group (view, no control). */
fun isReadOnly(groupIds: List<String>): Boolean =
    groupIds.any { it.equals(GROUP_READ_ONLY, ignoreCase = true) }

/**
 * Turn a wire group id into a short human label. HA Core's three built-in
 * groups get friendly names; anything custom is shown verbatim so a bespoke
 * group id is still recognisable.
 */
fun prettyGroupId(groupId: String): String = when (groupId.lowercase(Locale.US)) {
    GROUP_ADMIN -> "Admin"
    GROUP_USERS -> "Users"
    GROUP_READ_ONLY -> "Read-only"
    else -> groupId
}

/**
 * Bucket a row. System-generated rows always sort into SYSTEM regardless of
 * their groups (the built-in Supervisor user is admin but is plumbing, not a
 * person). Human rows split by admin membership.
 */
fun sectionFor(systemGenerated: Boolean, isAdmin: Boolean): UserSection = when {
    systemGenerated -> UserSection.SYSTEM
    isAdmin -> UserSection.ADMINS
    else -> UserSection.USERS
}

/** Friendly name, falling back to a placeholder when HA gave us a blank. */
fun displayNameFor(user: HaUser): String = user.name.ifBlank { "(no name)" }

/**
 * Build a row model from an [HaUser] and an optional linked person. [isOwner]
 * is threaded in separately because `config/auth/list` does not currently
 * surface the owner bit through [HaUser]; callers pass `false` until that is
 * wired through core/ha.
 */
fun rowModelFor(
    user: HaUser,
    isOwner: Boolean = false,
    linkedPersonName: String? = null,
    linkedPersonState: String? = null,
    linkedPersonSince: java.time.Instant? = null,
): UserRowModel = UserRowModel(
    id = user.id,
    displayName = displayNameFor(user),
    isOwner = isOwner,
    isAdmin = isAdmin(user.groupIds),
    isReadOnly = isReadOnly(user.groupIds),
    isActive = user.isActive,
    systemGenerated = user.systemGenerated,
    localOnly = user.localOnly,
    groupIds = user.groupIds,
    linkedPersonName = linkedPersonName,
    linkedPersonState = linkedPersonState,
    linkedPersonSince = linkedPersonSince,
)

/**
 * Group + sort rows for display. Returns the three sections in fixed order
 * (Admins, Users, System); empty sections are dropped so the screen never
 * renders an empty header. Within a section, rows sort by display name
 * case-insensitively, blanks last.
 */
fun groupUsers(rows: List<UserRowModel>): List<Pair<UserSection, List<UserRowModel>>> {
    val bySection = rows.groupBy { it.section }
    return UserSection.entries
        .mapNotNull { section ->
            val members = bySection[section].orEmpty()
            if (members.isEmpty()) {
                null
            } else {
                section to members.sortedBy { it.displayName.lowercase(Locale.US) }
            }
        }
}

/** Human-readable section title for an [R1Section] header. */
fun sectionTitle(section: UserSection): String = when (section) {
    UserSection.ADMINS -> "Admins"
    UserSection.USERS -> "Users"
    UserSection.SYSTEM -> "System"
}
