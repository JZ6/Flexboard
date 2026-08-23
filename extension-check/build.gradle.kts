plugins {
    `java-library`
}

// Local compile proof for the extension, without the Android SDK.
//
// The :extensions:extension module needs an SDK because it packages a dex — but *checking* it
// needs only javac. This module compiles the extension's own sources (pulled in via srcDir, so
// there is exactly one copy) against two stub sets: Gboard's host surface from :stubs, and the
// android.* surface written by hand in src/main/java/android — only the members the extension
// actually calls. A typo, a missing import, a renamed method: fails here in seconds, locally,
// instead of after a push in CI.
//
// CI never sees the android.* stubs: :extensions:extension compiles against the real android.jar
// and does not depend on this module.
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

sourceSets {
    named("main") {
        java.srcDir("../extensions/extension/src/main/java")
    }
}

dependencies {
    compileOnly(project(":stubs"))
}
