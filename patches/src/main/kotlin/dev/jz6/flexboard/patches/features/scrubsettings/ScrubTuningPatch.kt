package dev.jz6.flexboard.patches.features.scrubsettings

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import dev.jz6.flexboard.patches.features.scrubdelete.CONFIG_DISABLED_FIELD
import dev.jz6.flexboard.patches.features.scrubdelete.CONFIG_START_KEY_FIELD
import dev.jz6.flexboard.patches.features.scrubdelete.CONFIG_STEP_TABLE_FIELD
import dev.jz6.flexboard.patches.features.scrubdelete.PREFERENCE_GET_INT
import dev.jz6.flexboard.patches.features.scrubdelete.PREFERENCE_STORE_GET
import dev.jz6.flexboard.patches.features.scrubdelete.SCRUB_DELETE_MOTION_EVENT_HANDLER
import dev.jz6.flexboard.patches.features.scrubdelete.SCRUB_MOTION_EVENT_HANDLER
import dev.jz6.flexboard.patches.features.scrubdelete.ScrubDeleteConstructorFingerprint
import dev.jz6.flexboard.patches.features.scrubdelete.ScrubEngineConstructorFingerprint
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.indexOfSoleCall
import dev.jz6.flexboard.patches.shared.invokeRegisterAt
import dev.jz6.flexboard.patches.shared.invokeRegisterCount

/**
 * Makes the scrub engine's feel adjustable from Gboard's own settings.
 *
 * Every value in `Lpbu;`, the engine's tuning struct, is **`public final`** — Gboard writes them
 * only inside `Lpbu;-><init>`. So none of them can be set with an `iput` from a patch; ART rejects
 * a final-field write from outside the declaring class. The way in is to substitute the
 * **constructor arguments** instead, which is what this patch does.
 *
 * That turns out to be the better shape anyway. The hold delay was previously removed by editing
 * the activation test `p()` at runtime, and getting that edit wrong shipped a `VerifyError` that
 * bricked the keyboard. Substituting the value at construction touches no control flow in the
 * gesture path at all.
 *
 * Both edits are gated on the wildcard start keycode written by `swipeToDeletePatch`, so the
 * spacebar cursor drag and the inline-suggestion scrub keep their stock values. The
 * inline-suggestion handler additionally calls the four-argument constructor directly, so it never
 * even reaches the hold-delay substitution.
 *
 * See `docs/motion-event-handlers.md` for how the engine was derived.
 */
internal val scrubTuningPatch = bytecodePatch(
    description = "Reads the swipe length and hold delay from Gboard's preference store, so the " +
        "scrub engine's feel can be adjusted from its settings.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    // The rows that write these preferences. Shipping the reader without them would leave two
    // values nothing can ever set.
    dependsOn(scrubSettingsScreenPatch)

    execute {
        ScrubEngineConstructorFingerprint.method.substituteHoldDelay()
        ScrubDeleteConstructorFingerprint.method.scaleStepTable()
    }
}

/**
 * Preference keys. Deliberately plain string literals rather than resource ids: `Lpnp;` exposes a
 * string-keyed getter alongside its resource-id one, and a *new* resource has no id until aapt2
 * recompiles, which is long after this patch runs. Literals sidestep the problem entirely, and the
 * settings rows use the same literals.
 */
internal const val STEP_SCALE_KEY = "flexboard_scrub_step_scale"
internal const val HOLD_DELAY_KEY = "flexboard_scrub_hold_ms"

/** Percent, so the default means "exactly what Gboard ships" without knowing the pixel value. */
internal const val STEP_SCALE_DEFAULT = 100

/** Milliseconds. Zero reproduces the flick behaviour that shipped before this was adjustable. */
internal const val HOLD_DELAY_DEFAULT = 0

/** `regs=11, ins=4` — asserted so the scratch register below is provably the one that was read. */
private const val ENGINE_CONSTRUCTOR_REGISTER_COUNT = 11

/** `this`, Context, Lpbr;, Lpbv;, and the wide delay — six registers. */
private const val ENGINE_CONSTRUCTOR_ARGUMENT_REGISTERS = 6

private const val DELETE_CONSTRUCTOR_REGISTER_COUNT = 12
private const val DELETE_CONSTRUCTOR_ARGUMENT_REGISTERS = 4

private const val FOUR_ARGUMENT_ENGINE_CONSTRUCTOR =
    "$SCRUB_MOTION_EVENT_HANDLER-><init>(Landroid/content/Context;Lpbr;Lpbv;J)V"

private const val THREE_ARGUMENT_ENGINE_CONSTRUCTOR =
    "$SCRUB_MOTION_EVENT_HANDLER-><init>(Landroid/content/Context;Lpbr;Lpbv;)V"

/** `100.0f`, as the high-16 constant the smali assembler wants. */
private const val ONE_HUNDRED_FLOAT = "0x42c80000"

private const val STOCK_HOLD_LABEL = "flexboard_stock_hold"
private const val STEPS_LOOP_LABEL = "flexboard_steps_loop"
private const val STEPS_DONE_LABEL = "flexboard_steps_done"

/**
 * The three-argument engine constructor reads the 200 ms hold delay from a resource and forwards it
 * to the four-argument form:
 *
 * ```
 *  4: const v1, 0x7f0c00ef
 *  7: invoke-virtual {v0, v1}, Resources;->getInteger(I)I
 * 11: int-to-long v5, v0
 * 12: move-object v1, v7 … v4, v10
 * 16: invoke-direct/range {v1 .. v6}, ScrubMotionEventHandler-><init>(…Lpbv;J)V
 * ```
 *
 * Replacing the forwarded value means `Lpbu;->b:J` is *built* with the user's delay, so the gate in
 * `p()` still runs exactly as Gboard wrote it and simply compares against a different number.
 *
 * Anchored on the forwarded call rather than on the resource id or the conversion opcode: the call
 * gives every argument register directly, and neither a renumbered register nor a changed resource
 * id can silently mislead it.
 */
private fun MutableMethod.substituteHoldDelay() {
    val registerCount = implementation?.registerCount
        ?: error("$THREE_ARGUMENT_ENGINE_CONSTRUCTOR has no implementation")
    check(registerCount == ENGINE_CONSTRUCTOR_REGISTER_COUNT) {
        "The three-argument engine constructor has $registerCount registers, expected " +
            "$ENGINE_CONSTRUCTOR_REGISTER_COUNT — refusing to guess which register is free"
    }

    val forwardIndex = instructions.indexOfSoleCall(
        FOUR_ARGUMENT_ENGINE_CONSTRUCTOR,
        "the three-argument engine constructor",
    )
    val forward = instructions[forwardIndex]
    check(forward.invokeRegisterCount() == ENGINE_CONSTRUCTOR_ARGUMENT_REGISTERS) {
        "The forwarded engine constructor takes ${forward.invokeRegisterCount()} registers, " +
            "expected $ENGINE_CONSTRUCTOR_ARGUMENT_REGISTERS"
    }

    val contextRegister = forward.invokeRegisterAt(1)
    val configRegister = forward.invokeRegisterAt(3)
    val delayRegister = forward.invokeRegisterAt(4)

    // The registers below the forwarded call's range are the locals that computed the stock delay;
    // with the frame pinned above, the lowest of them holds nothing live by this point.
    val scratchRegister = forward.invokeRegisterAt(0) - 1
    check(scratchRegister >= 0) {
        "No register below the forwarded engine constructor call to borrow as scratch"
    }

    // The key and default are staged in the delay pair itself, which is about to be overwritten on
    // this path and is untouched on the other — so no register outside the pair is disturbed.
    val delayHigh = delayRegister + 1
    addInstructionsWithLabels(
        forwardIndex,
        """
            iget v$scratchRegister, v$configRegister, $CONFIG_START_KEY_FIELD
            if-gez v$scratchRegister, :$STOCK_HOLD_LABEL
            invoke-static { v$contextRegister }, $PREFERENCE_STORE_GET
            move-result-object v$scratchRegister
            const-string v$delayRegister, "$HOLD_DELAY_KEY"
            const/16 v$delayHigh, $HOLD_DELAY_DEFAULT
            invoke-virtual { v$scratchRegister, v$delayRegister, v$delayHigh }, $PREFERENCE_GET_INT
            move-result v$scratchRegister
            int-to-long v$delayRegister, v$scratchRegister
        """,
        ExternalLabel(STOCK_HOLD_LABEL, forward),
    )
}

/**
 * Scales the distance table in place.
 *
 * `r()` counts how many entries of `Lpbv;->h:[F` the travelled distance has passed, and that count
 * is the number of words deleted — so the table *is* the swipe length, and scaling it by a
 * percentage is the whole knob. Unlike `Lpbu;`, the table field is not final, and array contents
 * are writable regardless, so this needs no constructor-argument substitution.
 *
 * Done in `ScrubDeleteMotionEventHandler.<init>` rather than the shared engine constructor for two
 * reasons: it is scoped to delete by construction, needing no sentinel test, and the Context is
 * still in a parameter register there — the engine constructor overwrites its own Context register
 * with `Resources` before the table is built.
 *
 * A positive scale preserves the strictly-increasing invariant the engine checks, so this cannot
 * trip the `Lpbv;->g:Z` bail-out. Where that flag is already set the table points at a *shared
 * static* fallback, so the scaling is skipped rather than corrupting global state.
 */
private fun MutableMethod.scaleStepTable() {
    val registerCount = implementation?.registerCount
        ?: error("$SCRUB_DELETE_MOTION_EVENT_HANDLER-><init> has no implementation")
    check(registerCount == DELETE_CONSTRUCTOR_REGISTER_COUNT) {
        "$SCRUB_DELETE_MOTION_EVENT_HANDLER-><init> has $registerCount registers, expected " +
            "$DELETE_CONSTRUCTOR_REGISTER_COUNT — refusing to guess which registers are free"
    }

    val superIndex = instructions.indexOfSoleCall(
        THREE_ARGUMENT_ENGINE_CONSTRUCTOR,
        "$SCRUB_DELETE_MOTION_EVENT_HANDLER-><init>",
    )
    val superCall = instructions[superIndex]
    check(superCall.invokeRegisterCount() == DELETE_CONSTRUCTOR_ARGUMENT_REGISTERS) {
        "The engine constructor call takes ${superCall.invokeRegisterCount()} registers, " +
            "expected $DELETE_CONSTRUCTOR_ARGUMENT_REGISTERS"
    }

    val contextRegister = superCall.invokeRegisterAt(1)
    val configRegister = superCall.invokeRegisterAt(3)

    // Once the super call has returned, every register it used is the only thing still live — the
    // method's next instruction is its return. So the scratch set is everything else, low first,
    // and capped at v15 because a 35c invoke packs its registers into nibbles.
    val used = (0 until superCall.invokeRegisterCount()).map { superCall.invokeRegisterAt(it) }
    val scratch = (0 until minOf(registerCount, PACKED_INVOKE_REGISTER_LIMIT))
        .filterNot { it in used }
        .take(SCRATCH_REGISTERS_NEEDED)
    check(scratch.size == SCRATCH_REGISTERS_NEEDED) {
        "Only ${scratch.size} free registers in $SCRUB_DELETE_MOTION_EVENT_HANDLER-><init>, " +
            "need $SCRATCH_REGISTERS_NEEDED"
    }
    val (store, table, length, index, element) = scratch

    val afterSuper = instructions[superIndex + 1]

    addInstructionsWithLabels(
        superIndex + 1,
        """
            invoke-static { v$contextRegister }, $PREFERENCE_STORE_GET
            move-result-object v$store
            const-string v$table, "$STEP_SCALE_KEY"
            const/16 v$length, $STEP_SCALE_DEFAULT
            invoke-virtual { v$store, v$table, v$length }, $PREFERENCE_GET_INT
            move-result v$store
            const/16 v$table, $STEP_SCALE_DEFAULT
            if-eq v$store, v$table, :$STEPS_DONE_LABEL
            if-lez v$store, :$STEPS_DONE_LABEL
            iget-boolean v$table, v$configRegister, $CONFIG_DISABLED_FIELD
            if-nez v$table, :$STEPS_DONE_LABEL
            iget-object v$table, v$configRegister, $CONFIG_STEP_TABLE_FIELD
            if-eqz v$table, :$STEPS_DONE_LABEL
            int-to-float v$store, v$store
            const/high16 v$length, $ONE_HUNDRED_FLOAT
            div-float/2addr v$store, v$length
            array-length v$length, v$table
            const/4 v$index, 0x0
            :$STEPS_LOOP_LABEL
            if-ge v$index, v$length, :$STEPS_DONE_LABEL
            aget v$element, v$table, v$index
            mul-float/2addr v$element, v$store
            aput v$element, v$table, v$index
            add-int/lit8 v$index, v$index, 0x1
            goto :$STEPS_LOOP_LABEL
        """,
        ExternalLabel(STEPS_DONE_LABEL, afterSuper),
    )
}

/** store, table, length, index, element. */
private const val SCRATCH_REGISTERS_NEEDED = 5

/** A `35c` invoke addresses its registers in 4-bit nibbles, so v15 is the highest usable one. */
private const val PACKED_INVOKE_REGISTER_LIMIT = 16
