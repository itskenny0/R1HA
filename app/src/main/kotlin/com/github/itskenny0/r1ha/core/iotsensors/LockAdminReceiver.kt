package com.github.itskenny0.r1ha.core.iotsensors

import android.app.admin.DeviceAdminReceiver

/**
 * Minimal Device Admin receiver — only exists so [IotSensorsService] can call
 * DevicePolicyManager.lockNow() in response to the HA `lock_screen` button.
 *
 * Lock-screen is the only privileged action we use here. The companion
 * meta-data XML (res/xml/device_admin_lock.xml) declares only USES_POLICY_FORCE_LOCK
 * so the OS shows the user a single-line capability summary on the grant
 * prompt. Anything more would be over-scoped for what the entity does.
 *
 * The grant flow is user-initiated: the settings screen launches
 * DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN, and the receiver is dormant
 * until the user accepts. Toggling the lock-screen entity off in the settings
 * UI does NOT auto-disable the admin grant — users can revoke it under
 * system Settings → Security → Device admin apps when they want to be sure.
 */
class LockAdminReceiver : DeviceAdminReceiver()
