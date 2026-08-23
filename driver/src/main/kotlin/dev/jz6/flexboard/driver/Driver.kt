package dev.jz6.flexboard.driver

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherResult
import app.morphe.patcher.patch.PatchLoader
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/**
 * Applies a built patch bundle to the stock APK exactly the way Morphe Manager does on a phone.
 *
 * Usage:
 *   ./gradlew :driver:run --args="gboard.apk patches-1.4.0-dev.1.mpp /tmp/patched.apk"
 *
 * Exit 0 means every patch executed, dexes compiled, and arsclib rebuilt the full resource
 * table — the entire pipeline that failed on-device for dev.3 through dev.5. The output APK is
 * unsigned and lacks the merged extension dex; proving the pipeline is the point.
 */
private fun main0(args: Array<String>): Int {
    if (args.size < 2) {
        System.err.println("usage: driver <base.apk> <bundle.mpp> [out.apk]")
        return 2
    }
    val base = File(args[0])
    val bundle = File(args[1])
    val out = File(args.getOrElse(2) { "patched.apk" })
    if (!base.isFile) return fail("base apk not found: $base")
    if (!bundle.isFile) return fail("bundle not found: $bundle")

    // The patcher's progress goes through java.util.logging; without a handler it vanishes on
    // the desktop JVM and the run looks hung for a minute.
    val rootLogger = Logger.getLogger("")
    rootLogger.level = Level.INFO
    if (rootLogger.handlers.none { it is ConsoleHandler }) {
        rootLogger.addHandler(ConsoleHandler().apply { level = Level.INFO })
    }

    // initializeTemporaryFilesDirectories() deleteRecursively()es this at decode time — a
    // dedicated throwaway dir, never anything shared.
    val work = System.getenv("FLEXBOARD_CACHE")?.let { File(it, "driver/work") }
        ?: File(System.getProperty("user.home"), ".cache/flexboard/driver/work")
    val config = PatcherConfig(apkFile = base, temporaryFilesPath = work)

    Patcher(config).use { patcher ->
        val patches = PatchLoader.Jar(setOf(bundle))
        System.err.println("driver: ${patches.size} patches in $bundle")
        patcher += patches

        val results = runBlocking { patcher().toList() }
        val failures = results.filter { it.exception != null }
        if (failures.isNotEmpty()) {
            for (failure in failures) {
                System.err.println(
                    "patch failed: ${failure.patch.name}\n" +
                        (failure.exception!!.stackTraceToString().lineSequence().take(25)
                            .joinToString("\n")),
                )
            }
            return fail("${failures.size} of ${results.size} patches failed")
        }
        System.err.println("driver: ${results.size} patches applied; compiling output")

        val result = try {
            patcher.get()
        } catch (e: Exception) {
            e.printStackTrace()
            return fail("compile stage failed (this is where dev.3/dev.4 died)")
        }
        writePatchedApk(base, result, out)
    }
    System.err.println("driver: OK → $out (unsigned, extension dex not merged — proof only)")
    return 0
}

private fun fail(message: String): Int {
    System.err.println("driver: FAIL: $message")
    return 1
}

/**
 * Reassembles the APK: patched dex files replace the base's, the compiled resources.apk
 * contributes `AndroidManifest.xml`, `resources.arsc` and the `res/` tree, `otherResources` land as
 * extra entries, signatures are dropped (the APK is unsigned by construction), and base entries
 * the pipeline deleted are omitted. [PatcherResult.PatchedResources.doNotCompress] entries are
 * stored uncompressed the way the base APK carries them.
 */
private fun writePatchedApk(base: File, result: PatcherResult, out: File) {
    out.parentFile?.mkdirs()
    val doNotCompress = result.resources.doNotCompress
    val deleted = result.resources.deleteResources.toSet()

    ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zout ->
        val written = mutableSetOf<String>()

        fun put(name: String, bytes: ByteArray, compress: Boolean) {
            if (!written.add(name)) return
            val entry = ZipEntry(name)
            if (name in doNotCompress || !compress) {
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = CRC32().also { it.update(bytes) }.value
            } else {
                entry.method = ZipEntry.DEFLATED
            }
            zout.putNextEntry(entry)
            zout.write(bytes)
            zout.closeEntry()
        }

        for (dex in result.dexFiles) {
            put(dex.name, dex.stream.readBytes(), compress = false)
        }

        result.resources.resourcesApk?.let { resourcesApk ->
            ZipFile(resourcesApk).use { rz ->
                for (entry in rz.entries()) {
                    val name = entry.name
                    if (name == "AndroidManifest.xml" || name == "resources.arsc" ||
                        name.startsWith("res/")
                    ) {
                        put(name, rz.getInputStream(entry).readBytes(),
                            compress = entry.method == ZipEntry.DEFLATED)
                    }
                }
            }
        }

        result.resources.otherResources?.walkTopDown()?.filter { it.isFile }?.forEach { file ->
            val rel = file.relativeTo(result.resources.otherResources!!).invariantSeparatorsPath
            put(rel, file.readBytes(), compress = true)
        }

        ZipFile(base).use { baseZip ->
            for (entry in baseZip.entries()) {
                val name = entry.name
                if (name in written || name in deleted) continue
                // Drop signature artifacts only — META-INF/services/* and friends are loadable
                // resources for libraries inside the app, not signing metadata.
                if (name == "META-INF/MANIFEST.MF" ||
                    name.matches(Regex("META-INF/[^/]+\\.(SF|RSA|DSA|EC)"))
                ) continue
                if (name.matches(Regex("classes[0-9]*\\.dex"))) continue
                if (name == "AndroidManifest.xml" || name == "resources.arsc") continue
                if (name.startsWith("res/") && result.resources.resourcesApk != null) continue
                put(name, baseZip.getInputStream(entry).readBytes(),
                    compress = entry.method == ZipEntry.DEFLATED)
            }
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(main0(args))
}
