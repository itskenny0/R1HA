package com.github.itskenny0.r1ha.feature.onboarding

/**
 * Normalise whatever the user typed into the Onboarding URL field into a usable HA
 * server base URL. The logic is small but worth its own file so it can be unit-tested
 * directly: the VM that wraps it (OnboardingViewModel.probe) has lifecycle, network,
 * and coroutine machinery that's an awkward harness for a string-in / string-out
 * function.
 *
 * Rules applied in order:
 *
 *   1. Trim surrounding whitespace and strip a single trailing slash.
 *   2. If the input is empty after trimming, return empty (caller surfaces an error).
 *   3. If the input already starts with `http://` or `https://`, the user chose the
 *      protocol on purpose. Return it verbatim — no inference, no port addition. The
 *      user with a reverse-proxy at `http://example.com/ha` does not want the port
 *      magicked into the middle of their URL.
 *   4. Otherwise (no scheme), the user typed something shaped like a host. We pick
 *      the protocol and add the default HA port if needed:
 *        - mDNS hosts (`*.local`), `localhost`, and the private IPv4 ranges
 *          (10/8, 172.16/12, 192.168/16, 127/8) get `http://`. Bare hostnames with
 *          no dots also fall here. These are the LAN cases where TLS is rare and
 *          HA's default port is 8123. If the user didn't already type a port, we
 *          append `:8123`.
 *        - Anything else with a dot is treated as a public DNS name and gets
 *          `https://`. Cloud HA installs (Nabu Casa, self-hosted with Let's Encrypt)
 *          are almost always HTTPS-only on the implicit :443.
 *
 * The function is pure: no logging, no toasts, no settings reads. Tests in
 * UrlNormalizerTest exercise every branch.
 */
internal fun normalizeServerUrl(raw: String): String {
    // Replace any backslashes with forward slashes first — Windows users and
    // copy-paste from some sources can produce https:\\host instead of https://host.
    val trimmed = raw.trim().replace('\\', '/').trimEnd('/')
    if (trimmed.isBlank()) return ""

    val alreadyHasProtocol =
        trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)

    // Step 1: pick a protocol if the user didn't type one.
    val withProtocol = if (alreadyHasProtocol) {
        trimmed
    } else {
        val hostOnly = trimmed.substringBefore('/').substringBefore('?').substringBefore(':')
        if (looksLikePublicHost(hostOnly)) "https://$trimmed" else "http://$trimmed"
    }

    // Step 2: auto-add the HA default port (:8123) only when ALL of:
    //   - resulting URL is plain http:// (port 80, virtually never an HA instance);
    //   - the host has no explicit `:port`;
    //   - there's no path component (a path implies the user is targeting a reverse
    //     proxy that already routes by URL; injecting :8123 between host and path
    //     would break it).
    // For https:// we always leave the port alone — public deployments universally
    // serve on implicit :443.
    return autoAddDefaultPort(withProtocol)
}

private fun autoAddDefaultPort(url: String): String {
    val scheme = when {
        url.startsWith("http://", ignoreCase = true) -> "http://"
        url.startsWith("HTTP://") -> "HTTP://"
        else -> return url
    }
    val withoutScheme = url.substring(scheme.length)
    val hostAndPort = withoutScheme.substringBefore('/').substringBefore('?')
    val pathSuffix = withoutScheme.substring(hostAndPort.length)
    if (hostAndPort.isBlank()) return url
    if (':' in hostAndPort) return url
    if (pathSuffix.isNotEmpty()) return url
    return "$scheme$hostAndPort:8123"
}

/**
 * True when [host] looks like a public DNS name (and so most likely served over
 * HTTPS in a Home Assistant deployment), false when it looks like an internal LAN
 * target (where HTTP is the norm).
 */
private fun looksLikePublicHost(host: String): Boolean {
    if (host.isBlank()) return false
    val lower = host.lowercase()
    if (lower == "localhost") return false
    if (lower.endsWith(".local")) return false // mDNS / Bonjour
    if (isPrivateIpv4(lower)) return false
    if (lower.startsWith("[") || ':' in lower) return false // IPv6 literal: too varied
    return '.' in lower
}

/**
 * RFC 1918 + loopback check: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8.
 * String-prefix tests are imprecise for the 172.16/12 block (where the second
 * octet has to be in 16..31), so we parse the octets and compare numerically.
 * Anything that doesn't look like four octets returns false (treated as a DNS
 * name by the caller, which routes to the HTTPS heuristic).
 */
private fun isPrivateIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    val a = octets[0]
    val b = octets[1]
    return when {
        a == 10 -> true
        a == 127 -> true
        a == 172 && b in 16..31 -> true
        a == 192 && b == 168 -> true
        else -> false
    }
}
