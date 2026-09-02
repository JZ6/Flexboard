package dev.jz6.flexboard.patches.features.swipetodelete

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import dev.jz6.flexboard.patches.features.swipetodelete.scrubTuningPatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.assertRegisterCount
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.destinationRegistersOrEmpty
import dev.jz6.flexboard.patches.shared.indexOfSoleCall
import dev.jz6.flexboard.patches.shared.invokeRegisterAt
import dev.jz6.flexboard.patches.shared.invokeRegisterCount
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.selectedSettingsSections
import dev.jz6.flexboard.patches.shared.SettingsSection
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
 * iget   v5, v5, Lpnu;->c:I     # keycode of the key under the finger
 * iget   v6, v1, Lpvs;->a:I     # the configured start keycode, 67 for delete
 * if-ne  v5, v6, -> bail
 * ```
 *
 * So this patch does not implement a gesture. It widens where Gboard's own gesture is allowed to
 * start, and where it is allowed to travel, in three edits:
 *
 *  1. `ScrubDeleteMotionEventHandler.<init>` passes **-1** instead of `KEYCODE_DEL`.
 *  2. `g()` skips the comparison when the configured keycode is negative.
 *  3. `g()` gives the tracking rect the full keyboard height, not one key's worth.
 *
 * A negative sentinel is what makes the second edit register-free: every Android keycode is
 * non-negative, so the test is `if-ltz` — format 21t, one register, no constant, and therefore no
 * need to prove some register is dead at that point in a 259-instruction method. The same sentinel
 * is what [scrubTuningPatch] tests to scope its values to this handler, and what scopes the
 * full-height rect below.
 *
 * ## There is no in-app off switch, deliberately
 *
 * The sentinel used to be a *choice*, read from a `flexboard_enabled` preference at construction, so
 * that turning Flexboard off left Gboard exactly as it shipped. That was removed: reading a
 * preference there cost three scratch registers whose deadness had to be re-proved against every
 * Gboard build, and it was the single most intricate insertion in the project. Morphe already lets
 * a user not apply a patch, which is where turning a feature off belongs.
 *
 * One consequence worth stating plainly: [forceScrubPreferencesPatch] now forces glide typing off
 * unconditionally for as long as this patch is applied, and [glideTypingRowPatch] greys those rows
 * out statically rather than through a dependency. Getting glide typing back means re-patching
 * without this patch.
 *
 * The sentinel still does the scoping work it always did. `ScrubMoveMotionEventHandler` (spacebar
 * cursor) and `InlineSuggestionScrubSpaceMotionEventHandler` both pass 62, so their gate still
 * enforces and none of Flexboard's tuning reaches them.
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

    dependsOn(basePatch)

    // Widening the gate is pointless if the handler is never attached, and unusable while glide
    // typing is live on the same pointer stream.
    dependsOn(forceScrubPreferencesPatch)

    // Greys out Gboard's glide typing row for as long as the above is forcing it off, so the
    // conflict shows up in the settings rather than as a setting that will not stay on.
    dependsOn(glideTypingRowPatch)

    // Supplies the word cap and the settings row behind it, and substitutes the hold delay so the
    // widened gesture answers to a flick rather than Gboard's 200 ms press-and-drag. It no longer
    // supplies a swipe length: that emitter is parked, and there is no row for either it or the
    // delay.
    dependsOn(scrubTuningPatch)

    execute {
        scrubDeleteConstructorFingerprint().method.writeWildcardStartKey()

        // Both edit `g()`, and both add an instruction to it. Neither depends on running first:
        // each locates what it needs by a shape the other does not produce. See the note on
        // [trackAcrossFullKeyboard] for why that mattered enough to design for.
        //
        // Resolved once and shared. Two calls would match the same method twice — correct, since
        // matching runs against the original definition rather than the mutated one, but it is
        // wasted work and reads as though the second edit wanted a fresh view of the first's
        // output, which it explicitly does not.
        val handleMotionEvent = scrubHandleMotionEventFingerprint().method
        handleMotionEvent.acceptWildcardStartKey()
        handleMotionEvent.trackAcrossFullKeyboard()

        // Registered last, on purpose. A failing patch does not abort the run: the patcher records
        // the exception and moves on, and `settingsScreenPatch` — which did not fail — still
        // finalizes and reads this set. Registering before the edits above would ship the Swipe
        // rows for a build whose bytecode never got the feature.
        selectedSettingsSections += SettingsSection.SWIPE_TO_DELETE
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
 * `Lpvs;`, the per-handler config. Used to bound the scan proving three registers dead; now only to
 * confirm the keycode constant being replaced is the one feeding it.
 */
private const val CONFIG_CONSTRUCTOR = "Lpvs;-><init>(IZIIIIII)V"

/**
 * Asserted rather than adapted to. `p2` resolving to a different register on an unexpected build
 * is the failure mode that produced a bundle which would not apply once already — see
 * `docs/register-encoding.md`. Failing loudly here is far better.
 */
private const val SCRUB_HANDLE_REGISTER_COUNT = 13

private const val WILDCARD_LABEL = "flexboard_any_start_key"

private const val STOCK_VERTICAL_BOUNDS_LABEL = "flexboard_stock_vertical_bounds"

/**
 * The view the tracking rect is measured in, and whose height the widened rect spans.
 *
 * Neither `getWidth` nor `getHeight` is declared here — both are inherited from `View`. Naming the
 * subclass anyway is not a liberty: it is exactly how Gboard spells the `getWidth` call two
 * instructions above where this patch inserts, so dex resolution through the superclass chain is
 * demonstrated by the very method being edited rather than assumed.
 */
private const val KEYBOARD_VIEW =
    "Lcom/google/android/libraries/inputmethod/widgets/SoftKeyboardView;"

private const val KEYBOARD_VIEW_GET_WIDTH = "$KEYBOARD_VIEW->getWidth()I"
private const val KEYBOARD_VIEW_GET_HEIGHT = "$KEYBOARD_VIEW->getHeight()I"

private const val RECT = "Landroid/graphics/Rect;"
private const val RECT_LEFT = "$RECT->left:I"
private const val RECT_TOP = "$RECT->top:I"
private const val RECT_BOTTOM = "$RECT->bottom:I"

/**
 * Replaces the `const/16 vN, 67` feeding `Lpvs;-><init>`'s first argument with the wildcard.
 *
 * The literal is matched rather than the position, and exactly one match is required — the
 * constructor also loads four negative event codes and an attr reference, none of which can be
 * confused with a keycode. The ordering check against the `Lpvs;-><init>` call is what proves the
 * constant found is the one feeding the config rather than something later in the method.
 *
 * ## This used to be far more than one instruction
 *
 * Until the master switch was removed, the stock constant was *kept* and conditionally overwritten
 * from a preference, so that "off" meant byte-for-byte stock Gboard:
 *
 * ```
 *   const/16 v1, 67                 <- stock, untouched
 *   …read flexboard_enabled…
 *   if-eqz vStore, :stock_start_key
 *   const/16 v1, -1
 *   :stock_start_key
 * ```
 *
 * Reading that preference needed three scratch registers, and proving three registers dead inside a
 * constructor is not free. It rested on: every instruction between the constant and
 * `Lpvs;-><init>` being itself a `const` (which is what showed those registers were written before
 * anything read them); the `Context` parameter register being underived from the Dalvik calling
 * convention and unclobbered; and all three scratch registers fitting in a nibble, because a `35c`
 * invoke cannot address above v15. It also had to be argued that the **uninitialised** `Lpvs;` live
 * in v0 across the inserted block was fine — it is, being the shape javac emits for
 * `new Foo(cond ? a : b)`, where a forward branch merges the same uninitialised type from the same
 * allocation site; only a *backward* branch or an exception handler would be rejected.
 *
 * Every one of those was a fact about one Gboard build's register allocation, and R8 re-rolls that
 * every release. None of it survives here, which is the point: "off" is now "do not apply the
 * patch", and the patch itself is one `replaceInstruction`. The reasoning is kept in
 * `docs/motion-event-handlers.md` in case the switch is ever wanted back.
 */
private fun MutableMethod.writeWildcardStartKey() {
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

    // Order alone does not prove the constant feeds the config: a future build could have another
    // `const/16 …, 67` earlier in the method and nothing here would say so. Assert the call reads
    // the exact register the constant landed in, so that build fails loudly instead of being
    // rewritten on position.
    val configCall = instructions[configIndex]
    check((0 until configCall.invokeRegisterCount()).any { configCall.invokeRegisterAt(it) == startKeyRegister }) {
        "The keycode constant is in v$startKeyRegister, which the $CONFIG_CONSTRUCTOR call at " +
            "$configIndex never reads — the constant being replaced is not the one feeding the config"
    }

    replaceInstruction(keyIndex, "const/16 v$startKeyRegister, $WILDCARD_START_KEYCODE")
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
    assertRegisterCount(SCRUB_HANDLE_REGISTER_COUNT, "$SCRUB_MOTION_EVENT_HANDLER->g")

    // Matched on the *gate's* shape — a read of the field immediately tested by `if-ne` — rather
    // than on being the only read in the method. [trackAcrossFullKeyboard] adds a second read of
    // the same field, tested by `if-gez`, so "the only read" stopped being true the moment these
    // two patches shared a method. Keying on the shape means neither has to run first.
    val body = instructions
    val reads = body.withIndex().filter { (index, instruction) ->
        instruction.opcodeName() == "IGET" &&
            instruction.usesField(CONFIG_START_KEY_FIELD) &&
            body.getOrNull(index + 1)?.opcodeName() == "IF_NE"
    }
    check(reads.size == 1) {
        "Expected exactly one read of $CONFIG_START_KEY_FIELD tested by `if-ne` in " +
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
 * Gives the tracking rect the full keyboard height, so a swipe can wander off its starting row.
 *
 * ## What the rect is
 *
 * When a scrub begins, `g()` captures the bounds of the key the finger went down on and keeps them
 * in `ScrubMotionEventHandler->h:Rect`. Every subsequent move is tested against it, and the first
 * point outside ends the gesture:
 *
 * ```
 * Rect;->contains(x, y)      # in r(), on every move
 * if-eqz -> …                # outside: dispatch the leave code, clear `f`, gesture over
 * ```
 *
 * Gboard then widens that rect — but on one axis only:
 *
 * ```
 * Lsvk;->F(startKey, keyboardView, rect)   # the starting key's bounds
 * rect.left   = 0                          # ← full keyboard width
 * rect.right  = keyboardView.getWidth()
 * rect.top    = (int)((float)rect.top    - Lpvr;->g:F)   # one key tall, plus 4mm
 * rect.bottom = (int)((float)rect.bottom + Lpvr;->g:F)
 * ```
 *
 * `Lpvr;->g:F` is 4mm on 18.0.3 (`0x7f070935`, raw `0x405` — mantissa 4, unit `MM`). Note it is an
 * **outset**, not an inset: `sub-float` on the top edge and `add-float` on the bottom widen the
 * rect rather than shrink it. The corridor is therefore one key row plus 4mm either side.
 *
 * ## Why that is wrong for this patch specifically
 *
 * For stock Gboard the corridor is generous, because the gesture only ever starts on backspace and
 * a thumb dragging sideways from a known key does not stray far. Flexboard's whole premise is that
 * it starts *anywhere*, so the corridor is anchored wherever the finger happened to land — and a
 * swipe that begins on the top letter row and drifts naturally downward leaves it mid-gesture. The
 * gesture does not degrade, it cancels, which reads as the swipe simply not working.
 *
 * So this mirrors what Gboard already does horizontally, on the other axis. It is not a new policy
 * being invented; it is the same policy applied to the axis Google left alone because they never
 * needed it.
 *
 * ## Shape of the edit
 *
 * The stock computation is **kept and then overwritten**. That used to be justified by a sibling,
 * `chooseStartKeyFromPreference`, which kept the stock keycode and conditionally replaced it from a
 * preference — the link has been dangling since that was replaced by [writeWildcardStartKey], which
 * overwrites outright, and by the removal of the master switch that made "off means byte-for-byte
 * stock" reachable at all.
 *
 * The reason that survives both is the one that was always the stronger half: the insert is a
 * single forward branch, where an excision would need two merge points. A few wasted instructions
 * once per gesture is a cheap price for not rewriting Gboard's control flow.
 *
 * ```
 *   …stock top/bottom outset runs…
 *   iget    vS, vConfig, Lpvs;->a:I
 *   if-gez  vS, :stock_vertical_bounds     # a real keycode: leave Gboard's rect alone
 *   const/4 vS, 0
 *   iput    vS, vRect, Rect->top:I
 *   invoke-virtual { vView }, SoftKeyboardView;->getHeight()I
 *   move-result vS
 *   iput    vS, vRect, Rect->bottom:I
 *   :stock_vertical_bounds                 # stock resumes
 * ```
 *
 * `vS` is deliberately the register the stock `iput` to `bottom` reads, which holds an `int` there.
 * Our path leaves an `int` in it too, on both edges of the branch, so the merge is type-identical
 * and no register is defined on only one edge — the verifier failure the note at the end of
 * `docs/motion-event-handlers.md` warns about. Nothing else is touched: the `float` scratch the
 * stock outset uses is written on both paths, because the stock code still runs on both.
 *
 * The zero is materialised with `const/4` rather than borrowed from the register Gboard uses for
 * `rect.left`. Reusing that one would mean proving it still holds zero thirty instructions later,
 * and one extra instruction is cheaper than a liveness argument that could quietly stop being true.
 *
 * The gate is the same wildcard sentinel everything else keys off, so `ScrubMoveMotionEventHandler`
 * (spacebar cursor drag) and `InlineSuggestionScrubSpaceMotionEventHandler` — which share this
 * method and pass 62 — keep their one-key corridor.
 */
private fun MutableMethod.trackAcrossFullKeyboard() {
    assertRegisterCount(SCRUB_HANDLE_REGISTER_COUNT, "$SCRUB_MOTION_EVENT_HANDLER->g")

    val body = instructions

    fun soleWrite(field: String): IndexedValue<TwoRegisterInstruction> {
        val matches = body.withIndex().filter { (_, instruction) ->
            instruction.opcodeName() == "IPUT" && instruction.usesField(field)
        }
        check(matches.size == 1) {
            "Expected exactly one write to $field in $SCRUB_MOTION_EVENT_HANDLER->g, " +
                "found ${matches.size}"
        }
        val (index, instruction) = matches.single()
        return IndexedValue(index, instruction as TwoRegisterInstruction)
    }

    // The rect register, taken from the write this patch replaces the effect of.
    val (bottomIndex, bottomWrite) = soleWrite(RECT_BOTTOM)
    val rectRegister = bottomWrite.registerB
    val scratchRegister = bottomWrite.registerA

    // Every edge has to be the same Rect, or "the rect" is not one object and none of the reasoning
    // above holds.
    for (field in listOf(RECT_LEFT, RECT_TOP)) {
        val (_, write) = soleWrite(field)
        check(write.registerB == rectRegister) {
            "The write to $field in $SCRUB_MOTION_EVENT_HANDLER->g targets v${write.registerB}, " +
                "but $RECT_BOTTOM targets v$rectRegister — these are not the same Rect"
        }
    }

    // The override rewrites both edges after the `bottom` write, which is only sound if the stock
    // `top` write happened earlier. If a build ever swapped them, the override's `top = 0` would be
    // overwritten back and the corridor would silently reopen on one axis — so it is checked.
    val (topIndex) = soleWrite(RECT_TOP)
    check(topIndex < bottomIndex) {
        "The write to $RECT_TOP is at $topIndex, after the write to $RECT_BOTTOM at " +
            "$bottomIndex — the full-height override below would leave the stock top edge in place"
    }

    // Gboard's own full-width override, which this mirrors. Asserting it is still there is what
    // makes "we are widening the other axis the same way" a fact rather than a story about an
    // older build: if Google ever stops widening horizontally, the premise needs re-examining.
    val widthIndex = body.indexOfSoleCall(
        KEYBOARD_VIEW_GET_WIDTH,
        "$SCRUB_MOTION_EVENT_HANDLER->g",
    )
    val viewRegister = body[widthIndex].invokeRegisterAt(0)
    check(widthIndex < bottomIndex) {
        "$KEYBOARD_VIEW_GET_WIDTH is called at $widthIndex, after the write to $RECT_BOTTOM at " +
            "$bottomIndex — the rect is not built in the order this patch reads it"
    }

    // Order-independent by construction: this asks which object every read of the field goes
    // through, not which read comes where. [acceptWildcardStartKey] may or may not already have
    // inserted its branch, and adding a read of its own is exactly what this function then does.
    val configRegisters = body
        .filter { it.opcodeName() == "IGET" && it.usesField(CONFIG_START_KEY_FIELD) }
        .map { (it as TwoRegisterInstruction).registerB }
        .toSet()
    check(configRegisters.size == 1) {
        "Reads of $CONFIG_START_KEY_FIELD in $SCRUB_MOTION_EVENT_HANDLER->g go through " +
            "${configRegisters.size} different registers ($configRegisters); with more than one " +
            "there is no single register this patch can safely read the sentinel from"
    }
    val configRegister = configRegisters.single()

    // Both registers are read at the insertion point but derived from instructions well before it
    // — the view register some twenty instructions earlier. Ordering was asserted above; that is
    // not the same as liveness. Nothing here proves the registers still hold what they held at the
    // derivation, and R8 is free to reuse a register once its last stock read is gone. So assert
    // it: if anything between the derivation and the insertion writes either register, the values
    // this emission reads are not the ones it was reasoned about, and the emitted
    // `getHeight`/`iget` would run against whatever replaced them.
    val borrowed = mapOf(
        viewRegister to "the $KEYBOARD_VIEW_GET_WIDTH receiver",
        configRegister to "the $CONFIG_START_KEY_FIELD holder",
    )
    body.subList(widthIndex + 1, bottomIndex + 1).forEach { instruction ->
        // Wide writes take two registers, so a `-wide` landing on v4 clobbers v5 as well.
        instruction.destinationRegistersOrEmpty().forEach { written ->
            val what = borrowed[written] ?: return@forEach
            error(
                "v$written — $what — is overwritten by `${instruction.opcodeName()}` between the " +
                    "derivation at $widthIndex and the insertion at ${bottomIndex + 1} in " +
                    "$SCRUB_MOTION_EVENT_HANDLER->g; it no longer holds the value this patch reads",
            )
        }
    }

    // Captured before the insertion shifts indices; the label resolves by instruction identity.
    val stockResumes = body[bottomIndex + 1]

    addInstructionsWithLabels(
        bottomIndex + 1,
        """
            iget v$scratchRegister, v$configRegister, $CONFIG_START_KEY_FIELD
            if-gez v$scratchRegister, :$STOCK_VERTICAL_BOUNDS_LABEL
            const/4 v$scratchRegister, 0x0
            iput v$scratchRegister, v$rectRegister, $RECT_TOP
            invoke-virtual { v$viewRegister }, $KEYBOARD_VIEW_GET_HEIGHT
            move-result v$scratchRegister
            iput v$scratchRegister, v$rectRegister, $RECT_BOTTOM
        """,
        ExternalLabel(STOCK_VERTICAL_BOUNDS_LABEL, stockResumes),
    )
}
