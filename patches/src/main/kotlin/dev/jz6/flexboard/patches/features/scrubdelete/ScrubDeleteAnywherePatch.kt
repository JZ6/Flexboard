package dev.jz6.flexboard.patches.features.scrubdelete

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import dev.jz6.flexboard.patches.features.scrubsettings.scrubTuningPatch
import dev.jz6.flexboard.patches.shared.ANDROID_CONTEXT
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.TypedRegister
import dev.jz6.flexboard.patches.shared.checkAssignable
import dev.jz6.flexboard.patches.shared.indexOfSoleCall
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
 * ## The sentinel is also the off switch
 *
 * Because *everything* Flexboard does keys off that one negative value, writing `KEYCODE_DEL`
 * instead turns the whole feature off and leaves Gboard exactly as it shipped — the delete swipe
 * back on the backspace key alone, at stock distance and stock hold. So the first edit is not a
 * constant but a choice, read from `flexboard_enabled` at construction.
 *
 * That covers three of the four edits for free: this patch's own `g()` gate, and
 * [scrubTuningPatch]'s hold delay and word cap, all test the sentinel already. Its step-table
 * scaling is the exception and carries its own copy of the test.
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

    // Greys out Gboard's glide typing row for as long as the above is forcing it off, so the
    // conflict shows up in the settings rather than as a setting that will not stay on.
    dependsOn(glideTypingRowPatch)

    // Supplies the hold delay and swipe length, and the settings rows behind them. Its defaults are
    // what make the widened gesture answer to a flick rather than Gboard's 200 ms press-and-drag.
    dependsOn(scrubTuningPatch)

    execute {
        ScrubDeleteConstructorFingerprint.method.chooseStartKeyFromPreference(this)
        ScrubHandleMotionEventFingerprint.method.acceptWildcardStartKey()
    }
}

/**
 * The switch on Flexboard's settings screen. Absent means on, so a fresh install behaves as it did
 * before the setting existed. Duplicated as a literal in `FlexboardSettingsActivity.java` — a
 * patch-added resource has no id until aapt2 recompiles, so bytecode cannot address one.
 */
internal const val SCRUB_ENABLED_KEY = "flexboard_enabled"

/** `KeyEvent.KEYCODE_DEL`, the key Gboard scopes its word-scrub delete to. */
private const val STOCK_START_KEYCODE = 67

/**
 * Written into the config in place of the keycode. Any negative value works; no Android keycode
 * is negative, so it cannot collide with a real key.
 */
private const val WILDCARD_START_KEYCODE = "-0x1"

/** `Lpbv;`, the per-handler config. Its constructor is the boundary the scratch scan stops at. */
private const val CONFIG_CONSTRUCTOR = "Lpbv;-><init>(IZIIIIII)V"

private const val DELETE_INIT_REGISTER_COUNT = 12

private val DELETE_INIT_PARAMETERS = listOf("Landroid/content/Context;", "Lpbr;")

/** A `35c` invoke packs its registers into nibbles, so nothing above v15 can be passed to one. */
private const val NIBBLE_REGISTER_LIMIT = 16

/** The store, the key string, and the default passed to the getter. */
private const val START_KEY_SCRATCH_NEEDED = 3

private const val STOCK_START_KEY_LABEL = "flexboard_stock_start_key"

/**
 * Asserted rather than adapted to. `p2` resolving to a different register on an unexpected build
 * is the failure mode that produced a bundle which would not apply once already — see
 * `docs/register-encoding.md`. Failing loudly here is far better.
 */
private const val SCRUB_HANDLE_REGISTER_COUNT = 13

private const val WILDCARD_LABEL = "flexboard_any_start_key"

/**
 * Overrides the `const/16 vN, 67` feeding `Lpbv;-><init>`'s first argument with the wildcard, but
 * only when the preference says so. The literal is matched rather than the position, and exactly one
 * match is required — the constructor also loads four negative event codes and an attr reference,
 * none of which can be confused with a keycode.
 *
 * The stock constant is **kept** and conditionally overwritten rather than replaced, which is what
 * makes "off" mean stock rather than broken:
 *
 * ```
 *   const/16 v1, 67                 <- stock, untouched
 *   …read flexboard_enabled…
 *   if-eqz vStore, :stock_start_key
 *   const/16 v1, -1
 *   :stock_start_key
 *   const/4 v2, 1                   <- stock resumes
 * ```
 *
 * Three things have to hold, and each is asserted rather than assumed:
 *
 *  - **Every instruction between the constant and `Lpbv;-><init>` is itself a `const`.** That is
 *    what proves the registers they write are dead at the insertion point: they are written before
 *    anything reads them, so borrowing them for the preference call cannot lose a live value. If
 *    Gboard ever computes one of those arguments instead of loading it, this fails loudly.
 *  - **Nothing writes the Context register before the insertion point.** It is derived from the
 *    Dalvik calling convention — parameters occupy the last `ins` registers — rather than guessed.
 *  - **The scratch registers fit in a nibble**, because a `35c` invoke cannot address above v15.
 *
 * One thing is worth naming because it looks alarming and is not. `v0` holds an **uninitialised**
 * `Lpbv;` across the inserted block, since `new-instance` runs before the arguments are built. That
 * is exactly the shape javac emits for `new Foo(cond ? a : b)`: a forward branch merges the same
 * uninitialised type from the same allocation site, which verifies fine. Only a *backward* branch,
 * or an exception handler, with an uninitialised reference live is rejected.
 */
private fun MutableMethod.chooseStartKeyFromPreference(context: BytecodePatchContext) {
    val registerCount = implementation?.registerCount
        ?: error("$SCRUB_DELETE_MOTION_EVENT_HANDLER-><init> has no implementation")
    check(registerCount == DELETE_INIT_REGISTER_COUNT) {
        "$SCRUB_DELETE_MOTION_EVENT_HANDLER-><init> has $registerCount registers, expected " +
            "$DELETE_INIT_REGISTER_COUNT — refusing to guess which registers are free"
    }
    check(parameterTypes.map(Any::toString) == DELETE_INIT_PARAMETERS) {
        "$SCRUB_DELETE_MOTION_EVENT_HANDLER-><init> takes $parameterTypes, " +
            "expected $DELETE_INIT_PARAMETERS"
    }

    val matches = instructions.withIndex().filter { (_, instruction) ->
        instruction.opcodeName() == "CONST_16" &&
            (instruction as? NarrowLiteralInstruction)?.narrowLiteral == STOCK_START_KEYCODE
    }
    check(matches.size == 1) {
        "Expected exactly one `const/16 …, $STOCK_START_KEYCODE` in " +
            "$SCRUB_DELETE_MOTION_EVENT_HANDLER-><init>, found ${matches.size}. " +
            "Gboard's scrub delete no longer starts on KEYCODE_DEL, or the constructor changed."
    }
    val (keyIndex, keyInstruction) = matches.single()
    val startKeyRegister = (keyInstruction as OneRegisterInstruction).registerA

    val configIndex = instructions.indexOfSoleCall(
        CONFIG_CONSTRUCTOR,
        "$SCRUB_DELETE_MOTION_EVENT_HANDLER-><init>",
    )
    check(keyIndex < configIndex) {
        "The keycode constant is at $keyIndex, after the $CONFIG_CONSTRUCTOR call at $configIndex"
    }

    // `this` occupies the first parameter register, so the Context is the one after it. Its type
    // is the declared parameter type, which is as authoritative as it gets — and asserted anyway,
    // because handing a non-Context to the preference store is what took the keyboard out in
    // 0.0.1-dev.1.
    val contextParameter = TypedRegister(
        registerCount - (parameterTypes.size + 1) + 1,
        parameterTypes.first().toString(),
    )
    context.checkAssignable(
        contextParameter,
        ANDROID_CONTEXT,
        "The first parameter of $SCRUB_DELETE_MOTION_EVENT_HANDLER-><init>, " +
            "which $PREFERENCE_STORE_GET is handed",
    )
    val contextRegister = contextParameter.register
    val clobbersContext = (0 until keyIndex).any {
        (instructions[it] as? OneRegisterInstruction)?.registerA == contextRegister
    }
    check(!clobbersContext) {
        "v$contextRegister is written before index $keyIndex, so it may no longer hold the Context"
    }

    val window = (keyIndex + 1 until configIndex).map { instructions[it] }
    val computed = window.firstOrNull { !it.opcodeName().startsWith("CONST") }
    check(computed == null) {
        "`${computed?.opcode?.name}` sits between the keycode constant and $CONFIG_CONSTRUCTOR — " +
            "the arguments are no longer all constants, so no register can be shown to be dead"
    }
    val scratch = window
        .mapNotNull { (it as? OneRegisterInstruction)?.registerA }
        .distinct()
        .filter { it != startKeyRegister && it != contextRegister && it < NIBBLE_REGISTER_LIMIT }
        .take(START_KEY_SCRATCH_NEEDED)
    check(scratch.size == START_KEY_SCRATCH_NEEDED) {
        "Only ${scratch.size} of the $START_KEY_SCRATCH_NEEDED registers needed are free in " +
            "$SCRUB_DELETE_MOTION_EVENT_HANDLER-><init>"
    }
    val (store, key, fallback) = scratch

    // Captured before the insertion shifts indices; the label resolves by instruction identity.
    val stockResumes = instructions[keyIndex + 1]

    addInstructionsWithLabels(
        keyIndex + 1,
        """
            invoke-static { v$contextRegister }, $PREFERENCE_STORE_GET
            move-result-object v$store
            const-string v$key, "$SCRUB_ENABLED_KEY"
            const/4 v$fallback, 0x1
            invoke-virtual { v$store, v$key, v$fallback }, $PREFERENCE_GET_BOOLEAN
            move-result v$store
            if-eqz v$store, :$STOCK_START_KEY_LABEL
            const/16 v$startKeyRegister, $WILDCARD_START_KEYCODE
        """,
        ExternalLabel(STOCK_START_KEY_LABEL, stockResumes),
    )
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
