package dev.jz6.flexboard.patches

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction23x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import dev.jz6.flexboard.patches.shared.booleanFlagCallIndex
import dev.jz6.flexboard.patches.shared.assertNotReadBeforeWritten
import dev.jz6.flexboard.patches.shared.assertTailReturnUntargeted
import dev.jz6.flexboard.patches.shared.branchTargetIndex
import dev.jz6.flexboard.patches.shared.callsMethod
import dev.jz6.flexboard.patches.shared.destinationRegisterOrNull
import dev.jz6.flexboard.patches.shared.destinationRegistersOrEmpty
import dev.jz6.flexboard.patches.shared.fieldDescriptor
import dev.jz6.flexboard.patches.shared.indexOfSoleCall
import dev.jz6.flexboard.patches.shared.invokeRegisterAt
import dev.jz6.flexboard.patches.shared.invokeRegisterCount
import dev.jz6.flexboard.patches.shared.methodDescriptorOrNull
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.registersRead
import dev.jz6.flexboard.patches.shared.stringOrNull
import dev.jz6.flexboard.patches.shared.usesField

/**
 * Tests for the instruction helpers, on synthetic instructions rather than Gboard's.
 *
 * These are the register-reading family, and they are why this file exists: every emitter decides
 * where to put a value by asking which registers an instruction reads and writes, and a wrong answer
 * is not a compile error. It is a keyboard that will not open, which this project shipped twice.
 *
 * Synthetic rather than read from the APK, for the same reason the Python suite builds its own
 * streams: the shapes worth testing are the ones Gboard 18.0.3 does not happen to contain. A `3rc`
 * invoke, a wide destination, a `2addr` that reads what it writes -- the gate already checks the
 * real dex, and these check the code doing the checking.
 */

private val METHOD = ImmutableMethodReference("Lpvf;", "t", listOf("Lpvi;", "I"), "V")
private val OTHER = ImmutableMethodReference("Lpvf;", "u", listOf<String>(), "V")
private val FIELD = ImmutableFieldReference("Lpvs;", "a", "I")

private fun invoke35c(vararg regs: Int, ref: ImmutableMethodReference = METHOD): Instruction =
    ImmutableInstruction35c(
        Opcode.INVOKE_VIRTUAL,
        regs.size,
        regs.getOrElse(0) { 0 }, regs.getOrElse(1) { 0 }, regs.getOrElse(2) { 0 },
        regs.getOrElse(3) { 0 }, regs.getOrElse(4) { 0 },
        ref,
    )

private fun invoke3rc(start: Int, count: Int): Instruction =
    ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, start, count, METHOD)

internal fun instructionTests() {
    encodings()
    readsAndWrites()
    references()
    soleCall()
    flagSites()
}

// ------------------------------------------------------------------ flag declaration sites

private val FLAG_FACTORY = ImmutableMethodReference("Lnxs;", "a", listOf("Ljava/lang/String;", "Z"), "Lnxp;")

private fun name(s: String): Instruction =
    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(s))

private fun factoryCall(): Instruction =
    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 0, 1, 0, 0, 0, FLAG_FACTORY)

private fun filler(): Instruction = ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2)

/**
 * Reproduces the install that broke: two `<clinit>`s naming the same flag, one of them another
 * project's merged extension.
 *
 * Morphe merges every selected bundle's extension into the dex before patches run, so a scan for
 * "every `<clinit>` mentioning this flag" is a scan over other people's code too. A user running
 * Flexboard 2.4.1 beside two other Gboard bundles hit
 * `Ldev/jason/gboardpatches/…/GboardRambler1803StockPolicy;-><clinit>`, which names
 * `enable_rambler_toolbar_at_cursor_position` and never calls the factory, and the entire patch run
 * failed on a class that was none of Flexboard's business.
 */
private fun flagSites() {
    // Gboard's real shape: the name, a constant, then the factory.
    val declaration = listOf(name("enable_x"), filler(), factoryCall())
    equal("a declaration site resolves to its factory call", "2",
        booleanFlagCallIndex(declaration, 0).toString())

    // The foreign one: the name is there, the factory is not. It calls *something* -- another
    // project's class is code, not padding -- because a predicate that accepts any invoke at all
    // passes a test built out of inert filler and fails on the install that prompted this.
    val otherCall = ImmutableInstruction35c(
        Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
        ImmutableMethodReference("Ljava/util/Set;", "of", listOf("Ljava/lang/Object;"), "Ljava/util/Set;"),
    )
    val foreign = listOf(name("enable_x"), otherCall, filler(), otherCall)
    equal("a mention that calls something else is not a site", "null",
        booleanFlagCallIndex(foreign, 0).toString())

    val nextFlag = listOf(name("a_long_flag"), otherCall, name("a_boolean_flag"), factoryCall())
    equal("the next flag's boolean factory is not borrowed", "null",
        booleanFlagCallIndex(nextFlag, 0).toString())
    equal("the real boolean flag still resolves", "3",
        booleanFlagCallIndex(nextFlag, 2).toString())

    // The window is five, and it is a real boundary rather than decoration.
    val justInside = listOf(name("enable_x")) + List(4) { filler() } + listOf(factoryCall())
    equal("a factory call at the edge of the window counts", "5",
        booleanFlagCallIndex(justInside, 0).toString())
    val justOutside = listOf(name("enable_x")) + List(5) { otherCall } + listOf(factoryCall())
    equal("one instruction beyond the window does not", "null",
        booleanFlagCallIndex(justOutside, 0).toString())

    // A name at the very end cannot be a declaration, and must not read off the end either.
    equal("a trailing name is not a site", "null",
        booleanFlagCallIndex(listOf(filler(), name("enable_x")), 1).toString())
}

// ------------------------------------------------------------------ both invoke encodings

private fun encodings() {
    val packed = invoke35c(3, 5, 6)
    equal("35c register count", "3", packed.invokeRegisterCount().toString())
    equal("35c receiver", "3", packed.invokeRegisterAt(0).toString())
    equal("35c second argument", "5", packed.invokeRegisterAt(1).toString())
    equal("35c last argument", "6", packed.invokeRegisterAt(2).toString())

    // Five is the ceiling, and the fifth lives in registerG rather than the else-branch by accident.
    val full = invoke35c(1, 2, 3, 4, 9)
    equal("35c fifth argument", "9", full.invokeRegisterAt(4).toString())

    // A range invoke is consecutive from startRegister, not packed -- reading it as 35c silently
    // returns registerC, which is zero, and the emission lands on whatever v0 happens to hold.
    val range = invoke3rc(start = 12, count = 4)
    equal("3rc register count", "4", range.invokeRegisterCount().toString())
    equal("3rc first", "12", range.invokeRegisterAt(0).toString())
    equal("3rc third", "14", range.invokeRegisterAt(2).toString())

    rejects("an offset past the end of a 35c invoke", "out of range") { packed.invokeRegisterAt(3) }
    rejects("a negative invoke offset", "out of range") { packed.invokeRegisterAt(-1) }
    rejects("an offset past the end of a 3rc invoke", "out of range") { range.invokeRegisterAt(4) }
    rejects("asking a non-invoke for its registers", "Not an invoke") {
        ImmutableInstruction11x(Opcode.MOVE_RESULT, 0).invokeRegisterCount()
    }
}

// ------------------------------------------------------------------ the register questions

private fun readsAndWrites() {
    equal("a 35c invoke reads exactly its arguments", "[3, 5, 6]", invoke35c(3, 5, 6).registersRead().toString())
    equal("a 3rc invoke reads its whole range", "[12, 13, 14, 15]", invoke3rc(12, 4).registersRead().toString())

    // Over-inclusive on purpose: add-int/2addr writes registerA and also reads it. Reporting only
    // the read-only operand would free a register that is about to be overwritten.
    equal(
        "a 2addr reads the register it also writes",
        "[1, 2]",
        ImmutableInstruction12x(Opcode.ADD_INT_2ADDR, 1, 2).registersRead().sorted().toString(),
    )
    equal(
        "a three-register op reads its two sources, not its destination",
        "[5, 6]",
        ImmutableInstruction23x(Opcode.ADD_INT, 4, 5, 6).registersRead().sorted().toString(),
    )

    val narrow = ImmutableInstruction11n(Opcode.CONST_4, 5, 0)
    equal("a plain write does not read its destination", "[]", narrow.registersRead().toString())
    equal("move-result does not read its destination", "[]",
        ImmutableInstruction11x(Opcode.MOVE_RESULT, 5).registersRead().toString())
    val putWide = ImmutableInstruction22c(
        Opcode.IPUT_WIDE, 4, 0, ImmutableFieldReference("Lx;", "v", "J"))
    equal("iput-wide reads both halves of its value", "[0, 4, 5]",
        putWide.registersRead().sorted().toString())
    accepts("a write before a later read ends the scratch veto") {
        assertNotReadBeforeWritten(listOf(narrow, ImmutableInstruction10x(Opcode.RETURN_VOID)),
            0, listOf(5), "T")
    }
    rejects("a wide high-half read vetoes scratch", "v5 is read") {
        assertNotReadBeforeWritten(listOf(putWide, ImmutableInstruction10x(Opcode.RETURN_VOID)),
            0, listOf(5), "T")
    }
    val targetedTail = listOf(
        ImmutableInstruction10t(Opcode.GOTO, 1), ImmutableInstruction11x(Opcode.RETURN, 0))
    rejects("an edge into the return would bypass a tail insertion", "branch into its return") {
        assertTailReturnUntargeted(targetedTail, 1, "T")
    }
    accepts("a fall-through return follows the insertion") {
        assertTailReturnUntargeted(
            listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                ImmutableInstruction11x(Opcode.RETURN, 0)), 1, "T")
    }

    // Offsets are in code units from the branch, not instruction counts: if-eqz is two units wide.
    val branchy = listOf(
        ImmutableInstruction21t(Opcode.IF_EQZ, 0, 3),     // addr 0 -> addr 3
        ImmutableInstruction11n(Opcode.CONST_4, 0, 1),    // addr 2
        ImmutableInstruction11x(Opcode.RETURN, 0),        // addr 3
    )
    equal("an if-* resolves to the instruction at its offset", "2",
        branchy.branchTargetIndex(0, "T").toString())
    equal("a goto resolves the same way", "2",
        listOf(ImmutableInstruction10t(Opcode.GOTO, 2), ImmutableInstruction10x(Opcode.NOP),
            ImmutableInstruction11x(Opcode.RETURN, 0)).branchTargetIndex(0, "T").toString())
    rejects("a branch into the middle of an instruction has no target", "lands between") {
        listOf(ImmutableInstruction21t(Opcode.IF_EQZ, 0, 1), ImmutableInstruction11x(Opcode.RETURN, 0))
            .branchTargetIndex(0, "T")
    }
    rejects("only a goto or if-* has a branch target", "is not a goto or if-* branch") {
        branchy.branchTargetIndex(1, "T")
    }

    // `setsRegister` is the assembler's own flag. invoke-virtual does not set one: its result
    // arrives in the following move-result, and treating v-whatever as clobbered here loses a slot.
    equal("an invoke writes nothing", "null", invoke35c(3, 5).destinationRegisterOrNull().toString())
    equal("an invoke clobbers nothing", "[]", invoke35c(3, 5).destinationRegistersOrEmpty().toString())
    equal(
        "move-result writes its one register",
        "7",
        ImmutableInstruction11x(Opcode.MOVE_RESULT, 7).destinationRegisterOrNull().toString(),
    )

    // A wide destination occupies two slots, and an emitter that scribbles in the second half of a
    // long corrupts a value with nothing in the disassembly looking wrong.
    val wide = ImmutableInstruction11x(Opcode.MOVE_RESULT_WIDE, 4)
    equal("a wide destination claims both words", "[4, 5]", wide.destinationRegistersOrEmpty().toString())
    equal(
        "a narrow destination claims one",
        "[4]",
        ImmutableInstruction11x(Opcode.MOVE_RESULT, 4).destinationRegistersOrEmpty().toString(),
    )
}

// ------------------------------------------------------------------ what an instruction refers to

private fun references() {
    val call = invoke35c(3, ref = METHOD)
    equal("the descriptor of a call", "Lpvf;->t(Lpvi;I)V", call.methodDescriptorOrNull().toString())
    equal("callsMethod matches exactly", "true", call.callsMethod("Lpvf;->t(Lpvi;I)V").toString())
    equal("callsMethod does not match a prefix", "false", call.callsMethod("Lpvf;->t").toString())
    equal(
        "a different method does not match",
        "false",
        invoke35c(3, ref = OTHER).callsMethod("Lpvf;->t(Lpvi;I)V").toString(),
    )
    equal(
        "a non-call has no descriptor",
        "null",
        ImmutableInstruction11x(Opcode.MOVE_RESULT, 0).methodDescriptorOrNull().toString(),
    )

    val iget = ImmutableInstruction22c(Opcode.IGET, 0, 1, FIELD)
    equal("the descriptor of a field access", "Lpvs;->a:I", iget.fieldDescriptor())
    equal("usesField matches", "true", iget.usesField("Lpvs;->a:I").toString())
    equal("usesField rejects another field", "false", iget.usesField("Lpvs;->b:I").toString())
    equal("a call is not a field access", "false", call.usesField("Lpvs;->a:I").toString())

    val konst = ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("hello"))
    equal("the string of a const-string", "hello", konst.stringOrNull().toString())
    equal("a call carries no string", "null", call.stringOrNull().toString())

    // Uppercased with separators flattened, because it names generated constants.
    equal("opcode name of a plain opcode", "INVOKE_VIRTUAL", call.opcodeName())
    equal("opcode name flattens the slash", "INVOKE_VIRTUAL_RANGE", invoke3rc(0, 1).opcodeName())
    equal(
        "opcode name flattens the dash",
        "MOVE_RESULT_WIDE",
        ImmutableInstruction11x(Opcode.MOVE_RESULT_WIDE, 0).opcodeName(),
    )
}

// ------------------------------------------------------------------ finding the one call

private fun soleCall() {
    val body = listOf(
        ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
        invoke35c(3, ref = METHOD),
        ImmutableInstruction11x(Opcode.MOVE_RESULT, 1),
    )
    equal("the index of the only call", "1", body.indexOfSoleCall("Lpvf;->t(Lpvi;I)V", "T").toString())

    rejects("no call at all", "0") { body.indexOfSoleCall("Lpvf;->zzz()V", "T") }
    rejects("two calls", "2") {
        (body + invoke35c(4, ref = METHOD)).indexOfSoleCall("Lpvf;->t(Lpvi;I)V", "T")
    }
}
