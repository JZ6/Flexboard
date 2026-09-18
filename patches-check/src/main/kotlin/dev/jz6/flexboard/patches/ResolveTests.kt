package dev.jz6.flexboard.patches

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import dev.jz6.flexboard.patches.shared.ClassLookup
import dev.jz6.flexboard.patches.shared.FieldLookup
import dev.jz6.flexboard.patches.shared.InvokeKind
import dev.jz6.flexboard.patches.shared.checkAssignable
import dev.jz6.flexboard.patches.shared.checkFieldExists
import dev.jz6.flexboard.patches.shared.checkInvokeKind
import dev.jz6.flexboard.patches.shared.checkMethodExists
import dev.jz6.flexboard.patches.shared.findField
import dev.jz6.flexboard.patches.shared.findInstanceField
import dev.jz6.flexboard.patches.shared.soleMethodCalling
import dev.jz6.flexboard.patches.shared.soleMethodNotCalling
import dev.jz6.flexboard.patches.shared.soleMethodWithSignature
import dev.jz6.flexboard.patches.shared.superclassChain

/**
 * Tests for the resolution helpers, over a class hierarchy written by hand.
 *
 * These took a `BytecodePatchContext` until the refactor that came with this file, and that single
 * parameter is why none of them had a test: the context is a final class wanting a `PatcherConfig`
 * and an APK, so asking "does this find an inherited static field" first meant producing sixty
 * thousand decoded Gboard classes. They take a [ClassLookup] now, and a lookup is a map.
 *
 * Two shipped bugs live in here and both get a check of their own:
 *
 *  - `findInstanceField` could not see a static, so `checkFieldExists` rejected every `sget` this
 *    project emits and the patch threw before writing an instruction. Morphe catches that and
 *    carries on, so the build shipped with the feature quietly missing.
 *  - the same walk stopped at the first class it could not read and reported "absent", which is a
 *    different answer from "cannot tell" and got a perfectly correct patch rejected.
 */

private const val OBJECT = "Ljava/lang/Object;"

private fun field(owner: String, name: String, type: String, static: Boolean) =
    ImmutableField(owner, name, type, if (static) AccessFlags.STATIC.value else 0, null, null, null)

private fun method(
    owner: String,
    name: String,
    params: List<String> = emptyList(),
    returns: String = "V",
    static: Boolean = false,
    calls: String? = null,
): ImmutableMethod {
    val body = calls?.let {
        val parts = it.substringBefore("->")
        val rest = it.substringAfter("->")
        ImmutableMethodImplementation(
            5,
            listOf(
                ImmutableInstruction35c(
                    Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
                    ImmutableMethodReference(
                        parts,
                        rest.substringBefore("("),
                        emptyList<String>(),
                        rest.substringAfter(")"),
                    ),
                ),
            ),
            null,
            null,
        )
    }
    return ImmutableMethod(
        owner,
        name,
        params.map { ImmutableMethodParameter(it, null, null) },
        returns,
        if (static) AccessFlags.STATIC.value else 0,
        null,
        null,
        body,
    )
}

private fun clazz(
    type: String,
    superclass: String? = OBJECT,
    isInterface: Boolean = false,
    statics: List<ImmutableField> = emptyList(),
    instances: List<ImmutableField> = emptyList(),
    methods: List<ImmutableMethod> = emptyList(),
): ClassDef = ImmutableClassDef(
    type,
    if (isInterface) AccessFlags.INTERFACE.value else 0,
    superclass,
    null,
    null,
    null,
    statics,
    instances,
    methods,
    emptyList(),
)

/** A lookup over exactly these classes; anything else is "not in the APK". */
private fun lookupOf(vararg defs: ClassDef): ClassLookup {
    val byType = defs.associateBy { it.type }
    return { byType[it] }
}

// A three-deep hierarchy: the leaf declares nothing, the middle declares both kinds, and the root
// is Object. Gboard's real shape -- ScrubMotionEventHandler inheriting its Context one hop up.
private val BASE = clazz(
    "Lbase;",
    statics = listOf(field("Lbase;", "instance", "Lbase;", static = true)),
    instances = listOf(field("Lbase;", "context", "Landroid/content/Context;", static = false)),
)
private val LEAF = clazz("Lleaf;", superclass = "Lbase;")
private val OBJ = clazz(OBJECT, superclass = null)
private val WORLD = lookupOf(BASE, LEAF, OBJ)

internal fun resolveTests() {
    fields()
    chains()
    assignability()
    methodsAndInvokes()
    soleResolution()
}

// ------------------------------------------------------------------ field lookup

private fun fields() {
    // The 0.0.2-dev.1 bug: the field is one hop up, and looking only at the subclass finds nothing.
    val inherited = findInstanceField(WORLD, "Lleaf;", "context")
    equal("an inherited instance field is found", "Lbase;", inherited?.definingClass.toString())

    // The other one: statics are fields too, and every `sget` this project emits reads one.
    equal(
        "a static is found by findField",
        "Lbase;",
        ((findField(WORLD, "Lleaf;", "instance") as? FieldLookup.Found)?.field?.definingClass).toString(),
    )
    equal(
        "but not by findInstanceField, which is the point of having both",
        "null",
        findInstanceField(WORLD, "Lleaf;", "instance").toString(),
    )

    // Three-valued, not two. "Walked the whole chain and it is not there" and "the chain left the
    // APK so I cannot say" are different answers, and conflating them rejected a correct patch.
    equal(
        "absent when the whole chain was readable",
        "Absent",
        findField(WORLD, "Lleaf;", "nosuch").let { it::class.simpleName }.toString(),
    )
    val partial = lookupOf(LEAF)  // Lbase; missing, so the walk leaves the APK at the first hop
    val unknowable = findField(partial, "Lleaf;", "context")
    equal(
        "unknowable when the chain leaves the APK",
        "Lbase;",
        (unknowable as? FieldLookup.Unknowable)?.at.toString(),
    )
}

// ------------------------------------------------------------------ superclass chains

private fun chains() {
    equal(
        "a chain resolved to Object",
        "[Lleaf;, Lbase;, Ljava/lang/Object;]",
        superclassChain(WORLD, "Lleaf;").toString(),
    )
    // Null is "unknowable", and every caller treats it as "say nothing" rather than "no".
    equal("a chain that leaves the APK", "null", superclassChain(lookupOf(LEAF), "Lleaf;").toString())
    equal("a chain starting at Object", "[Ljava/lang/Object;]", superclassChain(WORLD, OBJECT).toString())
}

// ------------------------------------------------------------------ assignability

private fun assignability() {
    accepts("a type is assignable to itself") { checkAssignable(WORLD, "Lleaf;", "Lleaf;", "T") }
    accepts("a subclass is assignable to its base") { checkAssignable(WORLD, "Lleaf;", "Lbase;", "T") }
    // `Lbase;` is missing here, so the walk leaves the APK at the first hop and cannot say whether
    // something above it is the target. Unknowable beats wrong: failing a patch on a framework
    // superclass this cannot see would make the check worse than useless.
    accepts("an unreadable chain says nothing") {
        checkAssignable(lookupOf(LEAF), "Lleaf;", "Lother;", "T")
    }

    rejects("a type not in the chain", "not a") { checkAssignable(WORLD, "Lbase;", "Lleaf;", "T") }

    // A superclass walk cannot answer an interface question -- implementing a type does not put it
    // in the chain -- so the check would have produced a confident, wrong failure.
    val iface = clazz("Liface;", isInterface = true)
    rejects("an interface target", "interface") {
        checkAssignable(lookupOf(LEAF, BASE, OBJ, iface), "Lleaf;", "Liface;", "T")
    }

    // A primitive is not a class, so the lookup returns null and the walk would pass it silently.
    rejects("a primitive target", "not a class descriptor") { checkAssignable(WORLD, "Lleaf;", "I", "T") }
    rejects("a primitive subject", "not a class descriptor") { checkAssignable(WORLD, "I", "Lbase;", "T") }
}

// ------------------------------------------------------------------ methods and call kinds

private val CALLEE = clazz("Lcallee;", methods = listOf(method("Lcallee;", "go")))
private val HOST = clazz(
    "Lhost;",
    methods = listOf(
        method("Lhost;", "virtual"),
        method("Lhost;", "static", static = true),
    ),
)
private val IFACE = clazz("Liface;", isInterface = true, methods = listOf(method("Liface;", "go")))
private val KINDS = lookupOf(HOST, IFACE, CALLEE, OBJ)

private fun methodsAndInvokes() {
    accepts("a method that exists") { checkMethodExists(KINDS, "Lhost;->virtual()V", "T") }
    rejects("a method the class does not declare", "does not declare") {
        checkMethodExists(KINDS, "Lhost;->gone()V", "T")
    }
    rejects("an owner not in the APK", "not in the APK") {
        checkMethodExists(KINDS, "Lmissing;->x()V", "T")
    }

    accepts("invoke-virtual on a virtual method") {
        checkInvokeKind(KINDS, "Lhost;->virtual()V", InvokeKind.VIRTUAL, "T")
    }
    accepts("invoke-static on a static method") {
        checkInvokeKind(KINDS, "Lhost;->static()V", InvokeKind.STATIC, "T")
    }
    accepts("invoke-interface on an interface method") {
        checkInvokeKind(KINDS, "Liface;->go()V", InvokeKind.INTERFACE, "T")
    }

    // Both of these assemble cleanly and throw on the device, which is the entire reason the check
    // exists rather than being left to the assembler.
    rejects("invoke-virtual on a static method", "static") {
        checkInvokeKind(KINDS, "Lhost;->static()V", InvokeKind.VIRTUAL, "T")
    }
    rejects("invoke-virtual on an interface", "interface") {
        checkInvokeKind(KINDS, "Liface;->go()V", InvokeKind.VIRTUAL, "T")
    }
    rejects("invoke-interface on a class", "a class") {
        checkInvokeKind(KINDS, "Lhost;->virtual()V", InvokeKind.INTERFACE, "T")
    }

    accepts("a field that exists, with the right type") {
        checkFieldExists(WORLD, "Lbase;->context:Landroid/content/Context;", "T")
    }
    accepts("an sget target, which is static") {
        checkFieldExists(WORLD, "Lbase;->instance:Lbase;", "T")
    }
    rejects("a field of a different type", "is a") {
        checkFieldExists(WORLD, "Lbase;->context:Ljava/lang/String;", "T")
    }
    rejects("a field nothing declares", "neither") {
        checkFieldExists(WORLD, "Lleaf;->nosuch:I", "T")
    }
    accepts("a field whose chain left the APK says nothing") {
        checkFieldExists(lookupOf(LEAF), "Lleaf;->context:Landroid/content/Context;", "T")
    }
}

// ------------------------------------------------------------------ resolving one method by shape

private fun soleResolution() {
    val one = clazz("Lone;", methods = listOf(method("Lone;", "a", returns = "I")))
    equal(
        "the only method with a signature",
        "Lone;->a()I",
        soleMethodWithSignature(lookupOf(one), "Lone;", "()I", "T"),
    )

    val two = clazz(
        "Ltwo;",
        methods = listOf(method("Ltwo;", "a", returns = "I"), method("Ltwo;", "b", returns = "I")),
    )
    rejects("two methods of the same shape", "found 2") {
        soleMethodWithSignature(lookupOf(two), "Ltwo;", "()I", "T")
    }
    rejects("no method of that shape", "found 0") {
        soleMethodWithSignature(lookupOf(one), "Lone;", "()J", "T")
    }

    // The `s`-versus-`t` case: identical shapes, told apart only by what they do.
    val pair = clazz(
        "Lpair;",
        methods = listOf(
            method("Lpair;", "s", returns = "I", calls = "Lcallee;->go()V"),
            method("Lpair;", "t", returns = "I"),
        ),
    )
    val world = lookupOf(pair, CALLEE)
    equal(
        "the one that calls the needle",
        "Lpair;->s()I",
        soleMethodCalling(world, "Lpair;", "()I", "Lcallee;->go()V", "T"),
    )
    equal(
        "the one that does not",
        "Lpair;->t()I",
        soleMethodNotCalling(world, "Lpair;", "()I", "Lcallee;->go()V", "T"),
    )
}
