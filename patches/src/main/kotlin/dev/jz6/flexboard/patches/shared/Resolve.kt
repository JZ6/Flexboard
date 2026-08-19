package dev.jz6.flexboard.patches.shared

import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Turning obfuscated names from inputs into outputs.
 *
 * ## Why
 *
 * Every name this project emits is a single letter R8 chose, and letters move. Two ways that has
 * gone wrong, both of which reached a device:
 *
 *  - **The name vanishes.** Harmless in Kotlin — a descriptor is just a string — and fatal at run
 *    time, because smali assembles a reference to a method that no longer exists. Caught by
 *    [checkMethodExists] / [checkFieldExists]: cheap, and turns a broken keyboard into a refused
 *    patch.
 *  - **The name survives on the wrong thing.** `AbstractIme->s` was the undo re-commit on 17.7.7;
 *    on 18 the re-commit is `t` and a different method inherited `s`. Existence proves nothing
 *    there, and neither does the signature — both had the same one. Only *resolving the member by
 *    what it does* catches it.
 *
 * So the rule this file exists to enforce: **if a name has a same-shaped sibling, derive it; if it
 * does not, at least assert it is still there.** Which of the two applies is a property of the APK,
 * and is recorded per member below.
 */

/** Fails the patch when [descriptor] names a method the APK does not contain. */
internal fun BytecodePatchContext.checkMethodExists(descriptor: String, what: String) {
    val owner = descriptor.substringBefore("->")
    val definition = classDefByOrNull(owner)
        ?: error("$what refers to $descriptor, but $owner is not in the APK at all")
    val present = definition.methods.any { it.toDescriptor() == descriptor }
    check(present) {
        "$what refers to $descriptor, which $owner does not declare. The name has moved; emitting " +
            "it would assemble cleanly and fail at run time, which is the failure mode this check " +
            "exists to convert into a refused patch."
    }
}

/** Fails the patch when [descriptor] names a field the APK does not contain, inherited or not. */
internal fun BytecodePatchContext.checkFieldExists(descriptor: String, what: String) {
    val owner = descriptor.substringBefore("->")
    val name = descriptor.substringAfter("->").substringBefore(":")
    val type = descriptor.substringAfter(":")
    val found = findInstanceField(owner, name)
        ?: error("$what refers to $descriptor, but neither $owner nor anything above it declares `$name`")
    check(found.type == type) {
        "$what refers to $descriptor, but `$name` is a ${found.type} — the letter survived on a " +
            "field of a different type, so emitting this would read the wrong thing"
    }
}

/** dexlib2 prints a `Method` in its own form; this is the descriptor the patches speak. */
internal fun Method.toDescriptor(): String =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** The descriptor of a [MethodReference] — the same shape as [Method.toDescriptor], for the other
 *  receiver type. */
internal fun MethodReference.descriptor(): String =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** Every method this class declares whose parameters and return type match. */
private fun BytecodePatchContext.methodsWithSignature(
    owner: String,
    signature: String,
): List<Method> {
    val definition = classDefByOrNull(owner)
        ?: error("$owner is not in the APK, so nothing on it can be resolved")
    return definition.methods.filter {
        "(${it.parameterTypes.joinToString("")})${it.returnType}" == signature
    }
}

/** True when this method calls something whose descriptor contains [needle]. */
private fun Method.calls(needle: String): Boolean {
    // `instructions` is an Iterable, not a List, and an abstract method has no implementation.
    val body = implementation?.instructions ?: return false
    return body.any {
        val reference = (it as? ReferenceInstruction)?.reference as? MethodReference
        reference?.toString()?.contains(needle) == true
    }
}

/**
 * The one method on [owner] with [signature], failing loudly when that is not exactly one.
 *
 * Use only where the APK has been checked and the signature really is unique — the assertion is
 * what makes "unique" a fact rather than a hope.
 */
internal fun BytecodePatchContext.soleMethodWithSignature(
    owner: String,
    signature: String,
    what: String,
): String {
    val matches = methodsWithSignature(owner, signature)
    check(matches.size == 1) {
        "Expected exactly one $what on $owner — a method with signature $signature — but found " +
            "${matches.size}: ${matches.map { it.name }}. A name cannot be picked out by shape " +
            "alone here, so this needs resolving by behaviour instead."
    }
    return matches.single().toDescriptor()
}

/**
 * The one method on [owner] with [signature] **that calls [needle]**, for members whose signature
 * alone is ambiguous.
 *
 * This is the form that catches the `s`-versus-`t` class of bug: where two methods are shaped
 * identically and only what they do tells them apart.
 */
internal fun BytecodePatchContext.soleMethodCalling(
    owner: String,
    signature: String,
    needle: String,
    what: String,
): String {
    val candidates = methodsWithSignature(owner, signature)
    val matches = candidates.filter { it.calls(needle) }
    check(matches.size == 1) {
        "Expected exactly one $what on $owner — a method with signature $signature calling " +
            "$needle — but found ${matches.size} of ${candidates.size} candidates " +
            "(${candidates.map { it.name }}). Gboard no longer implements it the way this " +
            "resolution assumes."
    }
    return matches.single().toDescriptor()
}

/**
 * As [soleMethodCalling], but selecting the candidate that does **not** call [needle].
 *
 * The distinguishing behaviour is not always something the wanted member does; sometimes it is
 * something only its sibling does.
 */
internal fun BytecodePatchContext.soleMethodNotCalling(
    owner: String,
    signature: String,
    needle: String,
    what: String,
): String {
    val candidates = methodsWithSignature(owner, signature)
    val matches = candidates.filterNot { it.calls(needle) }
    check(matches.size == 1) {
        "Expected exactly one $what on $owner — a method with signature $signature that does not " +
            "call $needle — but found ${matches.size} of ${candidates.size} candidates " +
            "(${candidates.map { it.name }}). Gboard no longer implements it the way this " +
            "resolution assumes."
    }
    return matches.single().toDescriptor()
}
