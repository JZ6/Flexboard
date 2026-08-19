package dev.jz6.flexboard.patches.shared

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Does a register actually hold what the instruction about to use it requires?
 *
 * ## Why this exists
 *
 * `0.0.1-dev.1` shipped a keyboard that would not start. One emitted instruction passed `this`
 * — a `LatinIme` — to `Lpnp;->N(Landroid/content/Context;)Lpnp;` (the preference store, as Gboard
 * 17.7.7 spelled it). `LatinIme` extends
 * `AbstractIme` extends `Object`, so it is not a `Context`. Smali assembles it, D8 does not
 * type-check across an injection, and nothing downstream looks at the patched APK, so the first
 * thing that noticed was a device with no keyboard.
 *
 * Every input needed to catch it was already in the patch's hands. The register was not a guess: it
 * was read off `iget-boolean vFlag, vThis, AbstractIme->N:Z`, so its type is pinned by the field
 * reference. Whether that type reaches `Context` is a superclass walk over the class list the
 * patcher already holds. The bug was an assertion never written, not a missing build stage.
 *
 * ## What it can and cannot prove
 *
 * Framework classes are not in the APK's dex, which bounds what this can honestly claim:
 *
 *  - target appears in the chain — assignable, pass.
 *  - chain reaches `Object` entirely inside the dex — the chain is *complete* and the target is
 *    absent, so it is provably wrong. **Fail.**
 *  - chain reaches a class with no `ClassDef`, e.g. `Landroid/app/Service;` — unknowable from
 *    here. Pass, silently.
 *
 * So this is a cheap proof of *wrongness*, never of correctness. It is worth having anyway: the
 * middle case is where patches of this kind actually go wrong, and it is the case `dev.1` was in.
 *
 * **A supertype is enough to disprove.** The type handed in is often only a lower bound — reading
 * `AbstractIme->N:Z` off a register proves the register is *at least* an `AbstractIme`, and it may
 * hold a subclass. That does not weaken the check, because the targets here are classes rather than
 * interfaces: under single inheritance, if `AbstractIme`'s chain terminates at `Object` without
 * passing through `Context`, then no subclass of `AbstractIme` can be a `Context` either.
 *
 * That reasoning is exactly why [checkAssignable] must not be used with an interface as the target.
 * An interface can be implemented at any depth, so absence from a superclass chain proves nothing.
 */

private const val OBJECT_TYPE = "Ljava/lang/Object;"

/** The one target these patches check against, so far. */
internal const val ANDROID_CONTEXT = "Landroid/content/Context;"

/** A `35c` invoke encodes each register in a 4-bit nibble, so v15 is the highest usable one. */
internal const val PACKED_INVOKE_REGISTER_LIMIT = 16

/**
 * A register together with the type it is *proven* to hold, carried as one value.
 *
 * The pairing is the point. `0.0.1-dev.1` emitted one register and reasoned about another; keeping
 * the number and the claim in separate variables is what let them disagree. A check written against
 * a [TypedRegister] and an emission written from the same [TypedRegister] cannot drift apart
 * without someone deliberately constructing a false pair.
 *
 * [type] is a lower bound — the register holds this or a subclass. See the note on this file for
 * why that is still enough to disprove assignability.
 */
internal data class TypedRegister(val register: Int, val type: String) {
    /** For interpolation straight into emitted smali: `v12`. */
    override fun toString() = "v$register"
}

/**
 * The superclass chain of [type], starting with [type] itself and ending at [OBJECT_TYPE], or
 * `null` as soon as it reaches a class the APK does not define — a framework class, typically.
 *
 * `null` means "cannot tell from here", and is deliberately different from an empty chain.
 */
internal fun BytecodePatchContext.superclassChain(type: String): List<String>? {
    val chain = mutableListOf<String>()
    var current = type
    while (true) {
        chain += current
        if (current == OBJECT_TYPE) return chain
        // Not in the dex: a framework class, so the rest of the chain is unknowable.
        val definition = classDefByOrNull(current) ?: return null
        current = definition.superclass ?: return null
    }
}

/**
 * Fails the patch when [type] provably cannot be a [target].
 *
 * [target] must be a **class**, not an interface — see the note on this file. [what] names the
 * thing being checked and is quoted straight into the failure, so make it read as a diagnosis: a
 * patch author reading it should not have to open the APK to understand what went wrong.
 */
internal fun BytecodePatchContext.checkAssignable(type: String, target: String, what: String) {
    if (type == target) return
    // Unknowable rather than wrong. Saying nothing beats failing a patch on a framework subclass
    // this cannot see, which would make the check worse than useless.
    val chain = superclassChain(type) ?: return
    if (target in chain) return
    error(
        "$what is $type, which is not a $target. Its full chain is " +
            "${chain.joinToString(" -> ")}, resolved entirely inside the APK, so no subclass " +
            "of it can be one either.",
    )
}

/**
 * As [checkAssignable], for a register carrying its own proven type.
 *
 * Prefer this form: it takes the number and the claim from the same value, so the thing checked is
 * necessarily the thing emitted.
 */
internal fun BytecodePatchContext.checkAssignable(
    register: TypedRegister,
    target: String,
    what: String,
) = checkAssignable(register.type, target, "$what (v${register.register})")

/**
 * The register count this method was compiled with, failing loudly when it is not [expected].
 *
 * The register mapping for an injection is derived from the count — which slot `this` lands in, how
 * many locals sit above it — so a count that does not match what was assumed either picks the wrong
 * register or silently does nothing. Failing the patch turns that into a refused build rather than
 * a broken keyboard; `0.0.1-dev.1` shipped one because nothing checked.
 *
 * [what] names the method and is interpolated straight into the failure, so it should read as a
 * diagnosis. Returns the count so callers can chain `val n = method.assertRegisterCount(…)` and
 * keep the assertion next to the value it pinned.
 */
internal fun MutableMethod.assertRegisterCount(expected: Int, what: String): Int {
    val actual = implementation?.registerCount
        ?: error("$what has no implementation")
    check(actual == expected) {
        "$what has $actual registers, expected $expected — refusing to guess the register mapping"
    }
    return actual
}

/**
 * Fails the patch when the chosen [scratch] registers for an injection are unsafe.
 *
 * Three independent things worth catching, all of which have shipped a broken build when unchecked:
 *
 *  - **Duplicates** — a list that names the same slot twice would silently let one emission
 *    overwrite another. Distinct-check first, before anything else, so a collision is not
 *    misread as a collision with [avoid].
 *  - **Collisions with [avoid]** — `this`, the receiver, or any value the body still needs after
 *    the injection. Clobbering one of those is the bug `0.0.1-dev.1` hit by passing `this` as a
 *    `Context`.
 *  - **Out of nibble range** — a `35c` invoke encodes each register in 4 bits, so v15 is the
 *    highest it can address. Emitting `invoke-static { v16, … }` would assemble and fail to verify.
 *
 * [what] names the method and lands in every failure message.
 */
internal fun validateScratchRegisters(
    scratch: List<Int>,
    avoid: List<Int>,
    what: String,
) {
    check(scratch.distinct().size == scratch.size) {
        "Scratch registers $scratch are not distinct in $what"
    }
    check(scratch.intersect(avoid.toSet()).isEmpty()) {
        "Scratch registers $scratch collide with $avoid in $what"
    }
    check(scratch.all { it < PACKED_INVOKE_REGISTER_LIMIT }) {
        "Scratch registers $scratch do not all fit a 35c invoke's nibbles in $what"
    }
}

/**
 * Instance field [name] on [type] or any class above it, resolved the way the runtime resolves a
 * field reference — by walking up until something declares it.
 *
 * **`ClassDef.instanceFields` is not enough**, which is worth stating because assuming otherwise
 * shipped as `0.0.2-dev.1`. It lists only what a class *declares*, and inherited fields are the
 * normal case here: `ScrubMotionEventHandler` inherits its `Context` from
 * `AbstractMotionEventHandler` one hop up, so looking only at the subclass finds nothing and fails
 * a patch that was perfectly correct.
 *
 * Returns the declaration, so callers get the class that actually declares the field and can emit
 * that spelling rather than a subclass's.
 */
internal fun BytecodePatchContext.findInstanceField(type: String, name: String): Field? {
    var current: String? = type
    while (current != null) {
        val definition = classDefByOrNull(current) ?: return null
        definition.instanceFields.firstOrNull { it.name == name }?.let { return it }
        current = definition.superclass
    }
    return null
}

/**
 * The class an `iget`/`iput` reads its field from.
 *
 * A *lower bound* on the object register's type — the register holds this or a subclass — which
 * is all that disproving assignability needs.
 */
internal fun Instruction.fieldOwnerType(): String =
    ((this as? ReferenceInstruction)?.reference as? FieldReference)?.definingClass
        ?: error("Not a field access: `${opcode.name}`")

/**
 * The declared type of the argument at [offset] in an invoke's register list, matching
 * [invokeRegisterAt]'s numbering: `this` at 0 for a non-static invoke, then the declared
 * parameters.
 *
 * A wide parameter occupies two slots, so offsets do not index [MethodReference.parameterTypes]
 * directly. Landing on the high half of one is a mistake worth failing on rather than rounding
 * down.
 */
internal fun Instruction.invokeParameterType(offset: Int): String {
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference
        ?: error("Not an invoke: `${opcode.name}`")

    val isStatic = opcodeName().startsWith("INVOKE_STATIC")
    if (!isStatic && offset == 0) return reference.definingClass

    var slot = if (isStatic) 0 else 1
    reference.parameterTypes.forEach { parameter ->
        val type = parameter.toString()
        if (slot == offset) return type
        slot += if (type == "J" || type == "D") 2 else 1
    }
    error("Offset $offset does not start a parameter of $reference")
}
