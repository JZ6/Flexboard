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
    // Use the same patcher/dexlib2 versions as the bundle and :driver.
    implementation("app.morphe:morphe-patcher:${libs.versions.morphe.patcher.get()}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // The helpers speak dexlib2 directly; compile against the plugin's pinned smali fork.
    implementation("com.github.MorpheApp.smali:smali:${libs.versions.smali.get()}")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

sourceSets {
    named("main") {
        kotlin.srcDir("../patches/src/main/kotlin")
        // Only `shared/`, plus the tests. Feature emitters need a real BytecodePatchContext and
        // a matched Gboard APK to execute; the pure helpers here need only patcher and dexlib2.
        kotlin.include(
            "dev/jz6/flexboard/patches/shared/**",
            // Every test file in this module's own root, rather than naming them one at a time --
            // adding a test should not require editing the build.
            "dev/jz6/flexboard/patches/*Tests.kt",
        )
    }
}

application {
    mainClass.set("dev.jz6.flexboard.patches.PatchTestsKt")
}
