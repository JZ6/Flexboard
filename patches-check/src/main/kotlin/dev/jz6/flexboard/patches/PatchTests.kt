package dev.jz6.flexboard.patches

import dev.jz6.flexboard.patches.shared.PACKED_INVOKE_REGISTER_LIMIT
import dev.jz6.flexboard.patches.shared.sole
import dev.jz6.flexboard.patches.shared.validateScratchRegisters

/**
 * Tests for the patch helpers, on a desktop JVM with no patcher run and no Gboard.
 *
 * Forty-four Kotlin files shipped with no test of any kind, while every check built around them
 * looked at Python or at the APK. The bugs were not in the APK reading. They were here:
 *
 *  - `findInstanceField` could not see a static field, so a patch could never apply
 *  - `handoverFor` computed liveness with a linear walk, so a fix emitted nothing
 *  - `flagHolderClinit` used a fingerprint matching no `<clinit>`, so two builds failed
 *
 * Covered is what can be reached without a `BytecodePatchContext`: register validation and the
 * single-match helper. Both are called from every emitter, and a wrong answer from either is a
 * broken keyboard rather than a compile error.
 *
 * Assertions and a `main`, matching `:extension-check` and the Python suite — the reasoning is in
 * this module's `build.gradle.kts`.
 */

/**
 * The floor for the check count, for the same reason `preflight.py` has one.
 *
 * Adding a test file to this module compiles it, but its entry function still has to be called from
 * [main] by hand -- and nothing notices if you forget. Drop `resolveTests()` from the list and the
 * suite prints a smaller number, exits zero, and the gate lane goes green. A count that silently
 * fell is the same failure as a dex-derived list that came back empty.
 */
private const val MINIMUM_CHECKS = 89

internal var checks = 0
internal var failures = 0

fun main() {
    scratchRegisters()
    soleHelper()
    instructionTests()
    resolveTests()

    println("$checks checks, $failures failed")
    if (checks < MINIMUM_CHECKS) {
        println(
            "  only $checks checks ran, fewer than the $MINIMUM_CHECKS expected — a test file is " +
                "compiled but never called, or a group was dropped from main()",
        )
    }
    if (failures > 0 || checks < MINIMUM_CHECKS) {
        kotlin.system.exitProcess(1)
    }
}

private fun scratchRegisters() {
    accepts("a clean scratch set") {
        validateScratchRegisters(listOf(3, 5, 6), listOf(13, 2, 4), "T")
    }

    // Two emissions writing the same slot silently clobber each other.
    rejects("a duplicate slot", "not distinct") {
        validateScratchRegisters(listOf(3, 5, 3), emptyList(), "T")
    }

    // `0.0.1-dev.1` passed `this` where a Context was required; `avoid` is what that added.
    rejects("a collision with a register that must survive", "collide") {
        validateScratchRegisters(listOf(3, 13), listOf(13), "T")
    }

    // A `35c` invoke encodes each register in four bits, so v16 assembles and fails to verify.
    rejects("a register above the nibble ceiling", "nibble") {
        validateScratchRegisters(listOf(PACKED_INVOKE_REGISTER_LIMIT), emptyList(), "T")
    }
    accepts("the highest register that still fits") {
        validateScratchRegisters(listOf(PACKED_INVOKE_REGISTER_LIMIT - 1), emptyList(), "T")
    }

    // Negative slots passed all three of the original checks.
    rejects("a negative slot", "negative") {
        validateScratchRegisters(listOf(-1), emptyList(), "T")
    }

    // The frame is a second ceiling, usually lower than the encoding one: v12 in a ten-register
    // method assembles and fails to verify.
    rejects("a register outside the method's frame", "frame") {
        validateScratchRegisters(listOf(12), emptyList(), "T", registerCount = 10)
    }
    accepts("a register inside the frame") {
        validateScratchRegisters(listOf(9), emptyList(), "T", registerCount = 10)
    }
}

private fun soleHelper() {
    equal("returns the only element", "x", listOf("x").sole { "expected one, got $it" })

    rejects("an empty collection", "got 0") {
        emptyList<String>().sole { "expected one, got $it" }
    }
    rejects("more than one", "got 2") {
        listOf("x", "y").sole { "expected one, got $it" }
    }

    // The count is handed to the message so a failure can say what it found. "Silently took the
    // first of two" is the failure this helper exists to stop.
    var reported = -1
    runCatching { listOf(1, 2, 3).sole { reported = it; "boom" } }
    equal("the message is handed the real count", "3", reported.toString())
}

// ------------------------------------------------------------------ harness

internal fun accepts(what: String, body: () -> Unit) {
    checks++
    runCatching(body).onFailure {
        failures++
        println("  FAIL $what: rejected with ${it.message}")
    }
}

internal fun rejects(what: String, expectedFragment: String, body: () -> Unit) {
    checks++
    val outcome = runCatching(body)
    val message = outcome.exceptionOrNull()?.message
    when {
        outcome.isSuccess -> {
            failures++
            println("  FAIL $what: accepted, expected a failure mentioning '$expectedFragment'")
        }
        message?.contains(expectedFragment, ignoreCase = true) != true -> {
            failures++
            println(
                "  FAIL $what: rejected, but the message does not mention '$expectedFragment' " +
                    "— it said: $message",
            )
        }
    }
}

internal fun equal(what: String, expected: String, actual: String) {
    checks++
    if (expected != actual) {
        failures++
        println("  FAIL $what: expected '$expected', got '$actual'")
    }
}
