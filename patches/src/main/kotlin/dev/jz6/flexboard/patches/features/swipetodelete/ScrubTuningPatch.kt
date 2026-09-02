package dev.jz6.flexboard.patches.features.swipetodelete

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import dev.jz6.flexboard.patches.features.swipetodelete.CONFIG_DISABLED_FIELD
import dev.jz6.flexboard.patches.features.swipetodelete.CONFIG_FIELD
import dev.jz6.flexboard.patches.features.swipetodelete.CONFIG_START_KEY_FIELD
import dev.jz6.flexboard.patches.features.swipetodelete.CONFIG_STEP_TABLE_FIELD
import dev.jz6.flexboard.patches.features.swipetodelete.HANDLER_CONTEXT_FIELD
import dev.jz6.flexboard.patches.features.swipetodelete.HANDLER_CONTEXT_FIELD_NAME
import dev.jz6.flexboard.patches.features.swipetodelete.HANDLER_CONTEXT_OWNER
import dev.jz6.flexboard.patches.features.swipetodelete.INTEGER_VALUE_OF
import dev.jz6.flexboard.patches.features.swipetodelete.checkPreferenceStorePins
import dev.jz6.flexboard.patches.features.swipetodelete.resolvePreferenceGetParsedInt
import dev.jz6.flexboard.patches.features.swipetodelete.PREFERENCE_STORE_GET
import dev.jz6.flexboard.patches.features.swipetodelete.SCRUB_DELETE_MOTION_EVENT_HANDLER
import dev.jz6.flexboard.patches.features.swipetodelete.SCRUB_MOTION_EVENT_HANDLER
import dev.jz6.flexboard.patches.features.swipetodelete.StartKeyChain
import dev.jz6.flexboard.patches.features.swipetodelete.branchOnStartKey
import dev.jz6.flexboard.patches.features.swipetodelete.resolveStartKeyChain
import dev.jz6.flexboard.patches.features.swipetodelete.scrubDeleteConstructorFingerprint
import dev.jz6.flexboard.patches.features.swipetodelete.scrubDispatchFingerprint
import dev.jz6.flexboard.patches.features.swipetodelete.scrubEngineConstructorFingerprint
import dev.jz6.flexboard.patches.shared.ANDROID_CONTEXT
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.PACKED_INVOKE_REGISTER_LIMIT
import dev.jz6.flexboard.patches.shared.TypedRegister
import dev.jz6.flexboard.patches.shared.assertRegisterCount
import dev.jz6.flexboard.patches.shared.checkAssignable
import dev.jz6.flexboard.patches.shared.findInstanceField
import dev.jz6.flexboard.patches.shared.indexOfSoleCall
import dev.jz6.flexboard.patches.shared.invokeParameterType
import dev.jz6.flexboard.patches.shared.invokeRegisterAt
import dev.jz6.flexboard.patches.shared.invokeRegisterCount
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.validateScratchRegisters

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
    description = "Reads the word cap from Gboard's preference store, so it can be adjusted " +
        "from settings. Hold delay is fixed at 0 — its settings row was dropped; swipe length " +
        "scaling is parked pending the investigation in docs/roadmap.md.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    // The seed is swipe-scoped (it writes exactly the keys this patch reads), so it hangs off the
    // swipe tree rather than the base patch — deselecting Swipe to Delete now skips both.

    execute {
        checkPreferenceStorePins()

        // Resolved once and handed to both edits below. Reading it is what lets them ask, mid
        // gesture, which key the finger went down on — see `swipetodelete/StartKey.kt`.
        val startKey = resolveStartKeyChain()

        scrubEngineConstructorFingerprint().method.substituteHoldDelay(this)
        // Swipe-length scaling disabled — the slider has no effect on distance for now.
        // See the swipe-length investigation in docs/roadmap.md.
        scrubDispatchFingerprint().method.capWordCount(this, startKey)
    }
}

internal const val HOLD_DELAY_KEY = "flexboard_swipe_hold_ms"
internal const val MAX_WORDS_KEY = "flexboard_swipe_max_words"

/** Milliseconds. Zero reproduces the flick behaviour that shipped before this was adjustable. */
internal const val HOLD_DELAY_DEFAULT = 0

/** One word per swipe, however far it travels. Swiping back still restores. */
internal const val MAX_WORDS_DEFAULT = 1

/**
 * The slider's top position, and "no limit": at or above it the clamp is skipped entirely, leaving
 * the engine's progressive delete exactly as Gboard wrote it. **Not the default**, for the same
 * reason a sentinel should never track a default — sharing them would put the sentinel at 1 and
 * disable the cap at every setting.
 */
internal const val MAX_WORDS_NO_LIMIT = 10

/**
 * The sliders' span, mirrored into `flexboard_settings.xml` by attributes — see the key docs above
 * for why a file the build checker does not compare would be a silent breakage. These exist on the
 * Kotlin side only so `check_shared_constants.py` can assert the XML agrees; nothing emits them.
 */
internal const val MAX_WORDS_MIN = 1
internal const val HOLD_DELAY_MIN = 0
internal const val HOLD_DELAY_MAX = 300

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
    val getInt = context.resolvePreferenceGetParsedInt()
    assertRegisterCount(ENGINE_CONSTRUCTOR_REGISTER_COUNT, THREE_ARGUMENT_ENGINE_CONSTRUCTOR)

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
    val getInt = context.resolvePreferenceGetParsedInt()
    val (thisRegister, resolvedHandlerContext) = resolveDispatchEntry(context)

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
    validateScratchRegisters(
        CLAMP_SCRATCH_REGISTERS,
        listOf(countRegister, thisRegister),
        "$SCRUB_MOTION_EVENT_HANDLER->r",
    )

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

/** Unique in `r()`, and its `move-result` is the magnitude the table walk consumes. */
private const val MATH_ABS_FLOAT = "Ljava/lang/Math;->abs(F)F"

private const val STOCK_DISTANCE_LABEL = "flexboard_stock_distance"
private const val SCALE_DELTA_LABEL = "flexboard_scale_delta"

/**
 * Resolves the handler's inherited `Context` field, failing the patch when it has moved.
 *
 * [capWordCount] resolves this field and runs the assignability checks against it. A second
 * caller, the parked swipe-length emitter, is why it is a helper rather than inline; it is kept
 * as one because this is the single place the "Context has moved" error is spelled. The longer message is kept: it names [PREFERENCE_STORE_GET], which is what actually
 * receives the wrong value when this field is absent, and makes the failure easier to trace.
 */
private fun BytecodePatchContext.resolveHandlerContext() =
    findInstanceField(HANDLER_CONTEXT_OWNER, HANDLER_CONTEXT_FIELD_NAME)
        ?: error(
            "Neither $HANDLER_CONTEXT_OWNER nor anything above it declares " +
                "`$HANDLER_CONTEXT_FIELD_NAME` — the handler's Context has moved, and " +
                "$PREFERENCE_STORE_GET would be handed something else",
        )

/**
 * The handler register and its resolved Context field, established once for both dispatch-site
 * edits in `r()`.
 *
 * Asserts the register count, derives `this` from it, resolves the inherited Context field, and
 * checks assignability both ways — the same six steps [capWordCount] and
 * the parked swipe-length emitter used to carry inline. Returns the `this` register and the
 * resolved Context field descriptor so each caller can emit against them without duplicating the
 * proof.
 */
private fun MutableMethod.resolveDispatchEntry(context: BytecodePatchContext): Pair<Int, String> {
    val registerCount = assertRegisterCount(
        DISPATCH_REGISTER_COUNT,
        "$SCRUB_MOTION_EVENT_HANDLER->r",
    )
    val handler = TypedRegister(registerCount - DISPATCH_PARAMETER_WORDS, definingClass)
    val handlerContext = context.resolveHandlerContext()
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
    return Pair(handler.register, resolvedHandlerContext)
}
