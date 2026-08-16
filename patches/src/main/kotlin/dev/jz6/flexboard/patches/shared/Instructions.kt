package dev.jz6.flexboard.patches.shared

import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Small shape predicates shared by the scrub patches.
 *
 * Anchoring on *references* — a field descriptor, a method descriptor, a register range — rather
 * than on mnemonics is deliberate. `tools/apk/dis.py` prints arithmetic and conversion opcodes as
 * family placeholders (`binop2addrbb` for `add-long/2addr`), so a mnemonic read out of a dump is
 * not necessarily dexlib2's name for it, and asserting a guessed one has already produced a false
 * patch failure. References are exact in both tools.
 */

/** Normalised opcode name, e.g. `IGET_WIDE`, for when the opcode really is the anchor. */
internal fun Instruction.opcodeName(): String =
    opcode.name.uppercase().replace('-', '_').replace('/', '_')

/** True when this instruction reads or writes exactly the given field, e.g. `Lpvs;->a:I`. */
internal fun Instruction.usesField(descriptor: String): Boolean =
    ((this as? ReferenceInstruction)?.reference as? FieldReference)?.toString() == descriptor

/** True when this instruction invokes exactly the given method descriptor. */
internal fun Instruction.callsMethod(descriptor: String): Boolean =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == descriptor

/**
 * The register at [offset] in an invoke's argument list — `this` at 0, and a wide argument
 * occupying one slot at its low half. Handles both encodings: `35c` packs up to five registers into
 * nibbles, `3rc` gives a consecutive range.
 *
 * Reading arguments off the invoke itself is the most robust anchor available in these
 * constructors: it survives register reallocation, and it does not depend on knowing which
 * conversion opcode produced the value.
 */
internal fun Instruction.invokeRegisterAt(offset: Int): Int {
    val count = invokeRegisterCount()
    check(offset < count) { "Offset $offset is out of range for a $count-register invoke" }
    (this as? RegisterRangeInstruction)?.let { return it.startRegister + offset }
    val packed = this as? FiveRegisterInstruction
        ?: error("Expected an invoke to read register $offset from, found `${opcode.name}`")
    return when (offset) {
        0 -> packed.registerC
        1 -> packed.registerD
        2 -> packed.registerE
        3 -> packed.registerF
        else -> packed.registerG
    }
}

/** Number of registers an invoke passes, across both the `35c` and `3rc` encodings. */
internal fun Instruction.invokeRegisterCount(): Int =
    (this as? RegisterRangeInstruction)?.registerCount
        ?: (this as? FiveRegisterInstruction)?.registerCount
        ?: error("Not an invoke: `${opcode.name}`")

/**
 * Index of the single instruction invoking [descriptor], failing loudly when there is not exactly
 * one. Every call site in these patches genuinely expects one, and "silently patched the wrong one
 * of two" is the failure mode worth spending an assertion on.
 */
internal fun List<Instruction>.indexOfSoleCall(descriptor: String, context: String): Int {
    val matches = withIndex().filter { (_, instruction) -> instruction.callsMethod(descriptor) }
    check(matches.size == 1) {
        "Expected exactly one call to $descriptor in $context, found ${matches.size}"
    }
    return matches.single().index
}
