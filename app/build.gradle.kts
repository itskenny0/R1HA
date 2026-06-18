import java.util.Properties
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.kover)
    alias(libs.plugins.ktlint)
}

// ktlint runs as an on-demand `./gradlew ktlintCheck` / `ktlintFormat` task.
// Not auto-wired into the regular `check` task because the existing codebase
// has style drift from before this plugin was added; forcing all of it through
// the linter in one go would mean either a thousand-file format commit (which
// muddies git blame) or a thousand-line baseline file. The pragmatic middle
// ground: keep the linter available for newly-written code (run it manually
// before opening a PR), accept the existing drift as is, and gradually heal
// touched files as they're modified for real reasons.
ktlint {
    android = true
    ignoreFailures = true // for `./gradlew check` paths that include this transitively
    filter {
        // Don't lint generated code (BuildConfig, R, etc.) or build outputs.
        exclude { entry -> entry.file.path.contains("/build/") }
        exclude("**/build/**")
    }
}

android {
    namespace = "com.github.itskenny0.r1ha"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.github.itskenny0.r1ha"
        // Android 6.0. The app is built and tested against modern hardware (the
        // R1 targets run Android 14/16); 23 is the floor the current Jetpack Compose
        // libraries support, kept low deliberately so the app can give aging
        // devices a second life as a Home Assistant remote. Features that need a
        // newer OS (Quick Settings tiles = 24, notification channels = 26, etc.)
        // are gated at runtime; see the README "Supported vs. works" section for
        // what degrades on older devices.
        minSdk = 23
        targetSdk = 34
        // Versions are date-based to match the `r1ha-YYYYMMDD` release tag scheme.
        // CI passes APP_VERSION_CODE / APP_VERSION_NAME on tag builds; local builds fall back to today's date.
        versionCode = (System.getenv("APP_VERSION_CODE") ?: defaultVersionCode()).toInt()
        versionName = System.getenv("APP_VERSION_NAME") ?: defaultVersionName()

        // BuildConfig fields surfaced in the About screen
        buildConfigField("String", "SOURCE_URL", "\"https://github.com/itskenny0/R1HA\"")
        buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        getByName("debug") {
            // Stable debug keystore committed at repo root so every CI release
            // signs with the same SHA-1 and `adb install -r` works for updates.
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release config is read from local.properties / gradle.properties if present.
        // If not present, release builds will fail explicitly rather than ship unsigned.
        val keystorePropsFile = rootProject.file("local.properties")
        if (keystorePropsFile.exists()) {
            val props = Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
            if (props.getProperty("RELEASE_STORE_FILE") != null) {
                create("release") {
                    storeFile = file(props.getProperty("RELEASE_STORE_FILE"))
                    storePassword = props.getProperty("RELEASE_STORE_PASSWORD")
                    keyAlias = props.getProperty("RELEASE_KEY_ALIAS")
                    keyPassword = props.getProperty("RELEASE_KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // Three product flavours so distribution-specific behaviour can vary without a
    // separate branch:
    //
    //   github  → default; ships to GitHub Releases with self-updater enabled.
    //   fdroid  → strips the in-app self-updater (and REQUEST_INSTALL_PACKAGES) via a
    //             flavour-specific manifest overlay at app/src/fdroid/AndroidManifest.xml.
    //             F-Droid users get updates from the F-Droid client; folding the same
    //             affordance in would duplicate notifications and trip F-Droid's
    //             anti-feature scanner.
    //   legacy  → a deliberately slim build for aging hardware, branded "R1HAL". Ships
    //             only the card stack + its more-info drill-ins. minSdk stays at 23
    //             because the current Jetpack Compose (BOM foundation-layout) hard-floors
    //             there — 23 is genuinely the lowest API the card stack can run on, so
    //             the legacy build's value is the reduced feature set + slim APK + its
    //             own installable package, not a lower OS floor. The reduced set is
    //             enforced two ways: the
    //             manifest overlay at app/src/legacy/AndroidManifest.xml drops the
    //             hardware/service permissions the dropped features needed, and
    //             AppNavGraph routes the dropped destinations to a placeholder when
    //             BuildConfig.IS_LEGACY is set. Because IS_LEGACY is a compile-time
    //             constant, R8 dead-code-eliminates the real feature screens out of the
    //             release APK entirely (see com.github.itskenny0.r1ha.nav.LegacyFeatures).
    //             Unlike the other flavours it takes a distinct applicationId (the
    //             ".legacy" suffix) and its own label / icon / accent so R1HAL installs
    //             ALONGSIDE a full R1HA rather than upgrading it.
    //
    // github and fdroid keep an identical applicationId so they register as the SAME app
    // from Android's POV — switching distribution requires a sign-out then sign-in only
    // if the signatures differ (they do for f-droid.org's main repo because F-Droid signs
    // with their own key; they don't for IzzyOnDroid, which re-publishes the
    // github-flavour APK signed with our key).
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_FDROID_BUILD", "false")
            buildConfigField("boolean", "IS_LEGACY", "false")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_FDROID_BUILD", "true")
            buildConfigField("boolean", "IS_LEGACY", "false")
        }
        create("legacy") {
            dimension = "distribution"
            // Stays at the project floor: the Compose BOM's foundation-layout requires
            // minSdk 23, so going lower would need tools:overrideLibrary and risk runtime
            // crashes on exactly the old devices this build targets. 23 is the real floor.
            minSdk = 23
            // Distinct package so R1HAL coexists with a full R1HA install. The
            // FileProvider authority (${applicationId}.updates) and any other
            // applicationId-derived names follow the suffix automatically.
            applicationIdSuffix = ".legacy"
            // Surfaces in the About screen / window title so the slim build is
            // unmistakable; the launcher label comes from the manifest overlay's
            // @string/app_name override (R1HAL).
            versionNameSuffix = "-legacy"
            buildConfigField("boolean", "IS_FDROID_BUILD", "false")
            buildConfigField("boolean", "IS_LEGACY", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time (used pervasively for entity timestamps, history, calendars)
        // is an API-26 platform addition. Core-library desugaring backports it
        // (and java.util.stream / Optional / etc.) down to our minSdk so the date
        // and time code runs on Android 6.0/7.x without a NoClassDefFoundError.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Compose compiler metrics + stability reports for the release variant. Emits
    // composables.txt / classes.txt under build/compose_compiler so we can audit
    // skip rates + stable-vs-unstable parameter classifications when chasing perf
    // regressions. Gated behind a project property so opt-in (the build doesn't
    // generate the reports unless `-PcomposeReports=true` is on the command line)
    // since the files are large and CI builds don't need them.
    val composeReports = providers.gradleProperty("composeReports").orNull == "true"
    if (composeReports) {
        composeCompiler {
            metricsDestination = layout.buildDirectory.dir("compose_compiler/metrics")
            reportsDestination = layout.buildDirectory.dir("compose_compiler/reports")
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
        )
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Non-Robolectric JVM tests (e.g. HaWebSocketClientTest) exercise production code
            // that logs via android.util.Log; without this, an un-mocked Log call throws
            // "Method w in android.util.Log not mocked". Returning defaults lets those tests run
            // the real code path while ignoring the log side effect. Robolectric tests shadow
            // android.* themselves, so this flag is a no-op for them.
            isReturnDefaultValues = true
            all { it.useJUnitPlatform() }
        }
    }

    lint {
        // androidx.lifecycle's NonNullableMutableLiveDataDetector crashes during lint
        // analysis with the current AGP/Kotlin combo (IncompatibleClassChangeError on
        // KaCallableMemberCall — the detector was compiled against a different version of
        // the Kotlin compiler analysis API than what AGP ships). This app doesn't use
        // MutableLiveData, so the detector has nothing to do. Disable it and don't fail the
        // build on lint-internal errors that fall outside our actual code.
        disable += setOf("NullSafeMutableLiveData")
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    // Explicit compose.foundation — material3 used to pull it in transitively but
    // we now reach into the gesture-detector / pointer-input API surface for the
    // touch-drag slider on the vertical tape meter, which lives in foundation only.
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // material-icons-extended dropped: this app uses hand-drawn Compose Canvas
    // glyphs (HistoryChartGlyph, AssistMicGlyph, SettingsCogGlyph, etc.) for chrome,
    // so the ~3 MB icon set was carrying nothing at runtime.
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    // LeakCanary auto-installs on debug builds; surfaces a heap-dump-backed
    // notification when an Activity / Fragment / ViewModel leaks. Zero release
    // impact (debugImplementation = scoped to the debug variant only).
    debugImplementation(libs.leakcanary)

    // androidx.webkit: pulls in WebSettingsCompat so we can honour algorithmic
    // darkening on the Lovelace fallback (API 33+) without a manual SDK check.
    implementation(libs.androidx.webkit)
    // androidx.tracing: trace() blocks let us wrap App.onCreate + AppGraph wiring
    // so cold-start profiling tools (Perfetto, simpleperf) can show our intent
    // instead of opaque app code regions. Tracing is a no-op in release if R8
    // determines no tracing client is attached.
    implementation(libs.androidx.tracing)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.test.junit.jupiter)
    testImplementation(libs.test.junit.jupiter.params)
    testImplementation(libs.test.turbine)
    testImplementation(libs.test.truth)
    testImplementation(libs.test.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testRuntimeOnly(libs.junit.vintage)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

fun gitSha(): String = try {
    val proc = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir).redirectErrorStream(true).start()
    proc.inputStream.bufferedReader().readText().trim().ifEmpty { "dev" }
} catch (_: Exception) { "dev" }

/**
 * Default for local dev: a fixed 100M floor + minutes since 2020-01-01 UTC. The 100M
 * floor exists purely so this scheme produces versionCodes that are strictly larger
 * than every previously-shipped legacy YYYYMMDD-as-Int value (a 2026 release was
 * 20_260_513; ten-million-range vs. one-hundred-million-range means upgrade-on-top-of
 * never trips Android's "version code is older or same" rejection, which the OS
 * surfaces as the confusing "App not installed as package appears to be invalid"
 * message). Floor + minutes-since-epoch stays strictly monotonic with wall-clock
 * time and fits inside Int range until roughly year 4000 — plenty of runway.
 */
fun defaultVersionCode(): String {
    // 100M floor (see comment above) + minutes since 2020-01-01 UTC. Every legacy
    // YYYYMMDD-as-Int we ever shipped fits well below 100M (max plausible:
    // 99_991_231 in year 9999), so adding this floor guarantees strict monotonic
    // upgrade no matter which scheme was used for the previously-installed APK.
    val floor = 100_000_000L
    val epoch = LocalDateTime.of(2020, 1, 1, 0, 0)
    val now = LocalDateTime.now(ZoneOffset.UTC)
    val minutes = Duration.between(epoch, now).toMinutes()
    return (floor + minutes).coerceAtLeast(1).toString()
}

/**
 * Default for local dev: `YYYY.MM.DD.HHmm` in UTC. Same-day reships then display
 * distinct, human-readable versions (2026.05.13.1430 vs 2026.05.13.1820) instead of
 * Android adding a transparent letter suffix when two installs collide.
 */
fun defaultVersionName(): String =
    LocalDateTime.now(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy.MM.dd.HHmm"))
