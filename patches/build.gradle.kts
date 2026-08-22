group = "dev.jz6.flexboard"

patches {
    // Baked into the .mpp and shown as the source name in Morphe Manager, so it only changes when
    // a new bundle is published. A distinct name is also required rather than preferred: see the
    // project name restriction in NOTICE.
    about {
        name = "Flexboard"
        description = "Swipe anywhere on the keyboard to delete the previous word."
        source = "https://github.com/JZ6/Flexboard"
        author = "JZ6"
        contact = "https://github.com/JZ6/Flexboard/issues"
        website = "https://github.com/JZ6/Flexboard"
        license = "GPLv3"
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

// The footer label on the Flexboard settings screen shows the bundle's version, so the about
// text Morphe shows is the text the settings screen shows — the two can never disagree by
// construction. `version` arrives via gradle.properties, and `expand` substitutes it into
// `flexboard_version.txt` during :patches:processResources.
tasks.named<ProcessResources>("processResources") {
    filesMatching("flexboard_version.txt") {
        expand(mapOf("VERSION" to project.version.toString()))
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }
}
