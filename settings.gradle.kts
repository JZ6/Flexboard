rootProject.name = "flexboard"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/MorpheApp/registry")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
        maven { url = uri("https://jitpack.io") }
    }
}

plugins {
    id("app.morphe.patches") version "1.3.3"
}

// The compile-time stand-in for Gboard's settings fragment base class. Lives outside
// `extensions/` on purpose: the Morphe settings plugin treats every directory there as an
// extension module. See stubs/build.gradle.kts and the stub class's own Javadoc.
include(":stubs")

// Local-only compile proof of the extension with plain javac and no Android SDK — its sources
// plus hand-written android.* stubs. CI's real extension build does not touch this module; see
// extension-check/build.gradle.kts.
include(":extension-check")

// Applies a built bundle to the stock APK on the desk — Morphe Manager's patch pipeline, run
// locally. See driver/ and docs/development.md's check matrix.
include(":driver")
