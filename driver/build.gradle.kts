plugins {
    // No version: the Morphe settings plugin already put the Kotlin plugin on the classpath,
    // so an unversioned request resolves to that same artifact.
    kotlin("jvm")
    application
}

// The driver: applies a built patch bundle to the stock Gboard APK on the desk, the same calls
// Morphe Manager makes on the phone. This is the only gate that executes the patches themselves
// — every other lane inspects sources, resources or dex from the outside. A patch-time crash
// (the dev.3/dev.4 class of bug) shows up here in about a minute with the real stack and the
// real filename, instead of after CI plus an install plus a phone.
//
// The produced APK is unsigned and does not carry the merged extension dex — it exists to prove
// the pipeline succeeds, not to be installed.
repositories {
    mavenCentral()
    google()
    maven { url = uri("https://jitpack.io") }
    // morphe-patcher lives here; same credential convention as settings.gradle.kts'
    // pluginManagement block — ~/.gradle/gradle.properties holds gpr.user/gpr.key locally.
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/MorpheApp/registry")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    // Same version gradle/libs.versions.toml pins for the patches build; bump them together.
    implementation("app.morphe:morphe-patcher:1.8.0")
    // Explicit: morphe-patcher's published POM carries no transitive coordinates.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

application {
    mainClass.set("dev.jz6.flexboard.driver.DriverKt")

    // gboard.apk is a symlink into this directory in this repo; a sensible default
    // keeps two-argument use down to just the bundle.
    applicationDefaultJvmArgs = emptyList()
}
