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
        // Each release now ships two APKs: the github-flavour one (with the
        // self-updater enabled — that's what we want here) named
        //   r1ha-YYYY.MM.DD.HHmm.apk
        // and the fdroid-flavour one (no self-updater, fewer permissions) named
        //   r1ha-fdroid-YYYY.MM.DD.HHmm.apk
        // The in-app updater MUST pick the github asset, otherwise an updating
        // user would land on the fdroid build and lose the self-updater on their
        // next install. Filter `-fdroid-` out explicitly rather than relying on
        // alphabetical order in the assets array.
        val apkAsset = release.assets.firstOrNull {
            it.name.endsWith(".apk") && !it.name.contains("-fdroid-")
        } ?: return@withContext CheckResult.Failed("No github-flavour APK attached to ${release.tag_name}")
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
    private data class GhRelease(
        val tag_name: String,
        val name: String? = null,
        val body: String? = null,
        val assets: List<GhAsset> = emptyList(),
    )

    @Serializable
    private data class GhAsset(
        val name: String,
        val browser_download_url: String,
        val size: Long,
    )

    companion object {
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
