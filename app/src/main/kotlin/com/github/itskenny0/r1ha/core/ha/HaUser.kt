package com.github.itskenny0.r1ha.core.ha

/**
 * One entry from HA's `config/auth/list` reply. Admin-only — non-admin tokens
 * receive a permission_denied / auth_error response, which the Users browser
 * surfaces as a friendly "you need admin to see this" empty state rather than
 * a stack trace.
 *
 * HA carries more per-user fields (credentials list, refresh-token count,
 * MFA modules, etc.); we only model what the read-only browser actually
 * shows. Adding fields here is forward-compatible.
 */
data class HaUser(
    /** Stable server-assigned id, e.g. "0a1b2c…". Shown as a small mono
     *  trailing line under the friendly name. */
    val id: String,
    /** Friendly username chosen by the admin. May be blank for
     *  system-generated rows; the row falls back to "(no name)". */
    val name: String,
    /** True for the built-in Home Assistant / Supervisor user rows that the
     *  admin can't actually delete. The browser uses this to render a small
     *  "SYSTEM" badge so it's obvious they exist by design. */
    val systemGenerated: Boolean,
    /** False when the admin has disabled the account but kept the row for
     *  audit. Disabled accounts can't sign in or hold a refresh token. */
    val isActive: Boolean,
    /** True for accounts marked local-only — they can only sign in from
     *  the local network range, not via Nabu Casa / external URL. */
    val localOnly: Boolean,
    /** Group memberships. Most installs have system_admin / system_users;
     *  surface them so a "why can this person change settings?" question
     *  is answerable from the device. */
    val groupIds: List<String>,
)
