package dev.jz6.flexboard.patches.features.undodelete

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.jz6.flexboard.patches.features.scrubdelete.PREFERENCE_GET_BOOLEAN
import dev.jz6.flexboard.patches.features.scrubdelete.checkPreferenceStorePins
import dev.jz6.flexboard.patches.features.scrubdelete.PREFERENCE_STORE_GET
import dev.jz6.flexboard.patches.shared.ANDROID_CONTEXT
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.TypedRegister
import dev.jz6.flexboard.patches.shared.callsMethod
import dev.jz6.flexboard.patches.shared.checkAssignable
import dev.jz6.flexboard.patches.shared.fieldDescriptor
import dev.jz6.flexboard.patches.shared.fieldOwnerType
import dev.jz6.flexboard.patches.shared.findInstanceField
import dev.jz6.flexboard.patches.shared.invokeRegisterAt
import dev.jz6.flexboard.patches.shared.invokeRegisterCount
import dev.jz6.flexboard.patches.shared.opcodeName

/**
 * Swipe right, after the delete gesture has ended, to put back the words it removed.
 *
 * Gboard's own right-swipe restore only works *inside* a gesture — drag back before lifting and the
 * count shrinks toward zero. Lift, and the deletion is committed. This makes a fresh rightward
 * swipe undo it.
 *
 * ## Almost all of this already exists
 *
 * The first estimate for this feature assumed Flexboard would have to capture the deleted text
 * itself and reinsert it. It does not. Gboard already:
 *
 *  - **records the text.** `SCRUB_DELETE_FINISH` calls `Lomu;->a(I)`, which performs the deletion
 *    and *returns what it removed*, and the handler stores that in the undo slot
 *    (`LatinIme->y:Lqyc;`). The words a swipe deleted are sitting there when the finger lifts.
 *  - **knows how to put it back.** The stock `UNDO_MULTI_DELETION` handler pulls the slot and
 *    re-commits through an `AbstractIme` hook — `s` on 17.7.7, `t` on 18, which is why this patch
 *    reads the method out of that handler rather than naming it. Its own gate,
 *    `nga_enable_undo_delete`, is declared with a default of `true`, so there is nothing to turn on.
 *
 * The only missing piece was a way to ask for it. That is all this patch adds.
 *
 * ## Why a positive count is the right trigger
 *
 * `Lomu;->e(I)` opens with `count = Math.min(0, count)`, so a rightward scrub clamps to zero and
 * deletes nothing — the gesture is already an established no-op, and nothing is being taken away by
 * giving it a meaning. The clamp lives inside `Lomu;`, not in the event, so the signed count still
 * reaches the finish handler intact and `count > 0` *is* "the user swiped right".
 *
 * The obvious alternative — "the scrub finished having deleted nothing" — would also fire on a
 * short leftward flick that never crossed the first distance threshold, undoing something the user
 * was not asking about.
 *
 * ## Reusing the suppression branch instead of naming a target
 *
 * The handler's second instruction is `if-nez vFlag, :handled`, where `vFlag` is
 * `AbstractIme->O:Z` and `:handled` is the stock "treat as handled, do nothing" exit. Rather than
 * branch there — which would mean resolving a `packed-switch`-reached label — this sets `vFlag` and
 * lets the stock test do the jumping. Control flow converges on Gboard's own path with no external
 * label at all, and the epilogue's `Trace.endSection()` still runs, which an early `return` would
 * have skipped and left the trace stack unbalanced.
 */
@Suppress("unused")
val swipeRightToUndoPatch = bytecodePatch(
    name = "Swipe Right to Undo",
    description = "Swipe right after deleting to put the words back. Uses Gboard's own undo, " +
        "which already records what a delete swipe removed.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    execute {
        checkPreferenceStorePins()

        LatinImeHandleEventFingerprint.method.undoOnRightwardScrub(this)
    }
}

/**
 * Asserted rather than adapted to. Every register below is read off the anchor instructions, but
 * the *scratch* choice rests on knowing what the rest of the handler and the epilogue do with
 * v1–v3, which is a property of this build. Failing loudly beats guessing in a 34-register method.
 */
private const val HANDLE_EVENT_REGISTER_COUNT = 34

/** How far back from the sole `Lomu;->a(I)` call the handler's own prologue can be. */
private const val ANCHOR_SEARCH_WINDOW = 12

private const val NOT_RIGHTWARD_LABEL = "flexboard_not_rightward"
private const val UNDO_DONE_LABEL = "flexboard_undo_done"

/**
 * Registers the emitted block borrows.
 *
 * Safe because every one of them is written before it is next read, on both paths out of here:
 * the stock finish path rewrites v1 immediately (the `LatinIme->U:Lomu;` read), v2 at the
 * `Lomu;->b:Z` read and v3 as the high half of the `z()J` result; and the handled path runs the
 * epilogue, which writes v0/v1 (`move-result-wide`), v2 (`AbstractIme->X()`) and v3 (`sget-object`)
 * before reading any of them. The two branches the finish path can still take — the empty-text exit
 * and the no-`Lftq;` exit — both join the epilogue without reading v2 or v3 first.
 *
 * The epilogue *does* read v4 (the handled flag), v9 (`this`), v10 (the return value) and v12, so
 * none of those may be touched.
 *
 * **Re-derived for Gboard 18, not carried over.** The dispatcher dropped from 36 to 34 registers,
 * which reshuffles everything this argument rests on; v2 and v3 happen to remain free, but that is
 * a result of re-reading the method rather than an assumption inherited from 17.7.7.
 */
private val SCRATCH_REGISTERS = listOf(2, 3)

/**
 * The switch on Flexboard's settings screen. Absent means on, so an existing install keeps the
 * behaviour it had before the setting existed. Duplicated as a literal in
 * `FlexboardSettingsActivity.java`, for the same reason the scrub keys are: a patch-added resource
 * has no id until aapt2 recompiles, so bytecode cannot address one.
 */
internal const val UNDO_ENABLED_KEY = "flexboard_undo_enabled"

/** Everything the emitted undo needs, read out of the handler that performs Gboard's own undo. */
private data class StockUndo(
    val slotField: String,
    val available: String,
    val get: String,
    val committableText: String,
    val recommit: String,
    val clear: String,
)

/**
 * Reads the stock `UNDO_MULTI_DELETION` handler and returns the members it uses.
 *
 * ## Why every one of these is derived rather than named
 *
 * Four of the six share their signature with siblings on the same class, so neither the name nor
 * the shape identifies them — only what Gboard's own undo does with them:
 *
 * | member | siblings with the same signature |
 * |---|---|
 * | re-commit | `s` and `t` on `AbstractIme`, both `(L…;Z)V`, both empty stubs on the base |
 * | available | `d`, `m`, `n` on the slot, all `()Z` |
 * | clear | nine `()V` methods on the slot |
 * | slot field | resolved with the slot type, so it cannot name a stale one |
 *
 * `0.0.3-dev.1` shipped the first of those wrong: it called `s`, which existed, verified and ran,
 * and silently did nothing. The other three were the same accident waiting to happen.
 *
 * ## The anchor
 *
 * The re-commit is matched by *shape* — `AbstractIme->…(L…;Z)V` — which occurs exactly once in the
 * whole 1,544-instruction dispatcher, so it needs no name to find. Everything else is then located
 * relative to it by register: the receiver of the `Optional` getter is the slot, and the field that
 * loaded that register is where the slot lives.
 */
private fun MutableMethod.resolveStockUndo(): StockUndo {
    val anchors = instructions.withIndex().filter { (_, instruction) ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        reference != null && RECOMMIT_PATTERN.matches(reference.toString())
    }
    check(anchors.size == 1) {
        "Expected exactly one `$ABSTRACT_IME->…(L…;Z)V` call in $LATIN_IME->q — the stock undo's " +
            "re-commit — but found ${anchors.size}. Gboard's own undo no longer re-commits the way " +
            "this patch mirrors, so emitting a call here would be guessing at which method puts " +
            "the text back."
    }
    val (recommitIndex, recommitInstruction) = anchors.single()
    val recommit = (recommitInstruction as ReferenceInstruction).reference.toString()
    val committableText = RECOMMIT_PATTERN.matchEntire(recommit)!!.groupValues[1]

    val from = maxOf(0, recommitIndex - RECOMMIT_SEARCH_WINDOW)

    // The Optional getter, whose receiver is the slot. Its return type is unique on the slot class,
    // so unlike its neighbours this one really can be found by shape alone.
    val getIndex = (recommitIndex - 1 downTo from).firstOrNull {
        val reference = (instructions[it] as? ReferenceInstruction)?.reference as? MethodReference
        reference?.returnType == OPTIONAL
    } ?: error(
        "No call returning $OPTIONAL within $RECOMMIT_SEARCH_WINDOW instructions before the " +
            "re-commit in $LATIN_IME->q — the stock undo no longer reads the slot through an " +
            "Optional, so the slot cannot be identified from here",
    )
    val get = (instructions[getIndex] as ReferenceInstruction).reference.toString()
    val slotRegister = instructions[getIndex].invokeRegisterAt(0)
    val slotType = get.substringBefore("->")

    // Called on the same register before the getter: "is there anything to put back".
    val available = (getIndex - 1 downTo from).firstNonNullOf(
        "No `$slotType->…()Z` called on v$slotRegister before the Optional getter",
    ) { callOnRegister(it, slotRegister, returning = "Z") }

    // And after the re-commit: "the slot is spent".
    val clearEnd = minOf(instructions.size, recommitIndex + 1 + RECOMMIT_SEARCH_WINDOW)
    val clear = (recommitIndex + 1 until clearEnd).firstNonNullOf(
        "No `$slotType->…()V` called on v$slotRegister after the re-commit",
    ) { callOnRegister(it, slotRegister, returning = "V") }

    // Whatever loaded the slot register is where the slot is kept.
    val slotField = (getIndex - 1 downTo from).firstNotNullOfOrNull {
        val instruction = instructions[it]
        val writesSlot = (instruction as? TwoRegisterInstruction)?.registerA == slotRegister
        if (instruction.opcodeName() == "IGET_OBJECT" && writesSlot) {
            instruction.fieldDescriptor()
        } else {
            null
        }
    } ?: error(
        "Nothing loads v$slotRegister with an `iget-object` before the Optional getter in " +
            "$LATIN_IME->q, so the field holding the undo slot cannot be identified",
    )
    check(slotField.endsWith(":$slotType")) {
        "The field loading the slot register is $slotField, which is not a $slotType"
    }

    return StockUndo(slotField, available, get, committableText, recommit, clear)
}

/** The descriptor of an `invoke-virtual` on [register] with the given return type, or null. */
private fun MutableMethod.callOnRegister(index: Int, register: Int, returning: String): String? {
    val instruction = instructions[index]
    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        ?: return null
    if (reference.returnType != returning || reference.parameterTypes.isNotEmpty()) return null
    return if (instruction.invokeRegisterCount() == 1 &&
        instruction.invokeRegisterAt(0) == register
    ) {
        reference.toString()
    } else {
        null
    }
}

/** [firstNotNullOfOrNull] that fails with a diagnosis rather than returning null. */
private inline fun IntProgression.firstNonNullOf(
    onMissing: String,
    transform: (Int) -> String?,
): String = firstNotNullOfOrNull(transform) ?: error("$onMissing in $LATIN_IME->q")

private fun MutableMethod.undoOnRightwardScrub(context: BytecodePatchContext) {
    val registerCount = implementation?.registerCount
        ?: error("$LATIN_IME->q has no implementation")
    check(registerCount == HANDLE_EVENT_REGISTER_COUNT) {
        "$LATIN_IME->q has $registerCount registers, expected $HANDLE_EVENT_REGISTER_COUNT — " +
            "refusing to guess which registers are free in a method this size"
    }

    // Read out of Gboard's own undo handler rather than pinned. Four of these share a signature
    // with siblings on the same class, so a written-down letter is exactly what fails silently.
    val stock = resolveStockUndo()

    // The finish handler is reached only through a packed-switch, whose keys never appear in the
    // instruction stream, so it is anchored on the one call that is unique to it instead.
    val takeText = instructions.withIndex().filter { (_, it) -> it.callsMethod(SCRUB_STATE_TAKE_TEXT) }
    check(takeText.size == 1) {
        "Expected exactly one call to $SCRUB_STATE_TAKE_TEXT in $LATIN_IME->q, found " +
            "${takeText.size} — SCRUB_DELETE_FINISH can no longer be told apart from its siblings"
    }
    val takeTextIndex = takeText.single().index

    // The handler's prologue, found by its shape rather than by the field's name:
    //
    //     move-result   vCount            <- the signed word count
    //     iget-boolean  vFlag, vThis, ?   <- the suppression flag, whatever it is called
    //     if-nez        vFlag, :handled
    //
    // **The name is an output here, not an input.** It was `AbstractIme->N:Z` on 17.7.7 and is `O`
    // on 18, because Gboard 18 inserted a field and shifted every letter from `C` down one — while
    // `N` went on existing as an unrelated boolean. Matching on the descriptor would have found
    // nothing and failed loudly, which is survivable; the danger is the other direction, where a
    // letter still resolves and reads the wrong field. Three instructions in a row is a far more
    // stable identifier than any letter, so the field is read off whatever sits in that position.
    val flagCandidates =
        (takeTextIndex - 1 downTo maxOf(1, takeTextIndex - ANCHOR_SEARCH_WINDOW))
            .filter {
                instructions[it].opcodeName() == "IGET_BOOLEAN" &&
                    instructions[it - 1].opcodeName() == "MOVE_RESULT" &&
                    instructions[it + 1].opcodeName() == "IF_NEZ"
            }
    check(flagCandidates.size == 1) {
        "Expected exactly one `move-result` / `iget-boolean` / `if-nez` run within " +
            "$ANCHOR_SEARCH_WINDOW instructions before $SCRUB_STATE_TAKE_TEXT, found " +
            "${flagCandidates.size} — the SCRUB_DELETE_FINISH prologue has changed shape, and the " +
            "suppression flag can no longer be told apart from its neighbours by position"
    }
    val flagIndex = flagCandidates.single()

    val flagRead = instructions[flagIndex] as TwoRegisterInstruction
    val flagRegister = flagRead.registerA

    // Emitted below to restore the flag, so it must be the field that was actually read here.
    // Deliberately not compared against a constant: pinning it again as an assertion would fail a
    // patch whose derivation had worked perfectly, which is the opposite of the point.
    val suppressedField = flagRead.fieldDescriptor()

    // The IME register, carrying the only type it is *proven* to hold. Reading `AbstractIme->N:Z`
    // off it shows it is at least an `AbstractIme`; that it happens to be a `LatinIme` at run time
    // is true but not established here, which is why the Context field is named on `AbstractIme`
    // below rather than on the subclass.
    val ime = TypedRegister(flagRead.registerB, flagRead.fieldOwnerType())
    val thisRegister = ime.register

    // Gboard keeps the IME's Context in a field, and reaching it is the whole reason this patch
    // does not simply pass `this` to the preference store. `0.0.1-dev.1` did exactly that: it
    // assembled, failed verification at run time, took the dispatcher with it, and the keyboard
    // never appeared. The two checks below are what that release lacked.
    //
    // Resolved from AbstractIme's own field table rather than trusting a descriptor string, so a
    // Gboard that moves or retypes the field fails here instead of on a device.
    val contextField = context.findInstanceField(ime.type, IME_CONTEXT_FIELD_NAME)
        ?: error(
            "${ime.type} has no `$IME_CONTEXT_FIELD_NAME` field — the IME's Context has moved, " +
                "and $PREFERENCE_STORE_GET would be handed something that is not a Context",
        )

    // Reading the field at all requires the register to be a subclass of the class declaring it.
    context.checkAssignable(ime, contextField.definingClass, "The IME register in $LATIN_IME->q")
    // And what the preference store is handed has to be an actual Context. This is the assertion
    // whose absence shipped as 0.0.1-dev.1.
    context.checkAssignable(
        contextField.type,
        ANDROID_CONTEXT,
        "The value of ${contextField.definingClass}->$IME_CONTEXT_FIELD_NAME, " +
            "which $PREFERENCE_STORE_GET is handed",
    )
    val imeContextField = "${contextField.definingClass}->" +
        "$IME_CONTEXT_FIELD_NAME:${contextField.type}"

    val test = instructions[flagIndex + 1]
    check(test.opcodeName() == "IF_NEZ") {
        "Expected `if-nez` immediately after the read of $suppressedField, found " +
            "`${test.opcode.name}` — the handled-exit branch this patch relies on is gone"
    }
    val testedRegister = (test as OneRegisterInstruction).registerA
    check(testedRegister == flagRegister) {
        "The `if-nez` tests v$testedRegister, not the suppression flag in v$flagRegister"
    }

    // The count is whatever `La;->W(event)` left immediately before the flag read.
    val countMove = instructions[flagIndex - 1]
    check(countMove.opcodeName() == "MOVE_RESULT") {
        "Expected `move-result` before the read of $suppressedField, found " +
            "`${countMove.opcode.name}` — cannot locate the signed word count"
    }
    val countRegister = (countMove as OneRegisterInstruction).registerA

    val (slot, value) = SCRATCH_REGISTERS
    val claimed = listOf(countRegister, thisRegister, flagRegister, slot, value)
    check(claimed.distinct().size == claimed.size) {
        "Register collision in $LATIN_IME->q: count=v$countRegister this=v$thisRegister " +
            "flag=v$flagRegister scratch=$SCRATCH_REGISTERS"
    }

    // The count test comes before the preference read so the common case — every non-rightward
    // scrub finish — costs one comparison rather than a preference lookup.
    //
    // The `true` fallback has to be staged somewhere, and the two scratch registers are both already
    // carrying the store and the key by that point. It goes in the flag register, which is then
    // *restored by re-reading the same field the prologue read* — the identical instruction from
    // three slots earlier, so the value afterwards is provably what it was. That matters because the
    // exit below jumps to the stock `if-nez`, which tests exactly that register: leaving a borrowed
    // 1 there would tell Gboard the event was already handled and swallow every delete finish.
    addInstructionsWithLabels(
        flagIndex + 1,
        """
            if-lez v$countRegister, :$NOT_RIGHTWARD_LABEL
            iget-object v$slot, $ime, $imeContextField
            invoke-static { v$slot }, $PREFERENCE_STORE_GET
            move-result-object v$slot
            const-string v$value, "$UNDO_ENABLED_KEY"
            const/4 v$flagRegister, 0x1
            invoke-virtual { v$slot, v$value, v$flagRegister }, $PREFERENCE_GET_BOOLEAN
            move-result v$slot
            iget-boolean v$flagRegister, v$thisRegister, $suppressedField
            if-eqz v$slot, :$NOT_RIGHTWARD_LABEL
            iget-object v$slot, v$thisRegister, ${stock.slotField}
            invoke-virtual { v$slot }, ${stock.available}
            move-result v$value
            if-eqz v$value, :$UNDO_DONE_LABEL
            invoke-virtual { v$slot }, ${stock.get}
            move-result-object v$value
            invoke-virtual { v$value }, $OPTIONAL_IS_PRESENT
            move-result v$flagRegister
            if-eqz v$flagRegister, :$UNDO_DONE_LABEL
            invoke-virtual { v$value }, $OPTIONAL_GET
            move-result-object v$value
            check-cast v$value, ${stock.committableText}
            const/4 v$flagRegister, 0x1
            invoke-virtual { v$thisRegister, v$value, v$flagRegister }, ${stock.recommit}
            invoke-virtual { v$slot }, ${stock.clear}
            :$UNDO_DONE_LABEL
            const/4 v$flagRegister, 0x1
        """,
        // The stock `if-nez`, captured before the insertion shifts indices. A leftward or empty
        // scrub skips straight to it with the flag untouched, so Gboard's own path runs unchanged;
        // a rightward one falls into it with the flag forced on, and Gboard branches to its own
        // handled exit. Nothing here has to name that exit, which is reachable only through the
        // packed-switch.
        ExternalLabel(NOT_RIGHTWARD_LABEL, test),
    )
}
