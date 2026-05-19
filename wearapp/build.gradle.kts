import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.github.itskenny0.r1ha.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.github.itskenny0.r1ha.wear"
        minSdk = 30    // Wear OS 3 = API 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-wear"

        buildConfigField("String", "SOURCE_URL", "\"https://github.com/itskenny0/Rabbit-R1-HA\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // ── Shared source strategy ───────────────────────────────────────────────
    //
    // The phone :app module owns the entire core/ package (HA repository, prefs,
    // WheelInput, etc.). Rather than duplicating those ~35 files, we point this
    // module's Kotlin source root at the same directory so the wear app gets the
    // same compiled classes at no duplication cost.
    //
    // Phone-only classes (MainActivity, App, HaQuickTileService, OAuthWebView,
    // AppUpdater, …) end up in the wear APK's classpath but are never reachable
    // from the wear manifest; they are effectively dead code and will be removed
    // by R8 on release builds.
    //
    // TODO: The clean long-term solution is to extract a :core Gradle module that
    //       both :app and :wearapp depend on, eliminating the srcDirs trick.
    //       See docs/architecture.md for the migration plan.
    sourceSets {
        named("main") {
            kotlin.setSrcDirs(
                listOf(
                    "src/main/kotlin",
                    "${rootProject.projectDir}/app/src/main/kotlin",
                )
            )
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
        )
    }
}

dependencies {
    // ─── Wear OS Compose ────────────────────────────────────────────────────
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    debugImplementation(libs.wear.tooling.preview)

    // ─── Shared with :app (needed to compile the shared core source set) ────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // navigation-compose is referenced by the phone AppNavGraph that lives in
    // the shared srcDir; adding it here keeps compilation clean.
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    // core-splashscreen is referenced by the phone App.kt in the shared srcDir.
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
}
