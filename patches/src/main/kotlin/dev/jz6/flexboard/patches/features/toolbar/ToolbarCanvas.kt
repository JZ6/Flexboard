package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
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
