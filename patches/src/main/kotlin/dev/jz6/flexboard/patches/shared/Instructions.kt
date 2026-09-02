package dev.jz6.flexboard.patches.shared

import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

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

/**
 * The full descriptor of the field this instruction accesses.
 *
 * The counterpart to [usesField], for the cases where the field's name is an *output*: where the
 * instruction was located by its shape and what it touches is the thing being discovered. Pinning
 * a name and matching on it cannot survive a build that moves the letter onto a different member.
 */
internal fun Instruction.fieldDescriptor(): String =
    ((this as? ReferenceInstruction)?.reference as? FieldReference)?.toString()
        ?: error("Not a field access: `${opcode.name}`")

/**
 * The field this instruction accesses, or `null` when it accesses none.
 *
 * The nullable counterpart to [fieldDescriptor], for filtering a body by what a field *is* — its
 * type or its defining class — rather than by what it is called.
 */
internal fun Instruction.fieldReferenceOrNull(): FieldReference? =
    (this as? ReferenceInstruction)?.reference as? FieldReference

/**
 * The string this instruction loads, or `null` when it loads none.
 *
 * String literals are the one thing R8 does not rename, which makes them the strongest anchors in
 * this project — Gboard's own log formats and its generated builders' "missing required properties"
 * text both survive verbatim into the shipped dex.
 */
internal fun Instruction.stringOrNull(): String? =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/** True when this instruction invokes exactly the given method descriptor. */
internal fun Instruction.callsMethod(descriptor: String): Boolean =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == descriptor

/**
 * The descriptors this method calls, in body order. `null`-implementing (abstract) methods come
 * back as the empty list — call sites that assumed an implementation have a `check` downstream
 * that notices.
 */
internal fun com.android.tools.smali.dexlib2.iface.Method.calledDescriptors(): List<String> =
    implementation?.instructions?.toList().orEmpty()
        .mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() }

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
 * The register this instruction *writes*, or null when it writes none.
 *
 * Reading `registerA` directly is not the same thing and gets liveness checks backwards: for
 * `move`/`iget`/`const` it is the destination, but for `iput`, `if-eqz`, `return` and `throw` it is
 * a source. dexlib2 already knows which is which — `Opcode.setsRegister()` is the flag the
 * assembler itself uses — so ask it rather than enumerating mnemonics.
 *
 * Wide destinations occupy `registerA` and `registerA + 1`; callers checking whether a register was
 * clobbered need [destinationRegistersOrEmpty] for those.
 */
internal fun Instruction.destinationRegisterOrNull(): Int? =
    if (opcode.setsRegister()) (this as? OneRegisterInstruction)?.registerA else null

/** Every register this instruction writes, counting the second word of a wide destination. */
internal fun Instruction.destinationRegistersOrEmpty(): List<Int> {
    val first = destinationRegisterOrNull() ?: return emptyList()
    return if (opcode.setsWideRegister()) listOf(first, first + 1) else listOf(first)
}

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
