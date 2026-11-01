import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

// Shown at the top of every screen, so a test on the phones can tell which build is installed.
fun git(vararg args: String): String? = runCatching {
    providers.exec { commandLine("git", *args) }.standardOutput.asText.get().trim()
}.getOrNull()

// A release build is signed with the key named in local.properties (gitignored), see docs/setup.md §2.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "net.rpb.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.rpb.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        val dirty = if (git("status", "--porcelain", "--untracked-files=no").isNullOrEmpty()) "" else "+"
        buildConfigField("int", "BUILD_NUMBER", git("rev-list", "--count", "HEAD") ?: "0")
        buildConfigField("String", "BUILD_COMMIT", "\"${git("rev-parse", "--short", "HEAD") ?: "unknown"}$dirty\"")
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }

    signingConfigs {
        localProperties.getProperty("rpb.keystore")?.let { keystore ->
            create("release") {
                storeFile = rootProject.file(keystore)
                storePassword = localProperties.getProperty("rpb.keystorePassword")
                keyAlias = localProperties.getProperty("rpb.keyAlias")
                keyPassword = localProperties.getProperty("rpb.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Netty / Tink / serialization would need keep rules before minifying; a release build is only
            // non-debuggable and signed (ADR 0024).
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

// core uses the JVM Tink artifact; on Android use the Android build of the same library.
configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("com.google.crypto.tink:tink")).using(module("com.google.crypto.tink:tink-android:${libs.versions.tink.get()}"))
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)

    implementation(libs.zxing.embedded)
    implementation(libs.zxing.core)
}
