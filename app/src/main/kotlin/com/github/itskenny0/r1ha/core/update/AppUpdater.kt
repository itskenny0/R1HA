package com.github.itskenny0.r1ha.core.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.github.itskenny0.r1ha.BuildConfig
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Self-update via the GitHub Releases API. Queries `/repos/<owner>/<repo>/releases/latest`
 * (unauthenticated — the repository is public), parses the response for an .apk asset,
 * and downloads it to the app's cache so the package installer can pick it up.
 *
 * The flow is:
 *  1. [checkForUpdate] — HEAD-ish poll of the latest release; returns the parsed
 *     [UpdateInfo] when the release's versionCode beats [BuildConfig.VERSION_CODE].
 *  2. UI presents the user with version-name + release notes and a CONFIRM button.
 *  3. [downloadAndInstall] — streams the .apk into `cacheDir/updates/`, fires
 *     `ACTION_VIEW` with a content:// URI from our FileProvider, and Android's
 *     standard package installer prompts the user to confirm the install.
 *
 * Nothing here installs silently — the OS-level confirmation dialog is the last
 * line of defence and is required by `REQUEST_INSTALL_PACKAGES` semantics. The
 * cache-dir staging means the file disappears on next cache cleanup so we don't
 * leak partial downloads.
 *
 * versionCode parsing assumes the release's published asset URL contains the
 * canonical `r1ha-YYYY.MM.DD.HHmm.apk` name; we derive a versionCode from the
 * tag-name (the workflow already does this) by mirroring the workflow's math:
 * `100_000_000 + minutes-since-2020-01-01-UTC`. That keeps the check
 * deterministic without needing to fetch + parse the APK itself.
 */
class AppUpdater(
    private val http: OkHttpClient,
    private val releasesUrl: String = "https://api.github.com/repos/itskenny0/R1HA/releases/latest",
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Hit GitHub's API and return [UpdateInfo] when a strictly-newer release is
     * available, null otherwise. Network failures (offline, GitHub rate-limited,
     * malformed response) are caught and surfaced as null — the caller is the UI
     * layer and a missed check shouldn't crash the app.
     */
    /**
     * Result of [checkForUpdate]. Splits "nothing to do" from "couldn't tell"
     * so the UI surfaces real network / parse errors instead of silently
     * showing UP TO DATE every time GitHub rate-limits us or DNS fails.
     */
    sealed interface CheckResult {
        /** A strictly-newer release is available. */
        data class Available(val info: UpdateInfo) : CheckResult
        /** GitHub returned the latest release and it isn't newer than us. */
        data object UpToDate : CheckResult
        /** Anything that went wrong: HTTP non-2xx, network IOException, JSON
         *  parse failure, malformed tag, no APK asset attached. [message] is
         *  the user-visible explanation; [cause] is kept for diagnostic
         *  logging by the caller. */
        data class Failed(val message: String, val cause: Throwable? = null) : CheckResult
    }

    /**
     * Result of [listReleases]. Mirrors [CheckResult] so the About-screen picker
     * can tell "couldn't reach GitHub" / "rate-limited" apart from "no installable
     * releases for this flavour" and render the right message.
     */
    sealed interface ReleasesResult {
        /** One or more installable releases (matching-flavour APK, parseable tag),
         *  newest first. May still be empty if GitHub returned releases but none
         *  carried an asset for this flavour. */
        data class Ok(val releases: List<ReleaseOption>) : ReleasesResult
        /** HTTP non-2xx (incl. 403 rate-limit), network IOException, or JSON
         *  parse failure. [message] is user-visible; [cause] is for logging. */
        data class Failed(val message: String, val cause: Throwable? = null) : ReleasesResult
    }

    /**
     * GET the (first page of the) releases list and map each entry to a
     * [ReleaseOption] for the current [flavor]. Unauthenticated, like
     * [checkForUpdate]; the first page (~30 releases) is plenty for a picker.
     *
     * Releases without a matching-flavour APK asset, or with an unparseable tag,
     * are dropped. The result is sorted newest-first (by derived versionCode).
     * [installedVersionCode] flags the currently-running build so the UI can mark
     * and disable it.
     *
     * The default [listUrl] derives from [releasesUrl] by stripping the trailing
     * `/latest`; tests can inject either directly.
     */
    suspend fun listReleases(
        flavor: String = BuildConfig.FLAVOR,
        installedVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
        listUrl: String = releasesUrl.removeSuffix("/latest"),
    ): ReleasesResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(listUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "R1HA-self-update/${BuildConfig.VERSION_NAME}")
            // Same cold-fetch rationale as checkForUpdate: an intermediate cache
            // holding the prior /releases response would hide a fresh tag.
            .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
            .build()
        val body = runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // 403 here is almost always GitHub's unauthenticated rate
                    // limit (60 req/hr/IP). Name it so the UI can say so.
                    val msg = if (resp.code == 403) {
                        "GitHub rate limit reached (HTTP 403); try again later"
                    } else {
                        "GitHub returned HTTP ${resp.code}"
                    }
                    R1Log.w("Updater.list", msg)
                    return@withContext ReleasesResult.Failed(msg)
                }
                resp.body?.string() ?: return@withContext ReleasesResult.Failed("empty response body")
            }
        }.getOrElse { t ->
            val msg = t.message ?: t::class.java.simpleName
            R1Log.w("Updater.list", "network failure: $msg")
            return@withContext ReleasesResult.Failed("Network: $msg", t)
        }
        val releases = runCatching { json.decodeFromString<List<GhRelease>>(body) }
            .getOrElse { t ->
                R1Log.w("Updater.list", "JSON parse failure: ${t.message}")
                return@withContext ReleasesResult.Failed("Bad releases JSON", t)
            }
        ReleasesResult.Ok(mapReleases(releases, flavor, installedVersionCode))
    }

    suspend fun checkForUpdate(): CheckResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(releasesUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "R1HA-self-update/${BuildConfig.VERSION_NAME}")
            // Force a cold fetch in case some intermediate cache is holding
            // the prior /releases/latest response. GitHub's own ETag caching
            // still works at the server side (we just don't get the 304
            // shortcut); the alternative was silent staleness on tag bumps.
            .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
            .build()
        val body = runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = "GitHub returned HTTP ${resp.code}"
                    R1Log.w("Updater.check", msg)
                    return@withContext CheckResult.Failed(msg)
                }
                resp.body?.string() ?: return@withContext CheckResult.Failed("empty response body")
            }
        }.getOrElse { t ->
            val msg = t.message ?: t::class.java.simpleName
            R1Log.w("Updater.check", "network failure: $msg")
            return@withContext CheckResult.Failed("Network: $msg", t)
        }
        val release = runCatching { json.decodeFromString<GhRelease>(body) }
            .getOrElse { t ->
                R1Log.w("Updater.check", "JSON parse failure: ${t.message}")
                return@withContext CheckResult.Failed("Bad release JSON", t)
            }
        // Strip the r1ha- prefix and parse the tag's date+time into minutes-
        // since-2020-01-01-UTC, then add the 100M floor that the workflow
        // applies. This must stay in lock-step with `.github/workflows/release.yml`.
        val versionCode = versionCodeFromTag(release.tag_name)
            ?: return@withContext CheckResult.Failed("Malformed tag: ${release.tag_name}")
        if (versionCode <= BuildConfig.VERSION_CODE) {
            R1Log.i("Updater.check", "already on latest (${BuildConfig.VERSION_CODE} ≥ $versionCode)")
            return@withContext CheckResult.UpToDate
        }
        // Each release ships one APK per flavour (github / fdroid / legacy-R1HAL).
        // Pick the one matching THIS build's flavour via [flavorAssetFor]: a legacy
        // build must install the `-legacy-` APK, not the github one — the github
        // APK has a different applicationId and would install R1HA as a separate
        // app instead of updating R1HAL (the bug this replaces, which only filtered
        // out `-fdroid-` and so handed legacy the github asset).
        val apkAsset = flavorAssetFor(release.assets, BuildConfig.FLAVOR)
            ?: return@withContext CheckResult.Failed(
                "No ${BuildConfig.FLAVOR}-flavour APK attached to ${release.tag_name}",
            )
        CheckResult.Available(
            UpdateInfo(
                versionCode = versionCode,
                versionName = release.name ?: release.tag_name,
                tagName = release.tag_name,
                notes = release.body.orEmpty(),
                apkUrl = apkAsset.browser_download_url,
                apkSizeBytes = apkAsset.size,
                apkName = apkAsset.name,
            ),
        )
    }

    /**
     * Stream the APK to `cacheDir/updates/<name>`, then fire ACTION_VIEW with a
     * FileProvider content URI so Android's installer prompts the user. Returns
     * the staged File on success, throws on download / IO failure. Progress is
     * reported via the optional [onProgress] callback (bytes downloaded, total
     * bytes) so the UI can render a progress bar.
     */
    suspend fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onProgress: (bytesRead: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val outDir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Clear any previous staged APKs so we don't leak old downloads. Cheap —
        // typically zero files, occasionally one.
        outDir.listFiles()?.forEach { it.delete() }
        val outFile = File(outDir, info.apkName)
        // Refuse any asset URL that isn't HTTPS on a github host. The URLs come
        // from the GitHub API so this should always pass; it's a cheap guard so a
        // tampered response can't redirect the installer to an arbitrary host.
        require(isTrustedAssetUrl(info.apkUrl)) { "Refusing untrusted asset URL host" }
        val req = Request.Builder().url(info.apkUrl).build()
        http.newCall(req).execute().use { resp ->
            require(resp.isSuccessful) { "GitHub asset download returned HTTP ${resp.code}" }
            val body = resp.body ?: error("empty response body")
            val total = body.contentLength().coerceAtLeast(info.apkSizeBytes)
            body.byteStream().use { input ->
                outFile.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        }
        R1Log.i("Updater.dl", "staged ${outFile.absolutePath} (${outFile.length()} bytes)")
        // Hand off to the package installer. ACTION_VIEW with the APK content URI
        // and FLAG_GRANT_READ_URI_PERMISSION is the canonical pattern for self-
        // update without a custom installer; the OS prompts the user, and on
        // approve replaces the running app in-place (versionCode strictly greater
        // is enforced by Android, which the workflow + 100M floor guarantee).
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updates",
            outFile,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
        outFile
    }

    @Serializable
    internal data class GhRelease(
        val tag_name: String,
        val name: String? = null,
        val body: String? = null,
        val published_at: String? = null,
        val assets: List<GhAsset> = emptyList(),
    )

    @Serializable
    internal data class GhAsset(
        val name: String,
        val browser_download_url: String,
        val size: Long,
    )

    companion object {
        /**
         * Pick the APK asset matching [flavor] from a release's asset list.
         *
         * Asset naming is flavour-encoded by the release workflow:
         *  - github flavour: `r1ha-YYYY.MM.DD.HHmm.apk` (no flavour infix)
         *  - fdroid flavour: `r1ha-fdroid-YYYY.MM.DD.HHmm.apk`
         *  - legacy (R1HAL):  `r1ha-legacy-YYYY.MM.DD.HHmm.apk`
         *
         * Each build installs only its OWN flavour's APK. This matters most for
         * legacy: R1HAL has applicationId `...r1ha.legacy`, so installing the
         * github APK wouldn't update it — Android would install R1HA as a SEPARATE
         * app. (The earlier `!= -fdroid-` test treated legacy as github and did
         * exactly that.) A cross-flavour install would also re-introduce the
         * REQUEST_INSTALL_PACKAGES permission on fdroid or strip the self-updater
         * on github. Returns null when no asset matches the flavour.
         */
        internal fun flavorAssetFor(assets: List<GhAsset>, flavor: String): GhAsset? {
            // The exact infix this flavour's asset carries; github has none.
            val infix = when (flavor) {
                "fdroid" -> "-fdroid-"
                "legacy" -> "-legacy-"
                else -> null
            }
            return assets.firstOrNull { a ->
                if (!a.name.endsWith(".apk")) return@firstOrNull false
                if (infix != null) {
                    a.name.contains(infix)
                } else {
                    // github: the bare name, carrying NO other flavour's infix.
                    !a.name.contains("-fdroid-") && !a.name.contains("-legacy-")
                }
            }
        }

        /**
         * Whether the download host is GitHub's. Release assets live on
         * `github.com` (browser_download_url) or `*.githubusercontent.com`
         * (the redirect target / objects.githubusercontent.com). We refuse to
         * download from anything else, so a tampered or unexpected API response
         * can't point the installer at an arbitrary host. HTTPS is required too.
         */
        internal fun isTrustedAssetUrl(url: String): Boolean {
            val host = runCatching { java.net.URI(url) }.getOrNull()
                ?.takeIf { it.scheme.equals("https", ignoreCase = true) }
                ?.host
                ?.lowercase()
                ?: return false
            return host == "github.com" ||
                host == "githubusercontent.com" ||
                host.endsWith(".github.com") ||
                host.endsWith(".githubusercontent.com")
        }

        /**
         * Map a raw releases list to installable [ReleaseOption]s for [flavor],
         * newest first. Drops releases with an unparseable tag or no
         * matching-flavour APK; flags the one whose versionCode equals
         * [installedVersionCode] as current. Pure (no I/O) so it's unit-tested.
         */
        internal fun mapReleases(
            releases: List<GhRelease>,
            flavor: String,
            installedVersionCode: Long,
        ): List<ReleaseOption> = releases.mapNotNull { rel ->
            val versionCode = versionCodeFromTag(rel.tag_name) ?: return@mapNotNull null
            val asset = flavorAssetFor(rel.assets, flavor) ?: return@mapNotNull null
            // Belt-and-braces: never surface an asset we wouldn't download.
            if (!isTrustedAssetUrl(asset.browser_download_url)) return@mapNotNull null
            ReleaseOption(
                versionName = rel.name ?: rel.tag_name,
                tagName = rel.tag_name,
                versionCode = versionCode,
                apkAssetUrl = asset.browser_download_url,
                apkName = asset.name,
                apkSizeBytes = asset.size,
                notes = rel.body.orEmpty(),
                publishedAt = rel.published_at,
                isCurrent = versionCode == installedVersionCode,
            )
        }.sortedByDescending { it.versionCode }

        /**
         * Convert a release tag to its derived versionCode. Tag forms accepted:
         *  - `r1ha-YYYYMMDD` (legacy date-only) → minutes-since-2020-01-01 at 00:00 UTC
         *  - `r1ha-YYYYMMDD-HHmm` (current scheme) → minutes-since-2020-01-01 at HH:mm UTC
         * Both go through the same 100M floor as the workflow + defaultVersionCode().
         * Returns null on a malformed tag — the caller treats that as "no update
         * info" so a typo in a release name doesn't crash the updater.
         */
        internal fun versionCodeFromTag(tag: String): Long? {
            val rest = tag.removePrefix("r1ha-")
            if (rest.length < 8) return null
            val yyyymmdd = rest.substring(0, 8)
            val hhmm = if (rest.length >= 13 && rest[8] == '-') rest.substring(9, 13) else "0000"
            val year = yyyymmdd.substring(0, 4).toIntOrNull() ?: return null
            val month = yyyymmdd.substring(4, 6).toIntOrNull() ?: return null
            val day = yyyymmdd.substring(6, 8).toIntOrNull() ?: return null
            val hour = hhmm.substring(0, 2).toIntOrNull() ?: return null
            val minute = hhmm.substring(2, 4).toIntOrNull() ?: return null
            return runCatching {
                val epoch = java.time.LocalDateTime.of(2020, 1, 1, 0, 0)
                val tagMoment = java.time.LocalDateTime.of(year, month, day, hour, minute)
                val minutesSince = java.time.Duration.between(epoch, tagMoment).toMinutes()
                100_000_000L + minutesSince
            }.getOrNull()
        }
    }
}

/**
 * Result of a successful update check — everything the UI needs to render the
 * "an update is available" prompt and the subsequent download flow.
 */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val tagName: String,
    val notes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val apkName: String,
)

/**
 * One installable release for the About-screen version picker. Built by
 * [AppUpdater.mapReleases] from the GitHub releases list, already filtered to
 * the running build flavour and sorted newest-first by the caller.
 *
 * [isCurrent] marks the build the user is on (the picker disables INSTALL for
 * it). Compare [versionCode] against the installed code to decide whether a
 * chosen release is a downgrade: installing a strictly-older versionCode in
 * place is blocked by Android (INSTALL_FAILED_VERSION_DOWNGRADE), so the UI
 * warns that an uninstall (losing app data) may be required first.
 */
data class ReleaseOption(
    val versionName: String,
    val tagName: String,
    val versionCode: Long,
    val apkAssetUrl: String,
    val apkName: String,
    val apkSizeBytes: Long,
    val notes: String,
    val publishedAt: String?,
    val isCurrent: Boolean,
) {
    /** Adapt to the [UpdateInfo] the download path consumes. */
    fun toUpdateInfo(): UpdateInfo = UpdateInfo(
        versionCode = versionCode,
        versionName = versionName,
        tagName = tagName,
        notes = notes,
        apkUrl = apkAssetUrl,
        apkSizeBytes = apkSizeBytes,
        apkName = apkName,
    )
}
