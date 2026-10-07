package dev.jz6.flexboard.patches.shared

import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
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

/**
 * The descriptor this instruction invokes, or `null` when it invokes none.
 *
 * The nullable counterpart to [callsMethod], for asking *what* is called rather than whether one
 * particular thing is — a guard that has to notice any emission, not a named one.
 */
internal fun Instruction.methodDescriptorOrNull(): String? =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

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
 * The register at [offset] in an invoke's argument list — `this` at 0. Offsets count register
 * slots, so a wide argument occupies two slots, beginning at its low half. `35c` packs up to five into
 * nibbles, `3rc` gives a consecutive range.
 *
 * Reading arguments off the invoke itself is the most robust anchor available in these
 * constructors: it survives register reallocation, and it does not depend on knowing which
 * conversion opcode produced the value.
 */
internal fun Instruction.invokeRegisterAt(offset: Int): Int {
    val count = invokeRegisterCount()
    check(offset in 0 until count) { "Offset $offset is out of range for a $count-register invoke" }
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
 * Every register this instruction names as a source.
 *
 * The destination is not a read on a plain move/const/get, but *is* a source for `2addr` arithmetic.
 * Wide sources count both halves. A false read vetoes a correct patch; a missed read can corrupt a
 * live value, so distinguish these instead of treating every register operand alike.
 */
internal fun Instruction.registersRead(): List<Int> {
    val name = opcodeName()
    fun wide(r: Int) = listOf(r, r + 1)
    return when (this) {
        is FiveRegisterInstruction ->
            listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is ThreeRegisterInstruction -> {
            val a = if (opcode.setsRegister()) emptyList() else if (name.startsWith("APUT_WIDE"))
                wide(registerA) else listOf(registerA)
            val b = if (name.contains("LONG") || name.contains("DOUBLE"))
                wide(registerB) else listOf(registerB)
            val c = if ((name.contains("LONG") && !name.startsWith("SHL_") &&
                         !name.startsWith("SHR_") && !name.startsWith("USHR_")) ||
                        name.contains("DOUBLE")) wide(registerC) else listOf(registerC)
            a + b + c
        }
        is TwoRegisterInstruction -> {
            val a = if (!opcode.setsRegister() || name.endsWith("_2ADDR")) {
                if (name.startsWith("IPUT_WIDE") ||
                    (name.endsWith("_2ADDR") && opcode.setsWideRegister())) wide(registerA)
                else listOf(registerA)
            } else emptyList()
            val wideSource = name.startsWith("MOVE_WIDE") || name.startsWith("NEG_LONG") ||
                name.startsWith("NEG_DOUBLE") || name.startsWith("NOT_LONG") ||
                name.startsWith("LONG_TO") || name.startsWith("DOUBLE_TO") ||
                (name.endsWith("_2ADDR") && (name.contains("LONG") || name.contains("DOUBLE")))
            val b = if (wideSource) wide(registerB) else listOf(registerB)
            a + b
        }
        is OneRegisterInstruction -> when {
            opcode.setsRegister() -> emptyList()
            name.startsWith("RETURN_WIDE") || name.startsWith("SPUT_WIDE") -> wide(registerA)
            else -> listOf(registerA)
        }
        else -> emptyList()
    }
}

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
 * The only element, failing with [lazyMessage] when there is not exactly one. The count is passed
 * in so a message can report what it found.
 *
 * Every one of these searches genuinely expects a single hit, and "silently took the first of two"
 * is the failure worth spending an assertion on — Gboard grows near-duplicate methods between
 * releases, and a resolution that quietly picks one patches something nobody looked at. Written out
 * by hand at twenty-five sites before this existed, which is twenty-five chances to write
 * `.first()` and forget the check.
 */
internal inline fun <T> Collection<T>.sole(lazyMessage: (Int) -> String): T {
    check(size == 1) { lazyMessage(size) }
    return single()
}

/**
 * Index of the single instruction invoking [descriptor], failing loudly when there is not exactly
 * one. Every call site in these patches genuinely expects one, and "silently patched the wrong one
 * of two" is the failure mode worth spending an assertion on.
 */
internal fun List<Instruction>.indexOfSoleCall(descriptor: String, context: String): Int {
    return withIndex()
        .filter { (_, instruction) -> instruction.callsMethod(descriptor) }
        .sole { "Expected exactly one call to $descriptor in $context, found $it" }
        .index
}

/** The code-unit address of every instruction, which is what a branch's relative offset is in. */
internal fun List<Instruction>.codeAddresses(): IntArray {
    val addresses = IntArray(size)
    var pc = 0
    forEachIndexed { i, instruction ->
        addresses[i] = pc
        pc += instruction.codeUnits
    }
    return addresses
}

/**
 * The index of the instruction the `goto`/`if-*` at [index] jumps to.
 *
 * Restricted to those two families on purpose: a switch's or `fill-array-data`'s offset points at
 * a payload, not at code, and treating it as a branch target would hand an emitter a label on data.
 */
internal fun List<Instruction>.branchTargetIndex(index: Int, what: String): Int {
    val branch = getOrNull(index)
    val name = branch?.opcodeName().orEmpty()
    check(branch is OffsetInstruction && (name.startsWith("GOTO") || name.startsWith("IF_"))) {
        "$what: instruction $index (`$name`) is not a goto or if-* branch"
    }
    val addresses = codeAddresses()
    val target = addresses.indexOfFirst { it == addresses[index] + branch.codeOffset }
    check(target >= 0) { "$what: the branch at $index lands between instructions" }
    return target
}

/** Appending a block before a terminal return is safe only if no stock edge jumps past that block. */
internal fun assertTailReturnUntargeted(body: List<Instruction>, index: Int, what: String) {
    check(index in body.indices && body[index].opcodeName().startsWith("RETURN")) {
        "$what has no return at the insertion point $index"
    }
    check(body.none { it is SwitchPayload }) {
        "$what contains a switch payload; case offsets need inspection before a tail insertion"
    }
    val addresses = body.codeAddresses()
    check(body.withIndex().none { (i, instruction) ->
        instruction is OffsetInstruction && addresses[i] + instruction.codeOffset == addresses[index]
    }) { "$what has a branch into its return, which would skip the refresh block" }
}

/**
 * Refuses a build where a scratch register is read before anything writes it, **within the basic
 * block the insertion lands in**.
 *
 * A veto, never a licence, and the scope is the whole of what makes it usable. Linear order is only
 * control flow up to the next branch; past one, the next instruction in the list may not be
 * reachable at all. Scanning further does not merely fail to prove deadness -- it invents failures,
 * which is worse, because it refuses a patch that is correct. That is not hypothetical: this walked
 * past a `goto -> 123` in `ScrubMotionEventHandler->r`, read the `add-int/2addr v2, v5` eleven
 * instructions later at pc 112 -- code that jump cannot reach -- and stopped Swipe Left to Delete
 * from applying at all, on a device.
 *
 * So the walk stops at the first instruction that transfers control. That instruction's own reads
 * still count: `if-eqz v5` reads v5.
 *
 * A pass proves nothing either way. The registers come from preflight's backward analysis over the
 * real control-flow graph, which the gate runs; this is the cheap copy that makes a build which
 * moved them fail at patch time too.
 */
internal fun assertNotReadBeforeWritten(
    body: List<Instruction>,
    insertIndex: Int,
    scratch: List<Int>,
    what: String,
) {
    for (register in scratch) {
        for (index in insertIndex until body.size) {
            val instruction = body[index]
            if (register in instruction.registersRead()) {
                error(
                    "v$register is read by `${instruction.opcodeName()}` at $index before anything " +
                        "writes it, walking forward from the insertion point in $what — it carries " +
                        "a live value across the seam and cannot be scratch",
                )
            }
            if (register in instruction.destinationRegistersOrEmpty()) break
            if (instruction.transfersControl()) break
        }
    }
}

/** True when this instruction can send control somewhere other than the next one in the list. */
private fun Instruction.transfersControl(): Boolean {
    val name = opcodeName()
    return name.startsWith("GOTO") || name.startsWith("IF_") || name.startsWith("RETURN") ||
        name == "THROW" || name.endsWith("SWITCH")
}
