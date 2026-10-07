package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import dev.jz6.flexboard.patches.features.undo.LATIN_IME
import dev.jz6.flexboard.patches.features.undo.latinImeHandleEventFingerprint
import dev.jz6.flexboard.patches.shared.InvokeKind
import dev.jz6.flexboard.patches.shared.assertRegisterCount
import dev.jz6.flexboard.patches.shared.branchTargetIndex
import dev.jz6.flexboard.patches.shared.callsMethod
import dev.jz6.flexboard.patches.shared.checkFieldExists
import dev.jz6.flexboard.patches.shared.checkInvokeKind
import dev.jz6.flexboard.patches.shared.destinationRegistersOrEmpty
import dev.jz6.flexboard.patches.shared.invokeRegisterAt
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.sole
import dev.jz6.flexboard.patches.shared.usesField
import dev.jz6.flexboard.patches.shared.validateScratchRegisters

/**
 * Gboard's own key code for reverting the last autocorrection (`REVERT_AUTO_CORRECTION` in its
 * key-code name table). Handed to the decoder, never dispatched as an event by stock Gboard, so the
 * swipe-up event that carries it cannot be confused with anything Gboard sends itself.
 */
internal const val REVERT_AUTO_CORRECTION = -10076

/** Physical-keyboard delete-word: the one stock path that asks the decoder for a revert. */
private const val DELETE_WORD = -10133

private const val HANDLE_EVENT_REGISTERS = 34

// The physical-keyboard revert, as LatinIme->q runs it on 18.0.3. Every reference is asserted
// against the stock block before the copy is emitted, so a moved letter fails the patch.
private const val INPUT_STATE = "$LATIN_IME->x:Lftq;"
private const val REVERT_GUARD = "Lftq;->o:Z"

/** The builder's arguments after the event and the key code, in the order the stock block loads them. */
private val REQUEST_ARGUMENTS = listOf(
    "$LATIN_IME->m:Z",
    "$LATIN_IME->p:J",
    "$LATIN_IME->o:I",
    "$LATIN_IME->n:Z",
    "$LATIN_IME->ap:Lppa;",
)
private const val BUILD_REQUEST = "Lful;->d(Lnur;IZJIZLppa;)Lyhg;"
private const val DECODER = "$LATIN_IME->B()Lfsf;"
private const val DECODER_CLOCK = "$LATIN_IME->z()J"
private const val DECODE = "Lfsf;->k(JLyhg;Z)Lyct;"
private const val EVENT_TIME = "Lnur;->j:J"
private const val APPLY_RESULT = "$LATIN_IME->E(ZJZ)V"

// The delete-word test that leads into that block.
private const val KEY_CODE = "Lpnu;->c:I"
private const val IS_PHYSICAL = "Lnur;->k()Z"

// The end of Gboard's list of keys it handles itself, which is where -10076 joins that path.
private const val SUB_HANDLER = "$LATIN_IME->D()Lrqp;"
private const val SUB_HANDLER_CLAIMS = "Lrqp;->h(I)Z"

private const val ROUTE = "flexboard_revert_route"
private const val STOCK = "flexboard_revert_stock"
private const val DONE = "flexboard_revert_done"
private const val EXIT = "flexboard_revert_exit"

/**
 * Teaches `LatinIme->q` to revert the last autocorrection when it receives [REVERT_AUTO_CORRECTION].
 *
 * ## Why the decoder, and not -10045
 *
 * Gboard 18.0.3 has three things called undo, and only one of them is backspace's.
 *
 *  - **-10045 is a generic undo.** UndoExtension consumes it after the IME and steps its undo stack
 *    back one chunk, whatever that chunk was: typing, a deletion, or an autocorrection. With no
 *    autocorrection pending it undoes something else.
 *  - **EditTrackingImeWrapper's -10045 on backspace** fires only for GenAI post-corrections, behind
 *    a flag that installs the wrapper and the "Undo auto-correct on backspace" setting.
 *  - **Ordinary autocorrect revert on backspace happens in the native decoder.** Backspace reaches
 *    Delight5 as key 8, and the decoder alone knows whether the last commit was an autocorrection
 *    it can put back. No Java field mirrors that state.
 *
 * Gboard already asks the decoder for exactly that revert: on a physical keyboard, delete-word
 * builds a decoder request with key code -10076, applies the result, and falls back to deleting a
 * word only when the decoder returns nothing. This reuses that request without the fallback, so
 * the decoder stays the gate: a swipe with no autocorrection to revert does nothing.
 *
 * ## Two insertions, both keyed on -10076
 *
 * 1. **Route.** Stock `q` drops any key code missing from its handled list, and -10076 is missing.
 *    The list ends at the `D()` sub-handler query; one `const`/`if-eq` before it sends -10076 to
 *    the same handled-key path as delete-word, which preflight pins.
 * 2. **Revert.** That path is shared up to the delete-word comparison, through the IME's own input
 *    state checks. One `const`/`if-ne` before the comparison runs a copy of the physical-keyboard
 *    revert, then jumps to the same continuation the stock revert uses.
 *
 * Every other event pays two extra instructions at each site and takes the stock path unchanged.
 *
 * ## Registers
 *
 * The copy uses the stock block's own registers: the event in vE, temporaries vE+1..vE+7, and the
 * `this` copy the stock block makes. It writes nothing the stock block does not, and both leave by
 * the same continuation. That continuation reads only the `this` copy and the start-time pair;
 * preflight checks that over the real control-flow graph, switches and handlers included.
 */
internal fun BytecodePatchContext.routeRevertsToTheDecoder() {
    val method = latinImeHandleEventFingerprint().method
    val what = "$LATIN_IME->q"
    val registerCount = method.assertRegisterCount(HANDLE_EVENT_REGISTERS, what)

    checkFieldExists(INPUT_STATE, "the IME's input state")
    checkFieldExists(REVERT_GUARD, "the input state's revert guard")
    REQUEST_ARGUMENTS.forEach { checkFieldExists(it, "an argument of the decoder request") }
    checkFieldExists(EVENT_TIME, "the event's timestamp")
    checkInvokeKind(BUILD_REQUEST, InvokeKind.STATIC, "the decoder request builder")
    checkInvokeKind(DECODER, InvokeKind.VIRTUAL, "the IME's decoder")
    checkInvokeKind(DECODER_CLOCK, InvokeKind.VIRTUAL, "the decoder clock")
    checkInvokeKind(DECODE, InvokeKind.VIRTUAL, "the decoder call")
    checkInvokeKind(APPLY_RESULT, InvokeKind.VIRTUAL, "the result update")

    val body = method.instructions.toList()
    val revert = body.findStockRevert(what)
    val route = body.findHandledKeyRoute(what)
    check(route.index < revert.compareIndex) {
        "$what routes keys after the delete-word comparison; the two insertions would be reordered"
    }

    val e = revert.eventRegister
    val self = revert.selfRegister
    validateScratchRegisters(
        scratch = (e + 1..e + 7).toList() + self,
        avoid = listOf(e),
        what = what,
        registerCount = registerCount,
    )
    validateScratchRegisters(
        scratch = listOf(revert.constantRegister),
        avoid = listOf(revert.keyCodeRegister),
        what = what,
        registerCount = registerCount,
    )
    validateScratchRegisters(
        scratch = listOf(route.scratchRegister),
        avoid = listOf(route.keyCodeRegister),
        what = what,
        registerCount = registerCount,
    )

    // Later site first, so the earlier index is still valid when it is used.
    method.addInstructionsWithLabels(
        revert.compareIndex,
        """
            const/16 v${revert.constantRegister}, $REVERT_AUTO_CORRECTION
            if-ne v${revert.keyCodeRegister}, v${revert.constantRegister}, :$STOCK
            move-object/from16 v$self, p0
            iget-object v${e + 2}, v$self, $INPUT_STATE
            iget-boolean v${e + 2}, v${e + 2}, $REVERT_GUARD
            if-nez v${e + 2}, :$DONE
            iget-boolean v${e + 2}, v$self, ${REQUEST_ARGUMENTS[0]}
            iget-wide v${e + 3}, v$self, ${REQUEST_ARGUMENTS[1]}
            iget v${e + 5}, v$self, ${REQUEST_ARGUMENTS[2]}
            iget-boolean v${e + 6}, v$self, ${REQUEST_ARGUMENTS[3]}
            iget-object v${e + 7}, v$self, ${REQUEST_ARGUMENTS[4]}
            const/16 v${e + 1}, $REVERT_AUTO_CORRECTION
            invoke-static/range { v$e .. v${e + 7} }, $BUILD_REQUEST
            move-result-object v${e + 1}
            if-eqz v${e + 1}, :$DONE
            invoke-virtual { v$self }, $DECODER
            move-result-object v${e + 2}
            invoke-virtual { v$self }, $DECODER_CLOCK
            move-result-wide v${e + 3}
            const/4 v${e + 7}, 0x0
            invoke-virtual { v${e + 2}, v${e + 3}, v${e + 4}, v${e + 1}, v${e + 7} }, $DECODE
            move-result-object v${e + 1}
            if-eqz v${e + 1}, :$DONE
            iget-wide v$e, v$e, $EVENT_TIME
            const/4 v${e + 4}, 0x1
            invoke-virtual { v$self, v${e + 4}, v$e, v${e + 1}, v${e + 7} }, $APPLY_RESULT
            :$DONE
            goto/16 :$EXIT
        """.trimIndent(),
        ExternalLabel(STOCK, body[revert.compareIndex]),
        ExternalLabel(EXIT, body[revert.continuationIndex]),
    )

    method.addInstructionsWithLabels(
        route.index,
        """
            const/16 v${route.scratchRegister}, $REVERT_AUTO_CORRECTION
            if-eq v${route.keyCodeRegister}, v${route.scratchRegister}, :$ROUTE
        """.trimIndent(),
        ExternalLabel(ROUTE, body[route.handledIndex]),
    )
}

private class StockRevert(
    /** The delete-word `const` the revert test is inserted before. */
    val compareIndex: Int,
    val keyCodeRegister: Int,
    /** Overwritten by the delete-word `const` itself, so free at the insertion point. */
    val constantRegister: Int,
    /** The event, which the request is built from; the first register of the builder's range. */
    val eventRegister: Int,
    /** The stock block's copy of `this`, which its continuation reads. */
    val selfRegister: Int,
    /** Where the stock revert goes once it has applied the result. */
    val continuationIndex: Int,
)

/**
 * The physical-keyboard revert block and the delete-word test in front of it, asserted by shape.
 *
 * On 18.0.3, relative to the block's one `const/16 #-10076`:
 *
 *     -8  iget-object  vS, vThis, LatinIme->x:Lftq;
 *     -7  iget-boolean vS, vS, Lftq;->o:Z
 *     -6  if-nez       vS, -> delete a word instead
 *     -5…-1  the five request arguments, into vE+2…vE+7
 *      0  const/16     vE+1, #-10076
 *     +1  move-object/from16 vSelf, p0
 *     +2  invoke-static/range {vE .. vE+7}, Lful;->d(…)Lyhg;
 *     +5  invoke-virtual {vSelf}, LatinIme->B()      +7  …->z()
 *     +10 invoke-virtual {…}, Lfsf;->k(JLyhg;Z)Lyct;
 *     +13 iget-wide    vE, vE, Lnur;->j:J
 *     +15 invoke-virtual {vSelf, …}, LatinIme->E(ZJZ)V
 *     +16 goto/16      -> continuation
 */
private fun List<Instruction>.findStockRevert(what: String): StockRevert {
    val code = indices
        .filter { isConst16(this[it], REVERT_AUTO_CORRECTION) }
        .sole {
            "$what loads $REVERT_AUTO_CORRECTION $it times; expected exactly once, in the " +
                "physical-keyboard revert (twice means this patch was applied already)"
        }
    check(code >= 8 && code + 16 < size) { "$what: the revert at $code is too close to an end" }

    check(this[code - 8].usesField(INPUT_STATE) && this[code - 7].usesField(REVERT_GUARD) &&
          this[code - 6].opcodeName() == "IF_NEZ") {
        "$what no longer checks $REVERT_GUARD before its physical-keyboard revert"
    }
    REQUEST_ARGUMENTS.forEachIndexed { k, field ->
        check(this[code - 5 + k].usesField(field)) {
            "$what loads `${this[code - 5 + k].opcodeName()}` where the revert loaded $field"
        }
    }
    val request = this[code + 2]
    check(request.callsMethod(BUILD_REQUEST) && request is RegisterRangeInstruction &&
          request.registerCount == 8) {
        "$what no longer builds its revert with an 8-register $BUILD_REQUEST"
    }
    val e = request.startRegister
    check((this[code] as OneRegisterInstruction).registerA == e + 1) {
        "$what's revert code is not the builder's key-code argument"
    }
    val selfCopy = this[code + 1]
    check(selfCopy.opcodeName() == "MOVE_OBJECT_FROM16") {
        "$what's revert no longer copies `this` before the builder"
    }
    val self = (selfCopy as TwoRegisterInstruction).registerA
    check(this[code + 5].callsMethod(DECODER) && this[code + 5].invokeRegisterAt(0) == self &&
          this[code + 7].callsMethod(DECODER_CLOCK) && this[code + 10].callsMethod(DECODE) &&
          this[code + 13].usesField(EVENT_TIME) && this[code + 15].callsMethod(APPLY_RESULT) &&
          this[code + 15].invokeRegisterAt(0) == self) {
        "$what's revert no longer runs decoder, clock, decode, event time and result update on v$self"
    }
    val exit = code + 16
    check(this[exit].opcodeName().startsWith("GOTO")) {
        "$what's revert no longer ends in a goto to its continuation"
    }
    val continuation = branchTargetIndex(exit, what)

    // The delete-word test: read the key code, compare it, then ask whether the event is physical.
    val compare = (maxOf(1, code - 24) until code)
        .filter { isConst16(this[it], DELETE_WORD) }
        .sole { "$what has $it delete-word comparisons in front of its revert, expected one" }
    val read = this[compare - 1]
    val test = this[compare + 1]
    check(read.usesField(KEY_CODE) && test.opcodeName() == "IF_NE" &&
          this[compare + 2].callsMethod(IS_PHYSICAL)) {
        "$what no longer tests the key code against delete-word right before its revert"
    }
    val keyCode = (read as TwoRegisterInstruction).registerA
    val constant = (this[compare] as OneRegisterInstruction).registerA
    check((test as TwoRegisterInstruction).registerA == keyCode && test.registerB == constant) {
        "$what's delete-word test does not compare the key code it just read"
    }
    check(this[compare + 2].invokeRegisterAt(0) == e) {
        "$what asks v${this[compare + 2].invokeRegisterAt(0)} whether it is physical, not the event v$e"
    }
    check((compare until code + 2).none { e in this[it].destinationRegistersOrEmpty() }) {
        "$what overwrites the event v$e between the delete-word test and its revert"
    }
    check(self !in e..e + 7) { "$what's `this` copy v$self overlaps the builder's range" }

    return StockRevert(compare, keyCode, constant, e, self, continuation)
}

private class HandledKeyRoute(
    /** The `D()` query that closes Gboard's handled-key list, where the route is inserted. */
    val index: Int,
    val keyCodeRegister: Int,
    /** Receives the `D()` result immediately after, so free at the insertion point. */
    val scratchRegister: Int,
    /** Where every listed key, delete-word included, continues. */
    val handledIndex: Int,
)

/**
 * The end of `q`'s handled-key list. On 18.0.3:
 *
 *     if-eq vK, vC, -> handled          the last listed key
 *     invoke-virtual {vThis}, D()Lrqp;  <- the route goes here
 *     move-result-object vC
 *     invoke-interface {vC, vK}, Lrqp;->h(I)Z
 *
 * The route is only valid if delete-word takes the same `handled` path, which is asserted here.
 */
private fun List<Instruction>.findHandledKeyRoute(what: String): HandledKeyRoute {
    val claims = indices
        .filter { this[it].callsMethod(SUB_HANDLER_CLAIMS) }
        .sole { "$what asks $SUB_HANDLER_CLAIMS $it times, expected once" }
    val subHandler = claims - 2
    check(subHandler >= 1 && this[subHandler].callsMethod(SUB_HANDLER) &&
          this[subHandler + 1].opcodeName() == "MOVE_RESULT_OBJECT") {
        "$what no longer queries $SUB_HANDLER immediately before $SUB_HANDLER_CLAIMS"
    }
    val scratch = (this[subHandler + 1] as OneRegisterInstruction).registerA
    check(this[claims].invokeRegisterAt(0) == scratch) {
        "$what does not call $SUB_HANDLER_CLAIMS on the sub-handler it just fetched"
    }
    val keyCode = this[claims].invokeRegisterAt(1)

    val last = this[subHandler - 1]
    check(last.opcodeName() == "IF_EQ" && (last as TwoRegisterInstruction).registerA == keyCode &&
          last.registerB == scratch) {
        "$what's handled-key list no longer ends with a key-code test right before $SUB_HANDLER"
    }
    val handled = branchTargetIndex(subHandler - 1, what)

    val deleteWord = (0 until subHandler)
        .filter { isConst16(this[it], DELETE_WORD) }
        .sole { "$what's handled-key list loads delete-word $it times, expected once" }
    val deleteWordRegister = (this[deleteWord] as OneRegisterInstruction).registerA
    val deleteWordTest = (deleteWord + 1 until subHandler)
        .filter {
            this[it].opcodeName() == "IF_EQ" &&
                (this[it] as TwoRegisterInstruction).registerA == keyCode &&
                (this[it] as TwoRegisterInstruction).registerB == deleteWordRegister
        }
        .sole { "$what tests the key code against delete-word $it times in its list, expected once" }
    check(branchTargetIndex(deleteWordTest, what) == handled) {
        "$what sends delete-word somewhere other than the shared handled-key path; " +
            "$REVERT_AUTO_CORRECTION routed there would not reach the delete-word revert"
    }
    return HandledKeyRoute(subHandler, keyCode, scratch, handled)
}

private fun isConst16(instruction: Instruction, value: Int): Boolean =
    instruction.opcodeName() == "CONST_16" &&
        (instruction as? NarrowLiteralInstruction)?.narrowLiteral == value
