package dev.jz6.flexboard.patches.features.undodelete

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.callsMethod
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.usesField

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
 *  - **records the text.** `SCRUB_DELETE_FINISH` calls `Lnsz;->a(I)`, which performs the deletion
 *    and *returns what it removed*, and the handler stores that in the undo slot
 *    (`LatinIme->y:Lqcy;`). The words a swipe deleted are sitting there when the finger lifts.
 *  - **knows how to put it back.** The stock `UNDO_MULTI_DELETION` handler pulls the slot and
 *    re-commits through `AbstractIme->s`. Its own gate, `nga_enable_undo_delete`, is declared with
 *    a default of `true`, so there is nothing to turn on.
 *
 * The only missing piece was a way to ask for it. That is all this patch adds.
 *
 * ## Why a positive count is the right trigger
 *
 * `Lnsz;->e(I)` opens with `count = Math.min(0, count)`, so a rightward scrub clamps to zero and
 * deletes nothing — the gesture is already an established no-op, and nothing is being taken away by
 * giving it a meaning. The clamp lives inside `Lnsz;`, not in the event, so the signed count still
 * reaches the finish handler intact and `count > 0` *is* "the user swiped right".
 *
 * The obvious alternative — "the scrub finished having deleted nothing" — would also fire on a
 * short leftward flick that never crossed the first distance threshold, undoing something the user
 * was not asking about.
 *
 * ## Reusing the suppression branch instead of naming a target
 *
 * The handler's second instruction is `if-nez vFlag, :handled`, where `vFlag` is
 * `AbstractIme->N:Z` and `:handled` is the stock "treat as handled, do nothing" exit. Rather than
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
        LatinImeHandleEventFingerprint.method.undoOnRightwardScrub()
    }
}

/**
 * Asserted rather than adapted to. Every register below is read off the anchor instructions, but
 * the *scratch* choice rests on knowing what the rest of the handler and the epilogue do with
 * v1–v3, which is a property of this build. Failing loudly beats guessing in a 36-register method.
 */
private const val HANDLE_EVENT_REGISTER_COUNT = 36

/** How far back from the sole `Lnsz;->a(I)` call the handler's own prologue can be. */
private const val ANCHOR_SEARCH_WINDOW = 12

private const val NOT_RIGHTWARD_LABEL = "flexboard_not_rightward"
private const val UNDO_DONE_LABEL = "flexboard_undo_done"

/**
 * Registers the emitted block borrows.
 *
 * Safe because every one of them is written before it is next read, on both paths out of here:
 * the stock finish path rewrites v1 immediately, v2 at the `Lnsz;->b:Z` read and v3 as the high
 * half of the `B(J, CharSequence)` argument; and the handled path runs the epilogue, which writes
 * v0/v1 (`move-result-wide`), v2 (`aa()`) and v3 before reading any of them. The epilogue *does*
 * read v7, v8 and v12, so none of those may be touched.
 */
private val SCRATCH_REGISTERS = listOf(2, 3)

private fun MutableMethod.undoOnRightwardScrub() {
    val registerCount = implementation?.registerCount
        ?: error("$LATIN_IME->d has no implementation")
    check(registerCount == HANDLE_EVENT_REGISTER_COUNT) {
        "$LATIN_IME->d has $registerCount registers, expected $HANDLE_EVENT_REGISTER_COUNT — " +
            "refusing to guess which registers are free in a method this size"
    }

    // The finish handler is reached only through a packed-switch, whose keys never appear in the
    // instruction stream, so it is anchored on the one call that is unique to it instead.
    val takeText = instructions.withIndex().filter { (_, it) -> it.callsMethod(SCRUB_STATE_TAKE_TEXT) }
    check(takeText.size == 1) {
        "Expected exactly one call to $SCRUB_STATE_TAKE_TEXT in $LATIN_IME->d, found " +
            "${takeText.size} — SCRUB_DELETE_FINISH can no longer be told apart from its siblings"
    }
    val takeTextIndex = takeText.single().index

    // Walk back to the handler's prologue: `iget-boolean vFlag, vThis, AbstractIme->N:Z`.
    val flagIndex = (takeTextIndex - 1 downTo maxOf(0, takeTextIndex - ANCHOR_SEARCH_WINDOW))
        .firstOrNull {
            instructions[it].opcodeName() == "IGET_BOOLEAN" &&
                instructions[it].usesField(SUPPRESSED_FIELD)
        }
        ?: error(
            "No read of $SUPPRESSED_FIELD within $ANCHOR_SEARCH_WINDOW instructions before " +
                "$SCRUB_STATE_TAKE_TEXT — the SCRUB_DELETE_FINISH prologue has changed",
        )

    val flagRead = instructions[flagIndex] as TwoRegisterInstruction
    val flagRegister = flagRead.registerA
    val thisRegister = flagRead.registerB

    val test = instructions[flagIndex + 1]
    check(test.opcodeName() == "IF_NEZ") {
        "Expected `if-nez` immediately after the read of $SUPPRESSED_FIELD, found " +
            "`${test.opcode.name}` — the handled-exit branch this patch relies on is gone"
    }
    val testedRegister = (test as OneRegisterInstruction).registerA
    check(testedRegister == flagRegister) {
        "The `if-nez` tests v$testedRegister, not the suppression flag in v$flagRegister"
    }

    // The count is whatever `La;->W(event)` left immediately before the flag read.
    val countMove = instructions[flagIndex - 1]
    check(countMove.opcodeName() == "MOVE_RESULT") {
        "Expected `move-result` before the read of $SUPPRESSED_FIELD, found " +
            "`${countMove.opcode.name}` — cannot locate the signed word count"
    }
    val countRegister = (countMove as OneRegisterInstruction).registerA

    val (slot, value) = SCRATCH_REGISTERS
    val claimed = listOf(countRegister, thisRegister, flagRegister, slot, value)
    check(claimed.distinct().size == claimed.size) {
        "Register collision in $LATIN_IME->d: count=v$countRegister this=v$thisRegister " +
            "flag=v$flagRegister scratch=$SCRATCH_REGISTERS"
    }

    addInstructionsWithLabels(
        flagIndex + 1,
        """
            if-lez v$countRegister, :$NOT_RIGHTWARD_LABEL
            iget-object v$slot, v$thisRegister, $UNDO_SLOT_FIELD
            invoke-virtual { v$slot }, $UNDO_SLOT_AVAILABLE
            move-result v$value
            if-eqz v$value, :$UNDO_DONE_LABEL
            invoke-virtual { v$slot }, $UNDO_SLOT_GET
            move-result-object v$value
            invoke-virtual { v$value }, $OPTIONAL_IS_PRESENT
            move-result v$flagRegister
            if-eqz v$flagRegister, :$UNDO_DONE_LABEL
            invoke-virtual { v$value }, $OPTIONAL_GET
            move-result-object v$value
            check-cast v$value, $COMMITTABLE_TEXT
            const/4 v$flagRegister, 0x1
            invoke-virtual { v$thisRegister, v$value, v$flagRegister }, $RECOMMIT
            invoke-virtual { v$slot }, $UNDO_SLOT_CLEAR
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
