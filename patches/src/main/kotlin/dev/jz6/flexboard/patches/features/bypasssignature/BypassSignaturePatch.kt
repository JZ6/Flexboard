package dev.jz6.flexboard.patches.features.bypasssignature

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.assertRegisterCount
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.callsMethod
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.usesField

/**
 * Forces Gboard's own signature check to pass.
 *
 * `Lrpv;->a` computes the SHA-256 of the signing certificate of the package it is handed and
 * compares it byte-for-byte against three baked-in digests. A re-signed build matches none of
 * them, so on an unpatched Flexboard the check returns false.
 *
 * ## It gates nothing, and this file used to claim otherwise
 *
 * The previous version of this comment said "the features gated on that check stop working". That
 * is wrong, and it is the kind of wrong that gets a patch either dropped as dead weight or kept
 * for imagined reasons. There are exactly two callers:
 *
 *  - `Lmm;->run()` case 8, reached from `LatinApp;->e()` — `new Lmm(applicationContext, 8)`, the
 *    only construction site using that selector — on cold start, guarded by `isMainProcess`. Its
 *    entire body is the check followed by `return-void`. On failure it throws
 *    `IllegalStateException("APK is signed by unrecognized certificates: …")`. On success it does
 *    nothing whatsoever, so a failing check skips no work, because there is none to skip.
 *  - `WebDebugBridgeContentProvider;->call`, which checks the *caller* of a developer debug
 *    provider rather than Gboard itself.
 *
 * No Flexboard subsystem references `Lrpv;` at all — not the preference store, the Phenotype flag
 * suppliers, the access points bar, the scrub handlers, or the IME.
 *
 * **Tested without this patch on 2026-08-18: the keyboard still opens.** The throw lands on the
 * background executor held in `LatinApp;->c` and does not take the process down. So what this
 * patch buys is the absence of a startup exception, not the presence of any feature.
 *
 * It is kept regardless. An exception on every cold start is worth silencing even when it is
 * survivable; it is presumably reported to Google's crash telemetry; and the alternative is
 * betting that a background throw stays harmless on every device and Android version rather than
 * only on the one it happened to be tried on. The derivation below is also among the more stable
 * in the project, so the cost per Gboard bump is low.
 *
 * ## The derivation
 *
 * The method has three exits — the digest could not be computed, the digest matched, the digest
 * did not match — and all three are forced to return true.
 *
 * An obfuscated single-letter method on an unexpected build could be anything, so the bar for
 * recognising it is deliberately high: register count, the exact three return registers, the
 * digest call, the array comparison and all four field reads.
 */
@Suppress("unused")
val bypassGboardSignaturePatch = bytecodePatch(
    name = "Bypass Gboard Signature",
    description = "Bypass Gboard's signature whitelist checks and force them to pass.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        signatureCheckFingerprint().method.forceSignatureChecksToPass()
    }
}

fun signatureCheckFingerprint() = Fingerprint(
    definingClass = "Lrpv;",
    name = "a",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    returnType = "Z",
)

private const val SIGNATURE_CHECK = "Lrpv;->a(Landroid/content/Context;Ljava/lang/String;)Z"
private const val DIGEST_METHOD = "Lrpv;->c(Landroid/content/Context;Ljava/lang/String;)[B"
private const val ARRAYS_EQUALS = "Ljava/util/Arrays;->equals([B[B)Z"

/**
 * The three baked-in certificate digests, and the flag the check falls back to.
 *
 * `Lrox;->b:Z` is **not** a cached signature verdict, despite sitting in a list of signature
 * fields. It is the global test-environment flag — `Lrox;-><clinit>` copies it from `Lmvm;->a:Z`,
 * which is `Build.FINGERPRINT.equals("robolectric")` — so it is permanently false on a device, and
 * around forty unrelated places read it as a test-versus-production switch. The check reads it
 * exactly once, as the value to return when the caller's digest cannot be computed at all. It is
 * an input to the check, never an output of it, and it is listed here only because reading it is
 * part of what identifies the method.
 */
private val EXPECTED_FIELDS = listOf(
    "Lrpv;->e:[B",
    "Lrpv;->d:[B",
    "Lrpv;->c:[B",
    "Lrox;->b:Z",
)

/** The unknown-package exit, the match exit and the no-match exit, in bytecode order. */
private val EXPECTED_RETURN_REGISTERS = listOf(6, 4, 3)
private const val EXPECTED_REGISTER_COUNT = 8

/** `const/4` encodes its register in a nibble. */
private const val MAX_CONST_4_REGISTER = 15

private fun MutableMethod.forceSignatureChecksToPass() {
    assertRegisterCount(EXPECTED_REGISTER_COUNT, SIGNATURE_CHECK)
    check(instructions.count { it.callsMethod(DIGEST_METHOD) } == 1) {
        "Expected exactly one digest call in $SIGNATURE_CHECK"
    }
    check(instructions.count { it.callsMethod(ARRAYS_EQUALS) } == 1) {
        "Expected exactly one digest comparison in $SIGNATURE_CHECK"
    }
    EXPECTED_FIELDS.forEach { descriptor ->
        check(instructions.count { it.usesField(descriptor) } == 1) {
            "Expected exactly one read of $descriptor in $SIGNATURE_CHECK"
        }
    }

    val returnIndices = instructions.indices.filter { instructions[it].opcodeName() == "RETURN" }
    val returnRegisters = returnIndices.map { index ->
        (instructions[index] as? OneRegisterInstruction)?.registerA
            ?: error("RETURN at $index in $SIGNATURE_CHECK has no register")
    }
    check(returnRegisters == EXPECTED_RETURN_REGISTERS) {
        "$SIGNATURE_CHECK returns $returnRegisters, expected $EXPECTED_RETURN_REGISTERS"
    }

    // Reversed so each edit leaves the indices of the ones still to come untouched.
    returnIndices.asReversed().forEach { index ->
        val register = (instructions[index] as OneRegisterInstruction).registerA
        check(register <= MAX_CONST_4_REGISTER) {
            "RETURN register v$register cannot be forced with const/4"
        }
        replaceInstruction(index, "const/4 v$register, 0x1")
        addInstruction(index + 1, "return v$register")
    }
}
