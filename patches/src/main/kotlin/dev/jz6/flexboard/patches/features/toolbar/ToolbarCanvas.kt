package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import dev.jz6.flexboard.patches.shared.assertRegisterCount
import dev.jz6.flexboard.patches.shared.validateScratchRegisters
import dev.jz6.flexboard.patches.shared.calledDescriptors
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.toDescriptor

/**
 * The access-point controller the three toolbar emitters all write through.
 *
 * Split out of one file because the button registration, the hotkey slots and the start-input
 * refresh each read this and nothing else of each other's. Everything here is derived from the
 * dex per run rather than pinned by name -- see the individual resolvers for what each anchors on.
 */
internal fun requireSmaliSafe(literal: String, what: String, id: String) {
    require(
        !literal.contains('"') &&
            !literal.contains('\\') &&
            !literal.contains('\n') &&
            !literal.contains('\r'),
    ) {
        "$what on $id contains a character smali can't carry unparsed — " +
            "use the resource-id variant for that shape"
    }
}

/** The bar-controller's `<init>` register count on Gboard 18.0.3 — the value the insertion
 * assumes. A Gboard bump that moves this is asserted by preflight. */
internal const val CONTROLLER_INIT_REGISTER_COUNT = 13

/** `const/4` encodes a 4-bit signed value (-8..7). Larger-or-more-negative args use `const/16`. */
internal const val MAX_CONST_4_VALUE = 7

/** `const/16` encodes a 16-bit signed value; the emission does not reach below it. */
internal const val MAX_CONST_16_SAFE = 32767

/** The bar-versus-overflow split, identified by what it does to its `List` parameter. */
private fun Method.splitsAccessPoints(): Boolean {
    if (parameterTypes.map(Any::toString) != listOf("Ljava/util/List;")) return false
    if (returnType != "V") return false
    val called = calledDescriptors()
    return called.count { it == "Ljava/util/List;->subList(II)Ljava/util/List;" } == 2 &&
        called.any { it == "Ljava/lang/Math;->min(II)I" }
}

// -------------------------------------------------------------------------------------------
// Where the call goes
// -------------------------------------------------------------------------------------------

/**
 * The shared controller resolution behind every emission: where the controller lives and what
 * its register call is called today. One copy, so the three emitters can't drift a Gboard-bump
 * fix between them (that drift class has no gate of its own — only preflight's shape pins see
 * through it, and they cover the result, not the Kotlin).
 */
internal class ControllerCanvas(
    val controllerType: String,
    val registerCall: String,
    val initDescriptor: String,
)

internal fun BytecodePatchContext.resolveControllerCanvas(): ControllerCanvas {
    // Anchor the bar-controller class on the split method — shape-derived, not name-derived.
    val splits = methodsMatching { it.splitsAccessPoints() }
    check(splits.size == 1) {
        "The bar-controller anchor moved: expected exactly one method that splits a List around " +
            "subList+Math.min, found ${splits.size}: ${splits.map { it.toDescriptor() }}"
    }
    val controllerType = splits.single().definingClass
    val controllerClass = classDefByOrNull(controllerType)
        ?: error("$controllerType is not in the APK; the bar controller cannot be hooked")

    // The register call's name is a one-letter R8 alias on every Gboard build and changes
    // underneath us; what does not change is the *shape* — a (ApType, Z)V method on the
    // controller that Lays.put's into the registry map.
    val registerCall = resolveControllerRegisterCall(controllerClass)
    val initDescriptor = resolveInitDef(controllerClass).toDescriptor()
    return ControllerCanvas(controllerType, registerCall, initDescriptor)
}

/**
 * The controller's registration call, derived from its *what-it-does* rather than its name. Only
 * one method on the controller matches the `(ApType, Z)V` shape *and* writes into the registry
 * map via `Lays.put`; others are similar in either/or. Shape + call-target together is the pin.
 */
private fun resolveControllerRegisterCall(controllerClass: ClassDef): String {
    val lAysPut = "Lays;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    val candidates = controllerClass.methods.filter { method ->
        val params = method.parameterTypes.map(Any::toString)
        params.size == 2 &&
            params[1] == "Z" &&
            method.returnType == "V" &&
            method.implementation?.instructions?.any { instruction ->
                instruction.opcodeName() == "INVOKE_VIRTUAL" &&
                    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)
                        ?.toString() == lAysPut
            } == true
    }
    check(candidates.size == 1) {
        "The bar controller's register call moved: expected exactly one (*, Z)V method on " +
            "${controllerClass.type} that invokes Lays.put on `h`, found ${candidates.size}: " +
            candidates.map { it.toDescriptor() }
    }
    return candidates.single().toDescriptor()
}

/** The immutable `<init>(Context, ?)` declaration; identified once and shared by the rest. */
private fun resolveInitDef(
    controllerClass: ClassDef,
): com.android.tools.smali.dexlib2.iface.Method {
    return controllerClass.methods.singleOrNull {
        it.name == "<init>" &&
            it.parameterTypes.size == 2 &&
            it.parameterTypes[0].toString() == "Landroid/content/Context;"
    } ?: error(
        "${controllerClass.type} has no <init>(Context, ?) — the bar-controller constructor's " +
            "shape has changed and the hook point must be re-derived",
    )
}

// -------------------------------------------------------------------------------------------
// Emission
// -------------------------------------------------------------------------------------------


/**
 * The controller's parameter registers at [CONTROLLER_INIT_REGISTER_COUNT]: `p0` the receiver,
 * `p1` the `Context`, `p2` the module. Scratch has to stay clear of all three.
 */
private val CONTROLLER_INIT_PARAMETERS = listOf(10, 11, 12)

/** The constructor an emitter appends to, and the index of its tail. */
internal data class ControllerInit(
    val canvas: ControllerCanvas,
    val init: MutableMethod,
    val tailIndex: Int,
)

/**
 * Resolves the access-point controller's constructor and the point to append at.
 *
 * Both constructor emitters ran these eleven lines verbatim, error message included -- find the
 * canvas, find `<init>` on it, pin its register count, locate the `return-void` to insert before,
 * and check the scratch set against the parameters. A Gboard build that reshapes that constructor
 * needed the same fix applied twice, in two files, which is exactly the drift
 * [resolveControllerCanvas] was factored out to prevent; that just stopped one call too early.
 *
 * [scratch] is the only part that genuinely differed: the button emitter needs three registers,
 * the hotkey emitter four, because its per-slot blocks branch.
 *
 * `emitHotkeyRefresh` deliberately does not use this. It shares the canvas and nothing after it --
 * its target is the module's start-input method rather than the controller's constructor, with a
 * different register count and a different insertion anchor.
 */
internal fun BytecodePatchContext.resolveControllerInit(scratch: List<Int>): ControllerInit {
    val canvas = resolveControllerCanvas()
    val init = mutableClassDefBy(canvas.controllerType).methods.single {
        it.toDescriptor() == canvas.initDescriptor
    }
    init.assertRegisterCount(CONTROLLER_INIT_REGISTER_COUNT, canvas.initDescriptor)

    val tailIndex = init.implementation!!.instructions
        .indexOfLast { it.opcodeName() == "RETURN_VOID" }
    check(tailIndex >= 0) {
        "${canvas.initDescriptor} has no return-void — the constructor's shape has changed"
    }

    validateScratchRegisters(
        scratch = scratch,
        avoid = CONTROLLER_INIT_PARAMETERS,
        what = canvas.initDescriptor,
    )
    return ControllerInit(canvas, init, tailIndex)
}
