import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "io.github.imix667.gboardplus"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.imix667.gboardplus"
        minSdk = 35
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0-game2"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cFlags += "-std=c11"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile", "../keystore.jks"))
                storePassword = keystoreProperties.getProperty("storePassword", "")
                keyAlias = keystoreProperties.getProperty("keyAlias", "")
                keyPassword = keystoreProperties.getProperty("keyPassword", "")
            } else {
                storeFile = file(System.getenv("KEYSTORE_FILE") ?: "../keystore.jks")
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            // Signed with the release key so a debug build installs *over* a release build
            // without uninstalling (which would drop the LSPosed scope + prefs).
            isMinifyEnabled = false
            isShrinkResources = false
            // Prefer the release key (installs over a release build without dropping the
            // LSPosed scope); fall back to the debug key when no keystore is configured.
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// ── Gamepad daemon (cpp/gamepadd.c) ──────────────────────────────────────
// CMake builds the daemon as a plain executable named "gamepadd" (no .so
// suffix, so AGP does not stage it into jniLibs). The app needs it in
// assets/ instead: MainActivity extracts assets/gamepadd to filesDir and
// starts it via su. Copy the CMake output into a generated assets dir.
val gamepadAssetsDir = layout.buildDirectory.dir("generated/gamepadAssets")
android.sourceSets.getByName("main").assets.srcDir(gamepadAssetsDir)

val copyGamepadDaemon = tasks.register<Copy>("copyGamepadDaemon") {
    dependsOn("externalNativeBuildDebug", "externalNativeBuildRelease")
    from(fileTree(layout.buildDirectory.dir("intermediates/cmake")) {
        include("**/gamepadd")
    })
    into(gamepadAssetsDir)
    // debug/ and release/ obj dirs both yield a "gamepadd" — flatten to
    // assets/gamepadd (identical source, either build variant is fine).
    eachFile { relativePath = org.gradle.api.file.RelativePath(true, name) }
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
}
tasks.matching { it.name == "mergeDebugAssets" || it.name == "mergeReleaseAssets" }
    .configureEach { dependsOn(copyGamepadDaemon) }

dependencies {
    compileOnly(libs.xposed.api)
    implementation(libs.material)
    implementation(libs.appcompat)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.activity.compose)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
