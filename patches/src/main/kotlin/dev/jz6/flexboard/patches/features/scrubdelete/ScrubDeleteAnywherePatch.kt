package dev.jz6.flexboard.patches.features.scrubdelete

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD

/**
 * Gboard already implements swipe-to-delete-a-word. `ScrubDeleteMotionEventHandler` is the swipe
 * on the backspace key, and everything it does — progressive delete, drag back to restore,
 * distance thresholds, tracking across the full keyboard width — lives in a shared
 * `ScrubMotionEventHandler` that is entirely key-agnostic once a gesture has begun.
 *
 * The only thing scoping it to backspace is one comparison in `g(Landroid/view/MotionEvent;)V`:
 *
 * ```
 * iget   v5, v5, Loud;->c:I     # keycode of the key under the finger
 * iget   v6, v1, Lpbv;->a:I     # the configured start keycode, 67 for delete
 * if-ne  v5, v6, -> bail
 * ```
 *
 * So this patch does not implement a gesture. It widens where Gboard's own gesture is allowed to
 * start, in two edits:
 *
 *  1. `ScrubDeleteMotionEventHandler.<init>` passes **-1** instead of `KEYCODE_DEL`.
 *  2. `g()` skips the comparison when the configured keycode is negative.
 *
 * A negative sentinel is what makes the second edit register-free: every Android keycode is
 * non-negative, so the test is `if-ltz` — format 21t, one register, no constant, and therefore no
 * need to prove some register is dead at that point in a 259-instruction method.
 *
 * The other two subclasses are untouched. `ScrubMoveMotionEventHandler` (spacebar cursor) and
 * `InlineSuggestionScrubSpaceMotionEventHandler` both pass 62, so their gate still enforces.
 *
 * See `docs/motion-event-handlers.md` for how all of this was derived.
 */
@Suppress("unused")
val swipeToDeletePatch = bytecodePatch(
    name = "Swipe to Delete",
    description = "Swipe left anywhere on the keyboard to delete the previous word, and swipe " +
        "right to restore it. Uses Gboard's own word-scrub engine, so it behaves exactly like " +
        "swiping on the backspace key already does — only it can start anywhere.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    // Widening the gate is pointless if the handler is never attached, and unusable while glide
    // typing is live on the same pointer stream.
    dependsOn(forceScrubPreferencesPatch)

    execute {
        ScrubDeleteConstructorFingerprint.method.widenStartKeyToWildcard()
        ScrubHandleMotionEventFingerprint.method.acceptWildcardStartKey()
        ScrubActivationTestFingerprint.method.removeHoldDelayForWildcard()
    }
}

/** `KeyEvent.KEYCODE_DEL`, the key Gboard scopes its word-scrub delete to. */
private const val STOCK_START_KEYCODE = 67

/**
 * Written into the config in place of the keycode. Any negative value works; no Android keycode
 * is negative, so it cannot collide with a real key.
 */
private const val WILDCARD_START_KEYCODE = "-0x1"

/** The config field the gate reads. First argument of `Lpbv;-><init>(IZIIIIII)V`. */
private const val CONFIG_START_KEY_FIELD = "Lpbv;->a:I"

/**
 * Asserted rather than adapted to. `p2` resolving to a different register on an unexpected build
 * is the failure mode that produced a bundle which would not apply once already — see
 * `docs/register-encoding.md`. Failing loudly here is far better.
 */
private const val SCRUB_HANDLE_REGISTER_COUNT = 13

private const val WILDCARD_LABEL = "flexboard_any_start_key"

/** The 200 ms the gesture must be held before it may activate, whatever the distance travelled. */
private const val HOLD_DELAY_FIELD = "Lpbu;->b:J"

/** `p(Landroid/view/MotionEvent;I)Z` — non-static, so `this` + MotionEvent + int = 3 words. */
private const val ACTIVATION_TEST_REGISTER_COUNT = 10
private const val ACTIVATION_TEST_PARAMETER_WORDS = 3

private const val INSTANT_LABEL = "flexboard_no_hold"

/** The gate is five instructions long; the window is slack, not a measurement. */
private const val HOLD_GATE_WINDOW = 8

/**
 * Replaces the `const/16 vN, 67` feeding `Lpbv;-><init>`'s first argument. The literal is matched
 * rather than the position, and exactly one match is required — the constructor also loads four
 * negative event codes and an attr reference, none of which can be confused with a keycode.
 */
private fun MutableMethod.widenStartKeyToWildcard() {
    val matches = instructions.withIndex().filter { (_, instruction) ->
        instruction.opcodeName() == "CONST_16" &&
            (instruction as? NarrowLiteralInstruction)?.narrowLiteral == STOCK_START_KEYCODE
    }
    check(matches.size == 1) {
        "Expected exactly one `const/16 …, $STOCK_START_KEYCODE` in " +
            "$SCRUB_DELETE_MOTION_EVENT_HANDLER-><init>, found ${matches.size}. " +
            "Gboard's scrub delete no longer starts on KEYCODE_DEL, or the constructor changed."
    }
    val (index, instruction) = matches.single()
    val register = (instruction as OneRegisterInstruction).registerA
    replaceInstruction(index, "const/16 v$register, $WILDCARD_START_KEYCODE")
}

/**
 * Inserts a single `if-ltz` ahead of the gate, branching past it to the instruction the gate falls
 * through to. A negative configured keycode therefore means "any key"; a real one still enforces,
 * which is what leaves the spacebar and inline-suggestion scrubs alone.
 */
private fun MutableMethod.acceptWildcardStartKey() {
    val registerCount = implementation?.registerCount
        ?: error("$SCRUB_MOTION_EVENT_HANDLER->g has no implementation")
    check(registerCount == SCRUB_HANDLE_REGISTER_COUNT) {
        "$SCRUB_MOTION_EVENT_HANDLER->g has $registerCount registers, " +
            "expected $SCRUB_HANDLE_REGISTER_COUNT — refusing to guess register mapping"
    }

    val reads = instructions.withIndex().filter { (_, instruction) ->
        instruction.opcodeName() == "IGET" && instruction.readsField(CONFIG_START_KEY_FIELD)
    }
    check(reads.size == 1) {
        "Expected exactly one read of $CONFIG_START_KEY_FIELD in " +
            "$SCRUB_MOTION_EVENT_HANDLER->g, found ${reads.size}"
    }
    val (readIndex, read) = reads.single()
    val configRegister = (read as OneRegisterInstruction).registerA

    val gateIndex = readIndex + 1
    val gate = instructions[gateIndex]
    check(gate.opcodeName() == "IF_NE") {
        "Expected `if-ne` immediately after the read of $CONFIG_START_KEY_FIELD, " +
            "found `${gate.opcode.name}`"
    }
    val compared = (gate as TwoRegisterInstruction).let { setOf(it.registerA, it.registerB) }
    check(configRegister in compared) {
        "The `if-ne` after $CONFIG_START_KEY_FIELD compares $compared, " +
            "which does not include the register the read wrote (v$configRegister)"
    }

    // Captured before the insertion shifts indices; the label resolves by instruction identity.
    val gatePassed = instructions[gateIndex + 1]

    addInstructionsWithLabels(
        gateIndex,
        "if-ltz v$configRegister, :$WILDCARD_LABEL",
        ExternalLabel(WILDCARD_LABEL, gatePassed),
    )
}

/**
 * The activation test opens with a hold gate:
 *
 * ```
 * iget-wide v5, v0, Lpbu;->b:J     # 200 ms
 * add-long/2addr v3, v5            # downTime + delay
 * cmp-long v0, v1, v3
 * const/4 v1, 0x0
 * if-gez v0, :continue
 * return v1                        # too soon, whatever the distance
 * ```
 *
 * 200 ms of dead time makes the gesture feel like a press-and-drag rather than a flick. Gboard
 * itself varies this per handler — the inline-suggestion scrub passes 50 ms — so shortening it is
 * within the engine's design rather than a violation of it.
 *
 * Skipped only when the configured keycode is the wildcard, so the spacebar cursor-drag keeps its
 * full delay. Removing it there too would make accidental cursor drags off the spacebar far easier
 * while typing.
 *
 * The distance requirement is deliberately left alone: the finger must still travel 8pt *and*
 * leave the key it started on, which is what stops a tap being read as a delete.
 */
private fun MutableMethod.removeHoldDelayForWildcard() {
    val registerCount = implementation?.registerCount
        ?: error("$SCRUB_MOTION_EVENT_HANDLER->p has no implementation")
    check(registerCount == ACTIVATION_TEST_REGISTER_COUNT) {
        "$SCRUB_MOTION_EVENT_HANDLER->p has $registerCount registers, " +
            "expected $ACTIVATION_TEST_REGISTER_COUNT — refusing to guess register mapping"
    }
    // Non-static, (MotionEvent, int) -> three parameter words, so `this` is resolvable.
    val thisRegister = registerCount - ACTIVATION_TEST_PARAMETER_WORDS

    val reads = instructions.withIndex().filter { (_, instruction) ->
        instruction.opcodeName() == "IGET_WIDE" && instruction.readsField(HOLD_DELAY_FIELD)
    }
    check(reads.size == 1) {
        "Expected exactly one read of $HOLD_DELAY_FIELD in $SCRUB_MOTION_EVENT_HANDLER->p, " +
            "found ${reads.size}"
    }
    val readIndex = reads.single().index

    // Anchored on the branch rather than on the arithmetic between: the exact add/compare opcodes
    // are an encoding detail, and asserting them buys nothing while giving a Gboard recompile an
    // extra way to fail the patch for no reason.
    val gateBranch = (readIndex until minOf(readIndex + HOLD_GATE_WINDOW, instructions.size))
        .firstOrNull { instructions[it].opcodeName() == "IF_GEZ" }
        ?: error(
            "No `if-gez` within $HOLD_GATE_WINDOW instructions of $HOLD_DELAY_FIELD in " +
                "$SCRUB_MOTION_EVENT_HANDLER->p — the hold gate is not the shape expected",
        )
    check(instructions[gateBranch + 1].opcodeName() == "RETURN") {
        "Expected `return` immediately after the hold gate's `if-gez`, found " +
            "`${instructions[gateBranch + 1].opcode.name}`"
    }
    val gatePassed = instructions[gateBranch + 2]

    // Injected at method entry rather than next to the gate: by the time the gate runs, v0 holds
    // the Lpbu; the gate is about to read, so clobbering it there would break the very check being
    // bypassed. At entry v0 is dead — the stock body's first act is to write it.
    addInstructionsWithLabels(
        0,
        """
            iget-object v0, v$thisRegister, $SCRUB_MOTION_EVENT_HANDLER->g:Lpbv;
            iget v0, v0, $CONFIG_START_KEY_FIELD
            if-ltz v0, :$INSTANT_LABEL
        """,
        ExternalLabel(INSTANT_LABEL, gatePassed),
    )
}

private fun Instruction.opcodeName(): String =
    opcode.name.uppercase().replace('-', '_').replace('/', '_')

private fun Instruction.readsField(descriptor: String): Boolean =
    ((this as? ReferenceInstruction)?.reference as? FieldReference)?.toString() == descriptor
