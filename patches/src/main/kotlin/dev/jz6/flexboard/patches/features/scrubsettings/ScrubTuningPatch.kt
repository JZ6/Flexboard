package dev.jz6.flexboard.patches.features.scrubsettings

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import dev.jz6.flexboard.patches.features.scrubdelete.CONFIG_DISABLED_FIELD
import dev.jz6.flexboard.patches.features.scrubdelete.CONFIG_FIELD
import dev.jz6.flexboard.patches.features.scrubdelete.CONFIG_START_KEY_FIELD
import dev.jz6.flexboard.patches.features.scrubdelete.CONFIG_STEP_TABLE_FIELD
import dev.jz6.flexboard.patches.features.scrubdelete.HANDLER_CONTEXT_FIELD
import dev.jz6.flexboard.patches.features.scrubdelete.HANDLER_CONTEXT_FIELD_NAME
import dev.jz6.flexboard.patches.features.scrubdelete.HANDLER_CONTEXT_OWNER
import dev.jz6.flexboard.patches.features.scrubdelete.INTEGER_VALUE_OF
import dev.jz6.flexboard.patches.features.scrubdelete.checkPreferenceStorePins
import dev.jz6.flexboard.patches.features.scrubdelete.resolvePreferenceGetInt
import dev.jz6.flexboard.patches.features.scrubdelete.PREFERENCE_STORE_GET
import dev.jz6.flexboard.patches.features.scrubdelete.SCRUB_DELETE_MOTION_EVENT_HANDLER
import dev.jz6.flexboard.patches.features.scrubdelete.SCRUB_MOTION_EVENT_HANDLER
import dev.jz6.flexboard.patches.features.scrubdelete.StartKeyChain
import dev.jz6.flexboard.patches.features.scrubdelete.branchOnStartKey
import dev.jz6.flexboard.patches.features.scrubdelete.resolveStartKeyChain
import dev.jz6.flexboard.patches.features.scrubdelete.ScrubDeleteConstructorFingerprint
import dev.jz6.flexboard.patches.features.scrubdelete.ScrubDispatchFingerprint
import dev.jz6.flexboard.patches.features.scrubdelete.ScrubEngineConstructorFingerprint
import dev.jz6.flexboard.patches.shared.ANDROID_CONTEXT
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.TypedRegister
import dev.jz6.flexboard.patches.shared.checkAssignable
import dev.jz6.flexboard.patches.shared.findInstanceField
import dev.jz6.flexboard.patches.shared.indexOfSoleCall
import dev.jz6.flexboard.patches.shared.invokeParameterType
import dev.jz6.flexboard.patches.shared.invokeRegisterAt
import dev.jz6.flexboard.patches.shared.invokeRegisterCount
import dev.jz6.flexboard.patches.shared.opcodeName

/**
 * Makes the scrub engine's feel adjustable from Gboard's own settings.
 *
 * Every value in `Lpvr;`, the engine's tuning struct, is **`public final`** — Gboard writes them
 * only inside `Lpvr;-><init>`. So none of them can be set with an `iput` from a patch; ART rejects
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
    description = "Reads the swipe length, word cap and hold delay from Gboard's preference " +
        "store, so the scrub engine's feel can be adjusted from its settings.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    // The entry that reaches the screen writing these preferences. Shipping the reader without it
    // would leave three values nothing can ever set.
    dependsOn(scrubSettingsScreenPatch)

    // Writes the swipe length's starting value on first run, rather than letting the read's
    // fallback stand in for it. See that patch for why the difference matters.
    dependsOn(seedDefaultsPatch)

    // Carries FlexboardSettingsActivity, which the manifest entry that patch writes names. The
    // merge has to happen from a bytecode patch; a resource patch cannot do it.
    extendWith("extensions/extension.mpe")

    execute {
        checkPreferenceStorePins()

        // Resolved once and handed to both edits below. Reading it is what lets them ask, mid
        // gesture, which key the finger went down on — see `scrubdelete/StartKey.kt`.
        val startKey = resolveStartKeyChain()

        ScrubEngineConstructorFingerprint.method.substituteHoldDelay(this)
        ScrubDeleteConstructorFingerprint.method.scaleStepTable(this)
        ScrubDispatchFingerprint.method.useStockDistanceFromBackspace(this, startKey)
        ScrubDispatchFingerprint.method.capWordCount(this, startKey)
    }
}

/**
 * Preference keys. Deliberately plain string literals rather than resource ids: `Lqhy;` exposes a
 * string-keyed getter alongside its resource-id one, and a *new* resource has no id until aapt2
 * recompiles, which is long after this patch runs. Literals sidestep the problem entirely.
 *
 * **These three are duplicated in `FlexboardSettingsActivity`**, which writes what this reads. They
 * cannot be shared: that class is compiled into the extension DEX, a separate Gradle module with no
 * dependency on the patches. Changing one without the other silently decouples the slider from the
 * value the engine uses, so both sides carry a comment pointing at the other.
 */
internal const val STEP_SCALE_KEY = "flexboard_scrub_step_scale"
internal const val HOLD_DELAY_KEY = "flexboard_scrub_hold_ms"
internal const val MAX_WORDS_KEY = "flexboard_max_words"

/**
 * Percent of Gboard's own swipe distance.
 *
 * Sixty is a middle: 36 shipped up to `1.1.0-dev.1` on the reasoning that Gboard's distance assumes
 * a thumb travelling from the backspace key and a gesture starting under your thumb wants less,
 * which was sound in principle and too aggressive in practice; 100 is Gboard's own, which asks for
 * the whole journey the patch exists to remove. With the word cap at its default of 1 a swipe still
 * deletes one word however far it travels, so this only changes how far that is.
 *
 * **Written on first run rather than left to this fallback** — see `seedDefaultsPatch`. The two
 * agree so that behaviour is the same either way, but the stored value is what a user actually gets
 * and what a later change to this number will deliberately *not* move them off.
 */
internal const val STEP_SCALE_DEFAULT = 60

/**
 * The percentage at which scaling is a no-op, so the table is left alone rather than multiplied by
 * 1.0.
 *
 * **Deliberately a separate constant from [STEP_SCALE_DEFAULT].** They were one constant once; when
 * the default moved off 100 that made the sentinel follow it, so the one value the user had asked
 * for became the one value with no effect. Splitting them is what has since let the default move to
 * 36, back to 100 and now to 60 without dragging the sentinel along — and what keeps 100 meaning
 * "leave Gboard's table alone" for anyone who sets it there.
 */
internal const val STEP_SCALE_IDENTITY = 100

/** Milliseconds. Zero reproduces the flick behaviour that shipped before this was adjustable. */
internal const val HOLD_DELAY_DEFAULT = 0

/** One word per swipe, however far it travels. Swiping back still restores. */
internal const val MAX_WORDS_DEFAULT = 1

/**
 * The slider's top position, and "no limit": at or above it the clamp is skipped entirely, leaving
 * the engine's progressive delete exactly as Gboard wrote it. **Not the default**, for the same
 * reason as [STEP_SCALE_IDENTITY] — sharing them would put the sentinel at 1 and disable the cap at
 * every setting.
 */
internal const val MAX_WORDS_NO_LIMIT = 10

/** `regs=11, ins=4` — asserted so the scratch register below is provably the one that was read. */
private const val ENGINE_CONSTRUCTOR_REGISTER_COUNT = 11

/** `this`, Context, Lpvo;, Lpvs;, and the wide delay — six registers. */
private const val ENGINE_CONSTRUCTOR_ARGUMENT_REGISTERS = 6

private const val DELETE_CONSTRUCTOR_REGISTER_COUNT = 12
private const val DELETE_CONSTRUCTOR_ARGUMENT_REGISTERS = 4

private const val FOUR_ARGUMENT_ENGINE_CONSTRUCTOR =
    "$SCRUB_MOTION_EVENT_HANDLER-><init>(Landroid/content/Context;Lpvo;Lpvs;J)V"

private const val THREE_ARGUMENT_ENGINE_CONSTRUCTOR =
    "$SCRUB_MOTION_EVENT_HANDLER-><init>(Landroid/content/Context;Lpvo;Lpvs;)V"

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
 * 16: invoke-direct/range {v1 .. v6}, ScrubMotionEventHandler-><init>(…Lpvs;J)V
 * ```
 *
 * Replacing the forwarded value means `Lpvr;->b:J` is *built* with the user's delay, so the gate in
 * `p()` still runs exactly as Gboard wrote it and simply compares against a different number.
 *
 * Anchored on the forwarded call rather than on the resource id or the conversion opcode: the call
 * gives every argument register directly, and neither a renumbered register nor a changed resource
 * id can silently mislead it.
 */
private fun MutableMethod.substituteHoldDelay(context: BytecodePatchContext) {
    // Resolved, not named: the store has a second (String, I)I method that reads the value as
    // text and parses it. Emitting that one would compile, verify and quietly parse a
    // preference that was never written as a string.
    val getInt = context.resolvePreferenceGetInt()
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

    // The callee's own signature says what each argument slot must be, so the Context claim is
    // read off the constructor being forwarded to rather than assumed from position.
    val contextArgument = TypedRegister(forward.invokeRegisterAt(1), forward.invokeParameterType(1))
    context.checkAssignable(
        contextArgument,
        ANDROID_CONTEXT,
        "Argument 1 of $FOUR_ARGUMENT_ENGINE_CONSTRUCTOR, which $PREFERENCE_STORE_GET is handed",
    )
    val contextRegister = contextArgument.register
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
            invoke-virtual { v$scratchRegister, v$delayRegister, v$delayHigh }, $getInt
            move-result v$scratchRegister
            int-to-long v$delayRegister, v$scratchRegister
        """,
        ExternalLabel(STOCK_HOLD_LABEL, forward),
    )
}

/**
 * Scales the distance table in place.
 *
 * `r()` counts how many entries of `Lpvs;->h:[F` the travelled distance has passed, and that count
 * is the number of words deleted — so the table *is* the swipe length, and scaling it by a
 * percentage is the whole knob. Unlike `Lpvr;`, the table field is not final, and array contents
 * are writable regardless, so this needs no constructor-argument substitution.
 *
 * Done in `ScrubDeleteMotionEventHandler.<init>` rather than the shared engine constructor because
 * the Context is still in a parameter register there — the engine constructor overwrites its own
 * Context register with `Resources` before the table is built.
 *
 * ## Why this one tests the sentinel
 *
 * Living in the delete constructor scopes it to delete, so it needs no sentinel test to avoid the
 * spacebar and inline-suggestion handlers. It tests one anyway, and is alone among the three in
 * having to: the other two run in the *shared* engine and so already gate on `Lpvs;->a:I` being
 * negative, which means turning Flexboard off silences them for free. Without its own copy of that
 * test this one would keep rescaling the distance of Gboard's own backspace swipe after the switch
 * was turned off — the one setting that would leak out of a disabled Flexboard.
 *
 * The gate branches to the same target the rest of the block uses, which is the instruction after
 * the super call: `return-void`. It reads no registers, so nothing there can be upset by the extra
 * edge.
 *
 * A positive scale preserves the strictly-increasing invariant the engine checks, so this cannot
 * trip the `Lpvs;->g:Z` bail-out. Where that flag is already set the table points at a *shared
 * static* fallback, so the scaling is skipped rather than corrupting global state.
 */
private fun MutableMethod.scaleStepTable(context: BytecodePatchContext) {
    // Resolved, not named: the store has a second (String, I)I method that reads the value as
    // text and parses it. Emitting that one would compile, verify and quietly parse a
    // preference that was never written as a string.
    val getInt = context.resolvePreferenceGetInt()
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

    val contextArgument = TypedRegister(
        superCall.invokeRegisterAt(1),
        superCall.invokeParameterType(1),
    )
    context.checkAssignable(
        contextArgument,
        ANDROID_CONTEXT,
        "Argument 1 of the engine super call, which $PREFERENCE_STORE_GET is handed",
    )
    val contextRegister = contextArgument.register
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
            iget v$table, v$configRegister, $CONFIG_START_KEY_FIELD
            if-gez v$table, :$STEPS_DONE_LABEL
            invoke-static { v$contextRegister }, $PREFERENCE_STORE_GET
            move-result-object v$store
            const-string v$table, "$STEP_SCALE_KEY"
            const/16 v$length, $STEP_SCALE_DEFAULT
            invoke-virtual { v$store, v$table, v$length }, $getInt
            move-result v$store
            const/16 v$table, $STEP_SCALE_IDENTITY
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

/**
 * Caps how many words one swipe can delete.
 *
 * `r()` turns the travelled distance into a **signed** count and dispatches it, and the consumer
 * deletes to match. Two sites produce that count — the in-table bucket walk and the past-the-table
 * extrapolation — and both are a multiply of a magnitude by the direction:
 *
 * ```
 * 106: mul-int/2addr v3, v7      # bucket index × direction        (opcode 0xb2)
 * 121: mul-int v3, v7, v0        # extrapolated magnitude × direction (opcode 0x92)
 * 123: if-nez v12, -> 135        # both converge here
 * 125: iget v0, v10, ->r:I       # the last count dispatched
 * 127: if-ne v0, v3, -> 135      # only re-dispatched when it changes
 * ```
 *
 * Clamping the count to ±N gives "at most N words per swipe" for free: swiping further produces a
 * raw count that clamps back to the same value, the comparison at 127 finds no change, and nothing
 * more is dispatched. Swiping back still reduces the magnitude, so restore keeps working.
 *
 * **Both sites are patched rather than the convergence at 123**, because 123 is a branch target:
 * dexlib2 keeps labels attached to the original instruction, so code inserted before it would be
 * jumped straight over by the two `goto`s that reach it — catching only the extrapolation path and
 * silently leaving the common one uncapped.
 *
 * The clamp must also land *before* offset 127. Clamping any later would leave the change detection
 * comparing a clamped `this.r` against an unclamped count, so every further pixel of travel would
 * re-dispatch the same value and delete another word — the exact opposite of the intent.
 *
 * The two multiply opcodes are asserted by name, which the `dis.py` caveat in `tools/apk/README.md`
 * otherwise warns against. It is safe here because the placeholders that tool prints encode the
 * opcode bytes directly — `binop2addrb2` is `0xb2` and `binop92` is `0x92` — and the Dalvik spec
 * fixes those as `mul-int/2addr` and `mul-int`.
 */
private fun MutableMethod.capWordCount(
    context: BytecodePatchContext,
    startKey: StartKeyChain,
) {
    // Resolved, not named: the store has a second (String, I)I method that reads the value as
    // text and parses it. Emitting that one would compile, verify and quietly parse a
    // preference that was never written as a string.
    val getInt = context.resolvePreferenceGetInt()
    val registerCount = implementation?.registerCount
        ?: error("$SCRUB_MOTION_EVENT_HANDLER->r has no implementation")
    check(registerCount == DISPATCH_REGISTER_COUNT) {
        "$SCRUB_MOTION_EVENT_HANDLER->r has $registerCount registers, expected " +
            "$DISPATCH_REGISTER_COUNT — refusing to guess which registers are free"
    }
    // `this`, whose type is simply the method's own class. Unlike the two constructors above, the
    // Context here is not an argument — it is read out of a field, so both halves need checking:
    // that this register can legally read that field, and that what comes back is a Context.
    val handler = TypedRegister(registerCount - DISPATCH_PARAMETER_WORDS, definingClass)
    val thisRegister = handler.register

    // Resolved by walking up, because the handler *inherits* this field rather than declaring it —
    // it lives on AbstractMotionEventHandler, one hop above. Emitting the declaring class's own
    // spelling keeps what is written provable against what was looked up.
    val handlerContext = context.findInstanceField(HANDLER_CONTEXT_OWNER, HANDLER_CONTEXT_FIELD_NAME)
        ?: error(
            "Neither $HANDLER_CONTEXT_OWNER nor anything above it declares a " +
                "`$HANDLER_CONTEXT_FIELD_NAME` field — the handler's Context has moved, and " +
                "$PREFERENCE_STORE_GET would be handed something else",
        )
    val resolvedHandlerContext =
        "${handlerContext.definingClass}->${handlerContext.name}:${handlerContext.type}"

    context.checkAssignable(
        handler,
        handlerContext.definingClass,
        "`this` in $SCRUB_MOTION_EVENT_HANDLER->r",
    )
    context.checkAssignable(
        handlerContext.type,
        ANDROID_CONTEXT,
        "The value of $resolvedHandlerContext, which $PREFERENCE_STORE_GET is handed",
    )

    // Boxing the payload is what identifies the count register beyond doubt.
    val boxIndex = instructions.indexOfSoleCall(INTEGER_VALUE_OF, "$SCRUB_MOTION_EVENT_HANDLER->r")
    val countRegister = instructions[boxIndex].invokeRegisterAt(0)

    val producers = instructions.withIndex()
        .filter { (_, instruction) ->
            instruction.opcodeName() in COUNT_PRODUCER_OPCODES &&
                (instruction as? OneRegisterInstruction)?.registerA == countRegister
        }
        .map { it.index }
    check(producers.size == EXPECTED_COUNT_PRODUCERS) {
        "Expected $EXPECTED_COUNT_PRODUCERS multiplies writing v$countRegister in " +
            "$SCRUB_MOTION_EVENT_HANDLER->r, found ${producers.size} — the count is no longer " +
            "computed as magnitude × direction at exactly the bucket and extrapolation sites"
    }

    // Named rather than picked lowest-first, because the low registers are not free. Gboard 18's
    // `Lpnu;-><init>` takes a fourth, int argument, and the two registers feeding it are both live
    // across this whole region: v4 holds the null `Lpnt;` and v1 the int, and they are read at
    // offsets 161 and 182. Staging a string key or a count in either would put the wrong type into
    // that constructor.
    //
    // These three are dead from both insertion points onward — v5 and v7 are the -1 constant and
    // the direction, both consumed by the multiply being patched, and v9 is a spent comparison
    // result. The register-count assertion above is what makes that analysis binding.
    //
    // On 17.7.7 the same three roles sat in v4, v6 and v8; the fourth constructor argument is what
    // shifted them. They were re-read out of the v18 method rather than shifted by hand.
    val (store, key, limit) = CLAMP_SCRATCH_REGISTERS
    check(setOf(store, key, limit).size == CLAMP_SCRATCH_REGISTERS.size) {
        "Scratch registers $CLAMP_SCRATCH_REGISTERS are not distinct"
    }
    check(countRegister !in CLAMP_SCRATCH_REGISTERS && thisRegister !in CLAMP_SCRATCH_REGISTERS) {
        "Scratch registers $CLAMP_SCRATCH_REGISTERS collide with the count (v$countRegister) or " +
            "`this` (v$thisRegister) in $SCRUB_MOTION_EVENT_HANDLER->r"
    }
    check(CLAMP_SCRATCH_REGISTERS.all { it < PACKED_INVOKE_REGISTER_LIMIT }) {
        "Scratch registers $CLAMP_SCRATCH_REGISTERS do not all fit a 35c invoke's nibbles"
    }

    // Descending, so inserting at one site cannot shift the index of the other.
    producers.sortedDescending().forEachIndexed { ordinal, producerIndex ->
        val done = "${CAP_DONE_LABEL}_$ordinal"
        val low = "${CAP_LOW_LABEL}_$ordinal"
        val clamp = "${CAP_CLAMP_LABEL}_$ordinal"
        val resume = instructions[producerIndex + 1]

        addInstructionsWithLabels(
            producerIndex + 1,
            """
                ${startKey.branchOnStartKey(thisRegister, store, key, done, clamp)}
                :$clamp
                iget-object v$store, v$thisRegister, $CONFIG_FIELD
                iget v$store, v$store, $CONFIG_START_KEY_FIELD
                if-gez v$store, :$done
                iget-object v$store, v$thisRegister, $resolvedHandlerContext
                invoke-static { v$store }, $PREFERENCE_STORE_GET
                move-result-object v$store
                const-string v$key, "$MAX_WORDS_KEY"
                const/16 v$limit, $MAX_WORDS_DEFAULT
                invoke-virtual { v$store, v$key, v$limit }, $getInt
                move-result v$store
                const/16 v$key, $MAX_WORDS_NO_LIMIT
                if-ge v$store, v$key, :$done
                if-lez v$store, :$done
                if-le v$countRegister, v$store, :$low
                move v$countRegister, v$store
                :$low
                neg-int v$store, v$store
                if-ge v$countRegister, v$store, :$done
                move v$countRegister, v$store
            """,
            ExternalLabel(done, resume),
        )
    }
}

/** store, table, length, index, element. */
private const val SCRATCH_REGISTERS_NEEDED = 5

/**
 * store, key, limit — dead at both clamp sites in `r()`. Deliberately *not* the lowest free
 * registers: v4 carries the null `Lpnt;` and v1 the trailing int that the dispatch path passes to
 * `Lpnu;-><init>`.
 */
private val CLAMP_SCRATCH_REGISTERS = listOf(5, 7, 9)

/** `regs=13, ins=3` — `this`, the MotionEvent and the boolean. */
private const val DISPATCH_REGISTER_COUNT = 13
private const val DISPATCH_PARAMETER_WORDS = 3

/** `mul-int/2addr` (0xb2) at the bucket site, `mul-int` (0x92) at the extrapolation site. */
private val COUNT_PRODUCER_OPCODES = setOf("MUL_INT_2ADDR", "MUL_INT")
private const val EXPECTED_COUNT_PRODUCERS = 2

private const val CAP_DONE_LABEL = "flexboard_capped"
private const val CAP_LOW_LABEL = "flexboard_cap_low"
private const val CAP_CLAMP_LABEL = "flexboard_clamp"

/**
 * Scratch for the distance edit, which inserts **earlier** in `r()` than the clamp does and cannot
 * reuse [CLAMP_SCRATCH_REGISTERS] — v5 is still live there, carrying the -1 the count multiply
 * consumes.
 *
 * Established by backward liveness across the whole method, not by reading forward from the
 * insertion point. That distinction is not pedantry: reading forward says v3 is free, because the
 * `add-int/lit8` at the head of the table walk writes it. It is **wrong**. The `if-gt` guarding the
 * walk branches straight past that write to the extrapolation path, which reads v3 as the previous
 * bucket's threshold — zero, on the first iteration, from the `const/4` above. Borrowing v3 would
 * have silently corrupted the extrapolated word count for swipes past the end of the table, on a
 * path that only fires for long swipes. Nothing would have crashed.
 *
 * At the delta subtraction, v6 through v9 are all genuinely dead on every path. Three are taken
 * here and v6 left alone, since the next instruction overwrites it anyway.
 */
private val DISTANCE_SCRATCH_REGISTERS = listOf(7, 8, 9)

/** Unique in `r()`, and its `move-result` is the magnitude the table walk consumes. */
private const val MATH_ABS_FLOAT = "Ljava/lang/Math;->abs(F)F"

private const val STOCK_DISTANCE_LABEL = "flexboard_stock_distance"
private const val SCALE_DELTA_LABEL = "flexboard_scale_delta"

/** A `35c` invoke addresses its registers in 4-bit nibbles, so v15 is the highest usable one. */
private const val PACKED_INVOKE_REGISTER_LIMIT = 16

/**
 * Gives a swipe that started on the backspace key Gboard's own distance per word, undoing
 * [scaleStepTable] for that one case.
 *
 * ## Why a multiply rather than a second table
 *
 * [scaleStepTable] rewrites `Lpvs;->h:[F` in place at construction, so by the time `r()` walks the
 * table the stock thresholds are gone. Restoring them would mean keeping a copy and swapping which
 * array the walk reads — two arrays to maintain, and a field to swap them through.
 *
 * The arithmetic makes that unnecessary. The walk compares `|delta|` against the table, and the
 * table has been multiplied through by `scale/100`. Comparing `|delta| · scale/100` against
 * `T · scale/100` is the same test as comparing `|delta|` against `T`, so multiplying the delta by
 * the *same* factor exactly recovers the stock thresholds. One `mul-float`, no second array, and
 * `scaleStepTable` is left exactly as it was.
 *
 * It also means the cost falls on the case that is rarer for a Flexboard user: a swipe-anywhere
 * gesture does no extra arithmetic at all, because the table it walks is already the one it wants.
 *
 * ## Where it goes
 *
 * `Math.abs(F)F` is called exactly once in `r()`, and its `move-result` is the `|delta|` the walk
 * then consumes. Inserting immediately after it means the multiply lands after every path that
 * produces a delta and before every path that reads one, which is not something an offset could
 * promise.
 *
 * Reading the scale preference here rather than caching it mirrors [capWordCount], which already
 * reads `flexboard_max_words` from this same method on every event that produces a count. The store
 * resolves to a process-wide singleton over an in-memory map, so this is a lookup rather than I/O —
 * and doing it the same way as the code beside it is worth more than saving one of them.
 */
private fun MutableMethod.useStockDistanceFromBackspace(
    context: BytecodePatchContext,
    startKey: StartKeyChain,
) {
    val getInt = context.resolvePreferenceGetInt()
    val registerCount = implementation?.registerCount
        ?: error("$SCRUB_MOTION_EVENT_HANDLER->r has no implementation")
    check(registerCount == DISPATCH_REGISTER_COUNT) {
        "$SCRUB_MOTION_EVENT_HANDLER->r has $registerCount registers, expected " +
            "$DISPATCH_REGISTER_COUNT — refusing to guess which registers are free"
    }

    val handler = TypedRegister(registerCount - DISPATCH_PARAMETER_WORDS, definingClass)
    val thisRegister = handler.register

    val handlerContext = context.findInstanceField(HANDLER_CONTEXT_OWNER, HANDLER_CONTEXT_FIELD_NAME)
        ?: error(
            "Neither $HANDLER_CONTEXT_OWNER nor anything above it declares a " +
                "`$HANDLER_CONTEXT_FIELD_NAME` field — the handler's Context has moved",
        )
    val resolvedHandlerContext =
        "${handlerContext.definingClass}->${handlerContext.name}:${handlerContext.type}"
    context.checkAssignable(
        handler,
        handlerContext.definingClass,
        "`this` in $SCRUB_MOTION_EVENT_HANDLER->r",
    )
    context.checkAssignable(
        handlerContext.type,
        ANDROID_CONTEXT,
        "The value of $resolvedHandlerContext, which $PREFERENCE_STORE_GET is handed",
    )

    // `Math.abs` is the one unambiguous landmark in the walk; the delta is simply what it is
    // handed. Walking back to where that register was last written finds the subtraction, which is
    // the insertion point — far enough up the method that registers are still free there.
    val absIndex = instructions.indexOfSoleCall(MATH_ABS_FLOAT, "$SCRUB_MOTION_EVENT_HANDLER->r")
    val deltaRegister = instructions[absIndex].invokeRegisterAt(0)
    val subtractIndex = (absIndex - 1 downTo 0).firstOrNull {
        (instructions[it] as? OneRegisterInstruction)?.registerA == deltaRegister &&
            !instructions[it].opcodeName().startsWith("IF_")
    } ?: error(
        "Nothing writes v$deltaRegister before $MATH_ABS_FLOAT in $SCRUB_MOTION_EVENT_HANDLER->r",
    )
    check(instructions[subtractIndex].opcodeName() == "SUB_FLOAT_2ADDR") {
        "Expected the magnitude to come from a `sub-float/2addr` in " +
            "$SCRUB_MOTION_EVENT_HANDLER->r, found " +
            "`${instructions[subtractIndex].opcode.name}` — the delta is computed differently now"
    }

    val (store, key, fallback) = DISTANCE_SCRATCH_REGISTERS
    check(DISTANCE_SCRATCH_REGISTERS.distinct().size == DISTANCE_SCRATCH_REGISTERS.size) {
        "Scratch registers $DISTANCE_SCRATCH_REGISTERS are not distinct"
    }
    check(deltaRegister !in DISTANCE_SCRATCH_REGISTERS &&
        thisRegister !in DISTANCE_SCRATCH_REGISTERS) {
        "Scratch registers $DISTANCE_SCRATCH_REGISTERS collide with the magnitude " +
            "(v$deltaRegister) or `this` (v$thisRegister) in $SCRUB_MOTION_EVENT_HANDLER->r"
    }
    check(DISTANCE_SCRATCH_REGISTERS.all { it < PACKED_INVOKE_REGISTER_LIMIT }) {
        "Scratch registers $DISTANCE_SCRATCH_REGISTERS do not all fit a 35c invoke's nibbles"
    }

    val resume = instructions[subtractIndex + 1]

    addInstructionsWithLabels(
        subtractIndex + 1,
        """
            ${startKey.branchOnStartKey(thisRegister, store, key, SCALE_DELTA_LABEL, STOCK_DISTANCE_LABEL)}
            :$SCALE_DELTA_LABEL
            iget-object v$store, v$thisRegister, $resolvedHandlerContext
            invoke-static { v$store }, $PREFERENCE_STORE_GET
            move-result-object v$store
            const-string v$key, "$STEP_SCALE_KEY"
            const/16 v$fallback, $STEP_SCALE_DEFAULT
            invoke-virtual { v$store, v$key, v$fallback }, $getInt
            move-result v$store
            if-lez v$store, :$STOCK_DISTANCE_LABEL
            int-to-float v$store, v$store
            const/high16 v$key, $ONE_HUNDRED_FLOAT
            div-float/2addr v$store, v$key
            mul-float/2addr v$deltaRegister, v$store
        """,
        ExternalLabel(STOCK_DISTANCE_LABEL, resume),
    )
}
