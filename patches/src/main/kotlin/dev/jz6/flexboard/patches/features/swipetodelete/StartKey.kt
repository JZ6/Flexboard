package dev.jz6.flexboard.patches.features.swipetodelete

import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.jz6.flexboard.patches.shared.descriptor
import dev.jz6.flexboard.patches.shared.fieldDescriptor
import dev.jz6.flexboard.patches.shared.fieldReferenceOrNull
import dev.jz6.flexboard.patches.shared.invokeRegisterAt
import dev.jz6.flexboard.patches.shared.opcodeName

/**
 * Recovering *which key the gesture started on*, mid-gesture.
 *
 * ## Why this is possible at all
 *
 * [swipeToDeletePatch] widens the start-key gate to a wildcard, so by the time the engine is
 * dispatching there is nothing in the config that says where the finger went down. The obvious
 * conclusion — that the information is gone — is wrong. Gboard keeps the starting `SoftKeyView`
 * itself, in `ScrubMotionEventHandler->m:Landroid/view/View;`:
 *
 *  - written in `g()` on the `ACTION_DOWN` path, immediately after the gate passes;
 *  - read by `s(Z)V`, which is how the key under the finger gets its pressed state;
 *  - nulled in `l()`, the reset.
 *
 * So it holds the starting key for exactly the lifetime of a gesture, and it is Gboard's own
 * bookkeeping rather than anything this project has to add. Walking it back to a keycode is the
 * same four steps `g()` itself performs at offsets 88–108.
 *
 * ## Everything here is derived, nothing is pinned
 *
 * Four of the six members below are obfuscated and none is written down. They are read out of
 * `g()`, which is the one method guaranteed to contain the exact chain wanted — it is where Gboard
 * compares a key's code against the configured one, which is precisely the operation being
 * reproduced later.
 *
 * The chain is walked backwards from the single unambiguous anchor. `ActionDef->b()Lpnu;` is called
 * **once** in `g()`; `SoftKeyView->f(Lpmy;)ActionDef;` is called **twice** — once for the action
 * the gate requires present and once for the action it requires absent — so `f` alone could not be
 * picked out. Its receiver-and-argument pair falls out of whichever call feeds `b()`, and the
 * `sget-object` that loaded that argument gives the right one of the two `Lpmy;` constants. Anchor
 * on the unique thing, and the ambiguous things resolve themselves.
 */
internal data class StartKeyChain(
    /** `ScrubMotionEventHandler->m:Landroid/view/View;` — the key the gesture began on. */
    val startViewField: String,
    /** `…/widgets/SoftKeyView;`, unobfuscated, taken from the accessor's defining class. */
    val softKeyView: String,
    /** `Lpmy;->a:Lpmy;` — selects the action whose key data carries the keycode. */
    val actionSelector: String,
    /** `SoftKeyView->f(Lpmy;)…/metadata/ActionDef;` */
    val actionAccessor: String,
    /** `ActionDef->b()Lpnu;` */
    val keyDataAccessor: String,
    /** `Lpnu;->c:I` — the keycode itself. */
    val keycodeField: String,
)

/** `KeyEvent.KEYCODE_DEL`, as a smali literal. */
internal const val BACKSPACE_KEYCODE = "0x43"

private const val ACTION_DEF = "Lcom/google/android/libraries/inputmethod/metadata/ActionDef;"

internal fun BytecodePatchContext.resolveStartKeyChain(): StartKeyChain {
    val g = scrubHandleMotionEventFingerprint().method
    val body = g.instructionsOrFail()
    val where = "$SCRUB_MOTION_EVENT_HANDLER->g"

    // The starting view. Exactly one field of type View is written in g(), and it is this one.
    val viewWrites = body.withIndex().filter { (_, instruction) ->
        instruction.opcodeName() == "IPUT_OBJECT" &&
            (instruction.fieldReferenceOrNull()?.type == "Landroid/view/View;")
    }
    check(viewWrites.size == 1) {
        "Expected exactly one write to a View-typed field in $where, found ${viewWrites.size} — " +
            "the starting key is no longer retained where this patch reads it"
    }
    val startViewField = viewWrites.single().value.fieldDescriptor()

    // The anchor: called once, where `f` is called twice.
    val keyDataCalls = body.withIndex().filter { (_, instruction) ->
        instruction.methodReferenceOrNull()?.let {
            it.returnType == "Lpnu;" && it.parameterTypes.isEmpty()
        } == true
    }
    check(keyDataCalls.size == 1) {
        "Expected exactly one no-argument call returning Lpnu; in $where, found " +
            "${keyDataCalls.size} — the key-data accessor can no longer be picked out by shape"
    }
    val (keyDataIndex, keyDataCall) = keyDataCalls.single()
    val keyDataAccessor = keyDataCall.methodDescriptor()
    val actionRegister = keyDataCall.invokeRegisterAt(0)

    // Backwards to the `f()` whose result is in that register: the nearest `move-result-object`
    // writing it, and the invoke immediately before that.
    val resultIndex = (keyDataIndex - 1 downTo 0).firstOrNull {
        body[it].opcodeName() == "MOVE_RESULT_OBJECT" &&
            (body[it] as OneRegisterInstruction).registerA == actionRegister
    } ?: error("Nothing in $where writes v$actionRegister before $keyDataAccessor is called on it")

    val accessorCall = body[resultIndex - 1]
    val actionAccessor = accessorCall.methodDescriptor()
    check(accessorCall.methodReferenceOrNull()?.returnType == ACTION_DEF) {
        "The call feeding $keyDataAccessor in $where is $actionAccessor, which does not return " +
            "$ACTION_DEF — the chain from the starting key to its keycode has changed shape"
    }
    val softKeyView = accessorCall.methodReferenceOrNull()!!.definingClass
    val selectorRegister = accessorCall.invokeRegisterAt(1)

    // And the constant that argument was loaded from. Two `Lpmy;` statics are read in g(); this is
    // the one belonging to the call that actually leads to a keycode.
    val selectorIndex = (resultIndex - 2 downTo 0).firstOrNull {
        body[it].opcodeName() == "SGET_OBJECT" &&
            (body[it] as OneRegisterInstruction).registerA == selectorRegister
    } ?: error("Nothing in $where loads the action selector passed to $actionAccessor")
    val actionSelector = body[selectorIndex].fieldDescriptor()

    // The keycode read, which is also the gate's own left-hand side.
    val keycodeReads = body.filter {
        it.opcodeName() == "IGET" && it.fieldReferenceOrNull()?.definingClass == "Lpnu;"
    }
    check(keycodeReads.size == 1) {
        "Expected exactly one read of an Lpnu; field in $where, found ${keycodeReads.size}"
    }
    val keycodeField = keycodeReads.single().fieldDescriptor()

    return StartKeyChain(
        startViewField = startViewField,
        softKeyView = softKeyView,
        actionSelector = actionSelector,
        actionAccessor = actionAccessor,
        keyDataAccessor = keyDataAccessor,
        keycodeField = keycodeField,
    )
}

/**
 * Smali that lands on [onBackspace] when the gesture began on the backspace key, and on
 * [notBackspace] otherwise.
 *
 * Both exits are explicit — the block ends in a branch and a `goto` rather than falling through —
 * so a caller can place the two labels wherever its own shape needs them without this having to
 * know which way round it wants them.
 *
 * Every bail-out goes to [notBackspace], which is the conservative direction: an absent start view,
 * something that is not a `SoftKeyView`, or a key with no action all mean "treat this as an
 * ordinary swipe-anywhere gesture" and leave Flexboard's behaviour in place. That is also why the
 * `instance-of` is here rather than a bare `check-cast`: `g()` only stores the view after proving
 * it is a `SoftKeyView`, so the cast cannot fail today, but a thrown `ClassCastException` inside the
 * dispatcher would take the keyboard down where a branch merely restores the default.
 *
 * Needs two scratch registers, both of which are clobbered.
 */
internal fun StartKeyChain.branchOnStartKey(
    thisRegister: Int,
    scratchA: Int,
    scratchB: Int,
    onBackspace: String,
    notBackspace: String,
): String = """
    iget-object v$scratchA, v$thisRegister, $startViewField
    if-eqz v$scratchA, :$notBackspace
    instance-of v$scratchB, v$scratchA, $softKeyView
    if-eqz v$scratchB, :$notBackspace
    check-cast v$scratchA, $softKeyView
    sget-object v$scratchB, $actionSelector
    invoke-virtual { v$scratchA, v$scratchB }, $actionAccessor
    move-result-object v$scratchA
    if-eqz v$scratchA, :$notBackspace
    invoke-virtual { v$scratchA }, $keyDataAccessor
    move-result-object v$scratchA
    if-eqz v$scratchA, :$notBackspace
    iget v$scratchA, v$scratchA, $keycodeField
    const/16 v$scratchB, $BACKSPACE_KEYCODE
    if-eq v$scratchA, v$scratchB, :$onBackspace
    goto :$notBackspace
"""

private fun app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.instructionsOrFail() =
    implementation?.instructions?.toList()
        ?: error("$SCRUB_MOTION_EVENT_HANDLER->g has no implementation")

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.methodReferenceOrNull() =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.methodDescriptor(): String =
    methodReferenceOrNull()?.descriptor()
        ?: error("Not a method invocation: `${opcode.name}`")
