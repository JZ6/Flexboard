package dev.jz6.flexboard.patches.features.scrubdelete

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import dev.jz6.flexboard.patches.features.scrubsettings.scrubTuningPatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.usesField

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
 * need to prove some register is dead at that point in a 259-instruction method. The same sentinel
 * is what [scrubTuningPatch] tests to scope its values to this handler.
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

    // Supplies the hold delay and swipe length, and the settings rows behind them. Its defaults are
    // what make the widened gesture answer to a flick rather than Gboard's 200 ms press-and-drag.
    dependsOn(scrubTuningPatch)

    execute {
        ScrubDeleteConstructorFingerprint.method.widenStartKeyToWildcard()
        ScrubHandleMotionEventFingerprint.method.acceptWildcardStartKey()
    }
}

/** `KeyEvent.KEYCODE_DEL`, the key Gboard scopes its word-scrub delete to. */
private const val STOCK_START_KEYCODE = 67

/**
 * Written into the config in place of the keycode. Any negative value works; no Android keycode
 * is negative, so it cannot collide with a real key.
 */
private const val WILDCARD_START_KEYCODE = "-0x1"

/**
 * Asserted rather than adapted to. `p2` resolving to a different register on an unexpected build
 * is the failure mode that produced a bundle which would not apply once already — see
 * `docs/register-encoding.md`. Failing loudly here is far better.
 */
private const val SCRUB_HANDLE_REGISTER_COUNT = 13

private const val WILDCARD_LABEL = "flexboard_any_start_key"

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
 *
 * Both edges reach the target with the same two registers holding ints and nothing skipped but the
 * comparison itself, so the merge is clean. That is not a detail to skim: an inserted branch that
 * lands where a register is defined on only one edge fails ART's verifier and takes the whole class
 * down. See the rule at the end of `docs/motion-event-handlers.md`.
 */
private fun MutableMethod.acceptWildcardStartKey() {
    val registerCount = implementation?.registerCount
        ?: error("$SCRUB_MOTION_EVENT_HANDLER->g has no implementation")
    check(registerCount == SCRUB_HANDLE_REGISTER_COUNT) {
        "$SCRUB_MOTION_EVENT_HANDLER->g has $registerCount registers, " +
            "expected $SCRUB_HANDLE_REGISTER_COUNT — refusing to guess register mapping"
    }

    val reads = instructions.withIndex().filter { (_, instruction) ->
        instruction.opcodeName() == "IGET" && instruction.usesField(CONFIG_START_KEY_FIELD)
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
