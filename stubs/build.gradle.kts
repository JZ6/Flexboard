plugins {
    `java-library`
}

// The extension compiles against Gboard's obfuscated preference classes without bundling them.
// :extensions:extension consumes these stubs compileOnly; on a device the real classes come from
// Gboard's APK. :extension-check adds them at test runtime only for local JVM verification.
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
