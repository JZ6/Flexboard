plugins {
    kotlin("jvm")
    application
}

// Unit tests for the patch helpers, on a desktop JVM with no Android SDK.
//
// A separate module rather than `patches/src/test`, and the reason is the SDK. The :patches module
// depends on :extensions:extension, so *its* test source set inherits that dependency and
// compileTestKotlin fails on "SDK location not found" before a line of test code runs.
//
// So this borrows the same trick :extension-check uses for the extension: pull the real sources in
// with srcDir, so there is exactly one copy, and compile them here against the patcher alone. Both
// the sources and the tests land in one compilation, which is also what keeps `internal` visible —
// the helpers do not have to be widened to be tested.
repositories {
    mavenCentral()
    google()
    maven { url = uri("https://jitpack.io") }
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
    // Matching :driver, which resolves the same patcher without an SDK.
    implementation("app.morphe:morphe-patcher:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // The patcher's published POM carries no transitive coordinates, and the helpers speak
    // dexlib2 types directly.
    implementation("com.github.revanced:smali:d92701d947")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

sourceSets {
    named("main") {
        kotlin.srcDir("../patches/src/main/kotlin")
        // Only `shared/`, plus the tests. The feature patches import `android.*` through the
        // patches plugin's own classpath, which this module deliberately does not reproduce —
        // and they are not what is under test. The helpers in shared/ are, and they reference
        // nothing outside the patcher and dexlib2.
        kotlin.include(
            "dev/jz6/flexboard/patches/shared/**",
            "dev/jz6/flexboard/patches/PatchTests.kt",
        )
    }
}

application {
    mainClass.set("dev.jz6.flexboard.patches.PatchTestsKt")
}
