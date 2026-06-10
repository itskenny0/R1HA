package com.github.itskenny0.r1ha.core.ha

/**
 * The logged-in user, from HA's `auth/current_user` WebSocket command. Unlike
 * [HaUser] (an admin-only entry of `config/auth/list`), this command is
 * available to any authenticated token and returns only the caller's own
 * identity.
 *
 * Used by the Lovelace `user` / `location` conditions and the action
 * confirmation-exemption check. The command predates very few HA versions, but
 * we still degrade silently: a server that doesn't recognise it, or a call that
 * fails, leaves the cached identity null and those conditions evaluate exactly
 * as HA does for an unknown user (fail closed).
 */
data class HaCurrentUser(
    /** Stable server-assigned user id (matches a person entity's `user_id`
     *  attribute and an action exemption's `user:` id). */
    val id: String,
    /** Friendly display name. May be blank for system tokens. */
    val name: String,
    /** True when the account is in HA's admin group. */
    val isAdmin: Boolean,
)
