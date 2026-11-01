// Platform-independent logic shared by Device and Controller modes:
// crypto, protocol, relay, 7-segment reader, and the device/controller state machines.
// Pure JVM so everything here is unit-testable without an emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.cbor)
    // Diagnostics files (ADR 0023); the app stores its settings as JSON too.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tink)
    implementation(libs.hivemq.client)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.moquette)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}
