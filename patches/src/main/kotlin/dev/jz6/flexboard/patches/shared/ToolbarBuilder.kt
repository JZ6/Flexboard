package dev.jz6.flexboard.patches.shared

import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * The derivation of Gboard's access-point builder API.
 *
 * The builder class is R8-renamed on every Gboard build and its setters are five same-shaped
 * `(I)V` aliases — the kind of type that produced Flexboard's worst regression (`0.0.1-dev.1`
 * shipped against the wrong letter because nothing told the setters apart). So this derivation
 * does not "guess by name". It anchors on:
 *
 *  1. A *seed method* — the sole site in the dex that loads the text-editing access point's
 *     three resource ids together. That pins the factory, which pins the builder's class.
 *  2. The builder's `build()` — generated code that refuses to build an incomplete access
 *     point and names what is missing as string literals (`" icon"`, `" label"`,
 *     `" contentDescription"`). Each missing-property name is tested against one bit of a
 *     completeness mask, and exactly one `(I)V` setter writes that bit. R8 cannot touch
 *     string literals, so a bit leads from a setter to a name.
 *
 * Everything the patches need from the builder — setters by name, the literal pass-through
 * fields, the Runnable action wiring — hangs off that anchor. [resolveAccessPointBuilder] is
 * called per patch; each gets the same derived shape.
 */

// -------------------------------------------------------------------------------------------
// The derived shape
// -------------------------------------------------------------------------------------------

/** The builder API, every member of it derived. */
internal data class AccessPointBuilder(
    val newBuilder: String,
    val setId: String,
    val setIcon: String,
    val setLabel: String,
    val setContentDescription: String,
    val setAction: String,
    val putExtra: String,
    val build: String,
    /**
     * Where a **literal** label goes, for a button whose name is not a Gboard string.
     *
     * The access point carries both a label resource id and a label `String`, and its accessor
     * returns the `String` whenever the id is zero. There is no builder setter for the
     * `String` — it is a pass-through the generated `build` never validates — so it is written
     * directly, into a field derived rather than named.
     */
    val labelField: String,
    val contentDescriptionField: String,
)

/** One of the builder's resource-id setters, and what it writes. */
internal data class BuilderProperty(
    val setter: String,
    val bit: Long,
    /** The `int` field holding the resource id, which the literal field sits beside. */
    val resourceField: String,
)

// -------------------------------------------------------------------------------------------
// The seed
// -------------------------------------------------------------------------------------------

private const val SEED_ICON = 0x7f080546L
private const val SEED_LABEL = 0x7f140720L
private const val SEED_CONTENT_DESCRIPTION = 0x7f141218L

/** The generated builder's own names for the properties it refuses to build without. */
private const val PROPERTY_ICON = " icon"
private const val PROPERTY_LABEL = " label"
private const val PROPERTY_CONTENT_DESCRIPTION = " contentDescription"

private val PROPERTIES = listOf(PROPERTY_ICON, PROPERTY_LABEL, PROPERTY_CONTENT_DESCRIPTION)

// `instructions` is an Iterable, not a List, and an abstract method has no implementation at all.
private fun Method.body(): List<com.android.tools.smali.dexlib2.iface.instruction.Instruction> =
    implementation?.instructions?.toList() ?: emptyList()

private fun Method.literals(): List<Long> =
    body().filterIsInstance<WideLiteralInstruction>().map { it.wideLiteral }

/** The text-editing access point, which is the template every button here is built from. */
private fun Method.isAccessPointSeed(): Boolean {
    val literals = literals()
    return SEED_ICON in literals && SEED_LABEL in literals && SEED_CONTENT_DESCRIPTION in literals
}

/** Every method in the APK for which [predicate] holds, in one pass over all classes. */
internal fun BytecodePatchContext.methodsMatching(predicate: (Method) -> Boolean): List<Method> {
    val found = mutableListOf<Method>()
    classDefForEach { classDef -> classDef.methods.filterTo(found, predicate) }
    return found
}

/**
 * Reads the builder API out of Gboard's own code.
 *
 * The seed method — located by the three resource ids it uses together — supplies the factory that
 * opens a builder, and with it the builder's type. Everything else comes off the builder itself.
 *
 * The literal fields for label and content description are derived by adjacency: `build()` reads
 * resource-id fields in constructor order, and the resource-id of each property is immediately
 * followed by its literal. The icon's literal is an `Icon`, not a `String` — so the walk checks
 * the field type first.
 */
internal fun BytecodePatchContext.resolveAccessPointBuilder(): AccessPointBuilder {
    val seeds = methodsMatching { it.isAccessPointSeed() }

    check(seeds.size == 1) {
        "Expected exactly one access-point seed method — one using $SEED_ICON, $SEED_LABEL and " +
            "$SEED_CONTENT_DESCRIPTION together — but found ${seeds.size}: " +
            "${seeds.map { it.toDescriptor() }}. Gboard no longer builds the text editing access " +
            "point the way this derivation assumes."
    }

    val seed = seeds.single()
    val instructions = seed.body().ifEmpty {
        error("${seed.toDescriptor()} has no implementation")
    }

    // The static that opens the builder, and with it the builder's own type.
    //
    // Anchored at index 0 rather than scanned for. "The first zero-argument non-void call anywhere
    // in the method" would also match an accessor that a later build happens to call before the
    // factory, and the failure from that is not a miss — it is a *wrong* builder type, whose
    // symptom is a confusing "expected exactly one … found 0" from the walk below, pointing at the
    // wrong class. The seed opens with the factory or the derivation does not hold.
    val opening = instructions.first()
    val factory = (opening as? ReferenceInstruction)?.reference as? MethodReference
    check(
        factory != null &&
            opening.opcodeName() == "INVOKE_STATIC" &&
            factory.parameterTypes.isEmpty() &&
            factory.returnType != "V",
    ) {
        "${seed.toDescriptor()} opens with `${opening.opcodeName()}`, not the zero-argument " +
            "static builder factory this derivation reads the builder type from"
    }

    val builderType = factory.returnType
    val builderClass = classDefByOrNull(builderType)
        ?: error("$builderType is not in the APK, so the access-point builder cannot be resolved")

    fun soleBuilderMethod(signature: String, what: String): Method {
        val matches = builderClass.methods.filter {
            "(${it.parameterTypes.joinToString("")})${it.returnType}" == signature
        }
        check(matches.size == 1) {
            "Expected exactly one $what on $builderType — a method with signature $signature — " +
                "but found ${matches.size}: ${matches.map { it.name }}"
        }
        return matches.single()
    }

    val build = soleBuilderMethod("()${factory.definingClass}", "build method")
    val buildBody = build.body()

    val masks = buildBody.filter { it.opcodeName() == "IGET_BYTE" }
        .map { it.fieldDescriptor() }
        .distinct()
    check(masks.size == 1) {
        "Expected exactly one byte field read in ${build.toDescriptor()} — the generated " +
            "completeness mask — but found ${masks.size}: $masks"
    }

    val properties = build.resolveProperties(masks.single(), builderClass)
    fun property(name: String) = properties[name]
        ?: error("${build.toDescriptor()} never names a$name property")

    val setId = soleBuilderMethod("(Ljava/lang/String;)V", "id setter")
    val idFields = setId.body().filter { it.opcodeName() == "IPUT_OBJECT" }.map { it.fieldDescriptor() }
    check(idFields.size == 1) {
        "Expected the id setter ${setId.toDescriptor()} to write exactly one field, found $idFields"
    }

    /**
     * The `String` field carrying a literal value for [name].
     *
     * Adjacency, not "the next String somewhere after". The icon's literal is not a String at
     * all, and a looser rule applied to it walks straight past into the label's literal — a
     * preflight check caught that once. The strict rule fails loudly instead.
     */
    fun literalFieldFor(name: String, property: BuilderProperty): String {
        val index = buildBody.indexOfFirst {
            it.opcodeName() == "IGET" && it.fieldDescriptor() == property.resourceField
        }
        check(index >= 0) {
            "${build.toDescriptor()} never reads ${property.resourceField}, so the literal that " +
                "pairs with the$name property cannot be located"
        }
        val literal = buildBody.drop(index + 1).firstOrNull { it.fieldReferenceOrNull() != null }
            ?: error(
                "No field is read after ${property.resourceField} in ${build.toDescriptor()}, so " +
                    "the$name property has no literal to write"
            )
        val descriptor = literal.fieldDescriptor()
        check(
            literal.opcodeName() == "IGET_OBJECT" &&
                literal.fieldReferenceOrNull()?.type == "Ljava/lang/String;",
        ) {
            "The field read straight after ${property.resourceField} in ${build.toDescriptor()} " +
                "is $descriptor, which is not a String — the constructor's argument order is not " +
                "what this assumes"
        }
        check(descriptor != idFields.single()) {
            "The literal derived for the$name property is $descriptor, which is the access " +
                "point's id — the constructor's argument order is not what this assumes"
        }
        return descriptor
    }

    val label = property(PROPERTY_LABEL)
    val contentDescription = property(PROPERTY_CONTENT_DESCRIPTION)
    val labelField = literalFieldFor(PROPERTY_LABEL, label)
    val contentDescriptionField =
        literalFieldFor(PROPERTY_CONTENT_DESCRIPTION, contentDescription)
    check(labelField != contentDescriptionField) {
        "The label and content description resolved to the same literal field ($labelField), so " +
            "one of the two resource-id fields is not being read where this expects it"
    }

    return AccessPointBuilder(
        newBuilder = "${factory.definingClass}->${factory.name}()$builderType",
        setId = setId.toDescriptor(),
        setIcon = property(PROPERTY_ICON).setter,
        setLabel = label.setter,
        setContentDescription = contentDescription.setter,
        setAction = soleBuilderMethod("(Ljava/lang/Runnable;)V", "Runnable action setter")
            .toDescriptor(),
        putExtra = soleBuilderMethod("(Ljava/lang/String;Ljava/lang/Object;)V", "extras setter")
            .toDescriptor(),
        build = build.toDescriptor(),
        labelField = labelField,
        contentDescriptionField = contentDescriptionField,
    )
}

/**
 * Each property the builder names, mapped to the setter that satisfies it.
 *
 * Two halves meeting at the completeness mask: every `(I)V` setter that writes the mask contributes
 * exactly one bit, and every property the build method names is tested against exactly one bit.
 *
 * The mask write is what distinguishes a setter from the builder's other `(I)V` methods — one
 * of them (a convenience that sets several properties at once) loads a bit-shaped literal of its
 * own without writing the mask at all.
 */
private fun Method.resolveProperties(
    maskField: String,
    builderClass: ClassDef,
): Map<String, BuilderProperty> {
    val byBit = mutableMapOf<Long, BuilderProperty>()
    builderClass.methods.forEach { method ->
        if (method.parameterTypes.map(Any::toString) != listOf("I")) return@forEach
        if (method.returnType != "V") return@forEach

        val body = method.body()
        val writesMask = body.any {
            it.opcodeName() == "IPUT_BYTE" && it.fieldDescriptor() == maskField
        }
        if (!writesMask) return@forEach

        val bits = body.filterIsInstance<WideLiteralInstruction>().map { it.wideLiteral }
        check(bits.size == 1) {
            "Expected ${method.toDescriptor()} to contribute exactly one bit to $maskField, " +
                "found ${bits.size}: $bits"
        }
        val written = body.filter { it.opcodeName() == "IPUT" }.map { it.fieldDescriptor() }
        check(written.size == 1) {
            "Expected ${method.toDescriptor()} to write exactly one int field, found $written"
        }

        val previous = byBit.put(
            bits.single(),
            BuilderProperty(method.toDescriptor(), bits.single(), written.single()),
        )
        check(previous == null) {
            "${method.toDescriptor()} and ${previous?.setter} both set bit ${bits.single()} of " +
                "$maskField, so neither can be told from the other"
        }
    }

    val body = body()
    return PROPERTIES.associateWith { name ->
        val named = body.withIndex().filter { (_, instruction) -> instruction.stringOrNull() == name }
        check(named.size == 1) {
            "Expected ${toDescriptor()} to name the$name property exactly once among the " +
                "properties it refuses to build without, found ${named.size}"
        }
        val tested = body.take(named.single().index).lastOrNull { it is WideLiteralInstruction }
            ?: error("No mask literal precedes the$name string in ${toDescriptor()}")
        val bit = (tested as WideLiteralInstruction).wideLiteral
        byBit[bit] ?: error(
            "The$name property is tested against bit $bit of $maskField, which no setter on " +
                "${builderClass.type} sets"
        )
    }
}
