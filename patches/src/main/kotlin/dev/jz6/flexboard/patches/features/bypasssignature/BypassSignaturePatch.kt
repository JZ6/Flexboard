package dev.jz6.flexboard.patches.features.bypasssignature

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD

/**
 * `Lrpv;->a` hashes the calling package's signing certificate and compares it against three
 * baked-in digests. A patched build is re-signed, so it fails, and the features gated on that
 * check stop working.
 *
 * The method has three exits — unknown package, digest matched, digest did not match — and all
 * three are forced to return true.
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

    execute {
        SignatureCheckFingerprint.method.forceSignatureChecksToPass()
    }
}

object SignatureCheckFingerprint : Fingerprint(
    definingClass = "Lrpv;",
    name = "a",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    returnType = "Z",
)

private const val SIGNATURE_CHECK = "Lrpv;->a(Landroid/content/Context;Ljava/lang/String;)Z"
private const val DIGEST_METHOD = "Lrpv;->c(Landroid/content/Context;Ljava/lang/String;)[B"
private const val ARRAYS_EQUALS = "Ljava/util/Arrays;->equals([B[B)Z"

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
    val registerCount = implementation?.registerCount
        ?: error("$SIGNATURE_CHECK has no implementation")
    check(registerCount == EXPECTED_REGISTER_COUNT) {
        "$SIGNATURE_CHECK has $registerCount registers, expected $EXPECTED_REGISTER_COUNT"
    }
    check(instructions.count { it.callsMethod(DIGEST_METHOD) } == 1) {
        "Expected exactly one digest call in $SIGNATURE_CHECK"
    }
    check(instructions.count { it.callsMethod(ARRAYS_EQUALS) } == 1) {
        "Expected exactly one digest comparison in $SIGNATURE_CHECK"
    }
    EXPECTED_FIELDS.forEach { descriptor ->
        check(instructions.count { it.readsField(descriptor) } == 1) {
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

private fun Instruction.opcodeName(): String =
    opcode.name.uppercase().replace('-', '_').replace('/', '_')

private fun Instruction.callsMethod(descriptor: String): Boolean =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == descriptor

private fun Instruction.readsField(descriptor: String): Boolean =
    ((this as? ReferenceInstruction)?.reference as? FieldReference)?.toString() == descriptor
