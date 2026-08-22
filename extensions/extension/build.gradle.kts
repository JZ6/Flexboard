extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "dev.jz6.flexboard.extension"
}

dependencies {
    // Compile-only stand-ins for Gboard classes the extension subclasses. Never dexed: the real
    // classes resolve from the Gboard DEX at patch time.
    compileOnly(project(":stubs"))
}
