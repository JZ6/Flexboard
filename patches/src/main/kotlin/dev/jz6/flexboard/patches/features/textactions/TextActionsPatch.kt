package dev.jz6.flexboard.patches.features.textactions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.jz6.flexboard.patches.features.scrubsettings.scrubSettingsScreenPatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.assertRegisterCount
import dev.jz6.flexboard.patches.shared.fieldDescriptor
import dev.jz6.flexboard.patches.shared.fieldReferenceOrNull
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.stringOrNull
import dev.jz6.flexboard.patches.shared.toDescriptor

/**
 * Adds **Select all**, **Copy** and **Paste** buttons to Gboard's toolbar.
 *
 * ## Why these are not built the way they look like they should be
 *
 * Gboard has all three already, and none of them is reachable from anywhere but its own text
 * editing panel. `TEXT_EDITING_SELECT_ALL` is **-10086**, and that number appears exactly once in
 * the whole app — as an entry in the name-to-value map `Lppf;-><clinit>` builds so keyboard XML can
 * resolve `<key_code>`. Two packed-switches cover it and neither acts on it: one is a classifier
 * ("is this a text-editing keycode"), the other is usage metrics. The only implementation is the
 * text-editing keyboard's own consume-event hook, which runs only while that panel is open.
 *
 * That was checked in every encoding a keycode can take — `const/16`, 32-bit `const`,
 * sparse-switch payloads, and packed-switch **ranges**. The last one is the check that matters and
 * the one that is easy to skip: a packed-switch stores only its first key, so a literal search for
 * -10086 misses it entirely.
 *
 * Undo is a misleading template here. UNDO (-10045) *is* consumed at IME level, by four separate
 * handlers, which is why Flexboard's undo works from anywhere. These have no equivalent. A button
 * emitting -10086 would render, press, highlight, and do nothing.
 *
 * ## What is used instead
 *
 * Gboard has a keycode whose payload is an arbitrary `Runnable`. The builder's
 * `(Ljava/lang/Runnable;)V` setter stores no field — it wraps the Runnable as key data with
 * keycode **-40007** (`0xffff63b9`), and that *is* dispatched at IME level: `Lmln;->m(Lnur;)Z`
 * reaches `Runnable.run()` through a packed-switch covering [-40013, -40001).
 *
 * Two other classes test -40007 and **decline** it. Neither is the runner, and mistaking one for
 * the runner is the obvious way to get this wrong.
 *
 * So each button carries a Runnable, the Runnable lives in the extension, and it calls
 * `InputConnection.performContextMenuAction` — the same thing Gboard's own panel does, reached
 * without any of Gboard's own plumbing. One extension class serves all three, told apart by an
 * ordinal; see `TextAction` for why that rather than three classes or a framework id.
 *
 * ## Why nothing is published or registered
 *
 * The obvious route is to build access-point *notifications* and register them the way Gboard's own
 * providers do. That route was abandoned: the providers only store their notifications in fields,
 * and what later publishes them was never established.
 *
 * It turned out not to be needed. The method that decides what goes on the bar takes the ordered
 * list **as a parameter**, and only ever reads it — a size, and two `subList` calls:
 *
 * ```
 * n = min(<bar capacity>, list.size())
 * subList(0, n)     -> the bar
 * subList(n, size)  -> the overflow panel
 * ```
 *
 * So a patch can substitute its own list at entry and the whole notification machinery is
 * bypassed. The buttons are prepended in order, which is why they appear first and in the order
 * listed below.
 *
 * ## Why the builder setters are derived rather than named
 *
 * The builder exposes **five** setters sharing the signature `(I)V`. That is precisely the shape
 * that produced this project's worst bug: on 17.7.7 the undo re-commit was `s`, on 18 it is `t`,
 * and a different method inherited `s` — same signature, silently wrong behaviour. Naming a letter
 * here would be the same bet.
 *
 * They are told apart by **Gboard's own words for them**. The builder is generated code that
 * refuses to build an incomplete access point, and the refusal names what is missing — `" icon"`,
 * `" label"`, `" contentDescription"` — each tested against one bit of a completeness mask that
 * exactly one setter writes. String literals are the one thing R8 leaves alone, so a bit leads
 * from a setter to a name. [resolveAccessPointBuilder] does the walk.
 *
 * The labels are Gboard's own, already present because its text editing panel shows them. The icons
 * are Material's, which Gboard bundles — see [BUTTONS] for how they were found, given that every
 * drawable name in the app has been collapsed.
 *
 * ## Hotkeys
 *
 * The same button, with `commitText` in place of a context-menu id and nothing known at patch
 * time: the text is the user's, and so is whether the button exists at all. So six slots are
 * emitted, each guarded on the extension reporting that slot occupied, and each labelled with the
 * user's own snippet rather than a Gboard string — see [HOTKEY_ICONS] and
 * [AccessPointBuilder.labelField].
 */
@Suppress("unused")
val toolbarButtonsPatch = bytecodePatch(
    name = "Toolbar Buttons",
    description = "Add Select all, Copy and Paste buttons to the toolbar above the keyboard, so " +
        "each is one tap instead of opening Gboard's text editing panel first. Adds six hotkey " +
        "buttons too, each typing a string you set in Flexboard's settings — they only appear " +
        "once you have filled one in.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    dependsOn(scrubSettingsScreenPatch)
    extendWith("extensions/extension.mpe")

    execute {
        val builder = resolveAccessPointBuilder()
        publishInputMethodService()
        prependToolbarButtons(builder)
    }
}

// -------------------------------------------------------------------------------------------
// The buttons
// -------------------------------------------------------------------------------------------

/**
 * Flexboard's ordinals for the three actions.
 *
 * **Duplicated in `TextAction`**, which maps them to the framework's context-menu ids. They cannot
 * be shared: that class is compiled into the extension DEX, a separate Gradle module with no
 * dependency on the patches. `check_shared_constants.py` is what keeps the two sides honest.
 */
internal const val TEXT_ACTION_SELECT_ALL = 0
internal const val TEXT_ACTION_COPY = 1
internal const val TEXT_ACTION_PASTE = 2

/** One toolbar button: what it is called, what it looks like, and what it asks the extension for. */
private data class ToolbarButton(
    val id: String,
    val label: String,
    val icon: String,
    val action: Int,
)

/**
 * The three buttons, in the order they appear on the bar.
 *
 * The labels are Gboard's own strings, read out of its text editing panel's compiled layout, so
 * they are already translated into every language Gboard ships.
 *
 * The icons are Material's `select_all`, `content_copy` and `content_paste`. Gboard bundles them
 * and draws none of them — its panel spells all three out in words, with no icon at all, which is
 * why the first version of the select-all button borrowed an unrelated one. They could not be found
 * by name, because aapt2 `--collapse-resource-names` leaves all 1,679 drawables called
 * `0_resource_name_obfuscated`; they were found by matching published Material SVGs against the
 * APK's vectors geometrically, with `tools/apk/glyphs.py`. The ids are consecutive because the set
 * arrived as a block.
 */
private val BUTTONS = listOf(
    ToolbarButton("flexboard_select_all", "0x7f140576", "0x7f080218", TEXT_ACTION_SELECT_ALL),
    ToolbarButton("flexboard_copy", "0x7f140560", "0x7f080214", TEXT_ACTION_COPY),
    ToolbarButton("flexboard_paste", "0x7f140570", "0x7f080217", TEXT_ACTION_PASTE),
)

/**
 * How many hotkey slots exist.
 *
 * Fixed, because the emitted code is fixed: a button is built by a block of bytecode, and blocks
 * cannot be added at run time. Six is a compromise between a bar that can hold them and a settings
 * screen nobody scrolls. Slots are numbered from one, matching what the settings screen calls them.
 *
 * **Duplicated in `FlexboardSettingsActivity`** and held in step by `check_shared_constants.py`.
 */
internal const val HOTKEY_SLOT_COUNT = 6

/**
 * The icon each slot wears, and the whole of what tells six otherwise identical buttons apart on
 * the bar.
 *
 * **Gboard bundles no numbered glyphs**, so these are arbitrary markers rather than "1" through
 * "6": every published Material icon was matched against the APK's vector drawables, and of 2,170
 * only 29 shapes are present — the full table is in `docs/gboard-bindings.md`. These six are the
 * most distinct of those, and `content_cut` is genuinely spare rather than borrowed, since Gboard
 * bundles the scissors and draws them nowhere.
 *
 * Arbitrary markers are learnable because the settings screen renders each one beside the field
 * that fills it, so the star is chosen while looking at the star.
 *
 * **Duplicated in `FlexboardSettingsActivity`**, which needs the same ids to draw those previews.
 */
internal const val HOTKEY_ICON_1 = "0x7f080239" // star

internal const val HOTKEY_ICON_2 = "0x7f0806fc" // auto_awesome
internal const val HOTKEY_ICON_3 = "0x7f080215" // content_cut
internal const val HOTKEY_ICON_4 = "0x7f08074e" // check_box
internal const val HOTKEY_ICON_5 = "0x7f080733" // radio_button_unchecked
internal const val HOTKEY_ICON_6 = "0x7f080219" // share

private val HOTKEY_ICONS = listOf(
    HOTKEY_ICON_1,
    HOTKEY_ICON_2,
    HOTKEY_ICON_3,
    HOTKEY_ICON_4,
    HOTKEY_ICON_5,
    HOTKEY_ICON_6,
)

/** The access-point id for a slot. Gboard keys ordering and user customisation off this string. */
private fun hotkeyId(slot: Int) = "flexboard_hotkey_$slot"

// -------------------------------------------------------------------------------------------
// Anchors
// -------------------------------------------------------------------------------------------

/**
 * The three resource ids Gboard's text-editing access-point seed uses together, and which nothing
 * else in the app uses together. Resolved with `tools/apk/arsc.py`: the drawable is the panel's
 * icon, `0x7f140720` reads "Text editing", `0x7f141218` is its content description.
 *
 * These are **inputs to the derivation** — the values Gboard's own code hands the setters, used to
 * work out which of five `(I)V` setters is which. They are not what any button is given.
 */
private const val SEED_ICON = 0x7f080546L
private const val SEED_LABEL = 0x7f140720L
private const val SEED_CONTENT_DESCRIPTION = 0x7f141218L

private const val EXTENSION_CLASS = "Ldev/jz6/flexboard/extension/textaction/TextAction;"

private const val IME_SERVICE_CLASS = "Ldev/jz6/flexboard/extension/ime/ImeService;"

private const val SET_SERVICE =
    "$IME_SERVICE_CLASS->setService(Landroid/inputmethodservice/InputMethodService;)V"

private const val NEW_ACTION = "$EXTENSION_CLASS-><init>(I)V"

private const val HOTKEY_CLASS = "Ldev/jz6/flexboard/extension/hotkey/Hotkey;"

private const val NEW_HOTKEY = "$HOTKEY_CLASS-><init>(I)V"

/**
 * Asked once per slot while the bar is being built. A null answer means the slot is empty, and the
 * emitted block branches past the entire button — which is how hotkeys stay invisible until used.
 */
private const val HOTKEY_LABEL_AT = "$HOTKEY_CLASS->labelAt(I)Ljava/lang/String;"

private const val INPUT_METHOD_SERVICE = "Landroid/inputmethodservice/InputMethodService;"

private const val SUB_LIST = "Ljava/util/List;->subList(II)Ljava/util/List;"

private const val MATH_MIN = "Ljava/lang/Math;->min(II)I"

/**
 * Asserted rather than assumed, in the house pattern: an insertion is only sound against a known
 * register layout, and R8 re-rolls register allocation on every Gboard build.
 */
private const val SPLIT_REGISTER_COUNT = 7
private const val ON_CREATE_REGISTER_COUNT = 12

/** The builder API, every member of it derived. */
private data class AccessPointBuilder(
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
     * returns the `String` whenever the resource id is zero. There is no builder setter for the
     * `String` — it is a pass-through the generated `build` never validates — so it is written
     * directly, into a field derived rather than named. Only hotkeys use this; the three text
     * actions have real Gboard strings and set the resource id instead.
     */
    val labelField: String,
    val contentDescriptionField: String,
)

/**
 * The generated builder's own names for the properties it refuses to build without.
 *
 * These are **string literals in Gboard's dex**, which is what makes them worth anchoring on: R8
 * renames the class, the methods and the fields around them and leaves these untouched. See
 * [resolveProperties].
 */
private const val PROPERTY_ICON = " icon"

private const val PROPERTY_LABEL = " label"
private const val PROPERTY_CONTENT_DESCRIPTION = " contentDescription"

private val PROPERTIES = listOf(PROPERTY_ICON, PROPERTY_LABEL, PROPERTY_CONTENT_DESCRIPTION)

/** One of the builder's resource-id setters, and what it writes. */
private data class BuilderProperty(
    val setter: String,
    val bit: Long,
    /** The `int` field holding the resource id, which the literal field sits beside. */
    val resourceField: String,
)

// -------------------------------------------------------------------------------------------
// Derivation
// -------------------------------------------------------------------------------------------

/** Every class in the APK for which [predicate] holds. */
private fun BytecodePatchContext.classesMatching(predicate: (ClassDef) -> Boolean): List<ClassDef> {
    val found = mutableListOf<ClassDef>()
    classDefForEach { if (predicate(it)) found += it }
    return found
}

// `instructions` is an Iterable, not a List, and an abstract method has no implementation at all.
private fun Method.body(): List<com.android.tools.smali.dexlib2.iface.instruction.Instruction> =
    implementation?.instructions?.toList() ?: emptyList()

private fun Method.literals(): List<Long> =
    body().filterIsInstance<WideLiteralInstruction>().map { it.wideLiteral }

private fun Method.calledDescriptors(): List<String> =
    body().mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() }

/** The text-editing access point, which is the template every button here is built from. */
private fun Method.isAccessPointSeed(): Boolean {
    val literals = literals()
    return SEED_ICON in literals && SEED_LABEL in literals && SEED_CONTENT_DESCRIPTION in literals
}

/**
 * Reads the builder API out of Gboard's own code.
 *
 * The seed method — located by the three resource ids it uses together — supplies the factory that
 * opens a builder, and with it the builder's type. Everything else comes off the builder itself.
 *
 * **The setters are identified by Gboard's own words for them.** The builder is generated code, and
 * its `build` method refuses to build an incomplete access point by naming what is missing:
 *
 * ```
 * iget-byte    v2, v0, ->q:B          # the completeness mask
 * and-int/lit8 v2, v2, #2             # this property's bit
 * if-nez       v2, -> ...
 * const-string v2, " label"           # ...and its name
 * ```
 *
 * Every resource-id setter ORs one bit into that same mask, so a setter's bit leads to a string
 * literal naming what it sets. That is as strong an anchor as this project has: R8 renames classes,
 * methods and fields, and does not touch string literals.
 *
 * It replaces an earlier derivation that identified each setter by the resource id Gboard's seed
 * happened to hand it. That worked, but could not tell the label from the content description —
 * the seed passes both the same string — and said so in a caveat. It matters now: a hotkey's label
 * is a literal written beside the label resource id, and writing it beside the *content
 * description* instead would leave every hotkey named "Text editing".
 */
private fun BytecodePatchContext.resolveAccessPointBuilder(): AccessPointBuilder {
    val seeds = classesMatching { classDef -> classDef.methods.any { it.isAccessPointSeed() } }
        .flatMap { it.methods }
        .filter { it.isAccessPointSeed() }

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
    val factory = instructions.firstNotNullOfOrNull { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        reference?.takeIf { it.parameterTypes.isEmpty() && it.returnType != "V" }
    } ?: error("${seed.toDescriptor()} opens with no zero-argument builder factory")

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
     * The `String` field carrying a literal value for [property].
     *
     * `build` reads the builder's fields straight into the constructor's argument registers, in
     * constructor order, and the generated constructor takes each property as a resource id
     * **immediately** followed by its literal. So the literal is the very next field read.
     *
     * Adjacency, not "the next String somewhere after" — which is a distinction with teeth. Not
     * every property's literal is a `String`: the icon's is an `android.graphics.drawable.Icon`,
     * so a looser rule applied to the icon walks straight past it and lands on the *label's*
     * literal, reporting a field that belongs to a different property. That is exactly what a
     * preflight check caught here, and the strict rule fails loudly instead.
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
 * The mask write is what distinguishes a setter from the builder's other `(I)V` methods — one of
 * them is a convenience that sets several properties at once, and it loads a bit-shaped literal of
 * its own while writing no mask at all.
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

// -------------------------------------------------------------------------------------------
// Insertions
// -------------------------------------------------------------------------------------------

/**
 * Hands the IME service to the extension.
 *
 * Derived as *the* class extending `android.inputmethodservice.InputMethodService`. There is
 * exactly one, and the assertion is what keeps that a fact — a second one appearing would mean the
 * buttons silently wire themselves to whichever came first.
 *
 * All of v0..v10 are dead at entry by backward liveness, so `p0` is read and nothing else is
 * touched.
 */
private fun BytecodePatchContext.publishInputMethodService() {
    val services = classesMatching { it.superclass == INPUT_METHOD_SERVICE }
    check(services.size == 1) {
        "Expected exactly one InputMethodService subclass, found ${services.size}: " +
            "${services.map { it.type }}. The text actions need an unambiguous one."
    }

    val onCreate = services.single().methods.singleOrNull {
        it.name == "onCreate" && it.parameterTypes.isEmpty() && it.returnType == "V"
    } ?: error("${services.single().type} does not declare onCreate()V")

    val method = mutableClassDefBy(services.single().type).methods.single {
        it.toDescriptor() == onCreate.toDescriptor()
    }

    method.assertRegisterCount(ON_CREATE_REGISTER_COUNT, onCreate.toDescriptor())

    method.addInstructions(0, "invoke-static { p0 }, $SET_SERVICE")
}

/**
 * Prepends the buttons to the list the bar is built from.
 *
 * The target is derived by shape rather than name: the sole method taking a `List` that splits it
 * with two `subList` calls around a `Math.min`. Those are framework references, so the derivation
 * survives R8 renaming everything around them.
 *
 * The incoming list is Guava-immutable, so it is copied into an `ArrayList` rather than added to.
 * `p1` is only ever read by the stock body — a size and the two `subList` calls — so substituting
 * it at entry is safe. v0..v4 are dead there by backward liveness over the real CFG, not by a
 * forward first-touch scan, which is unsound and nearly shipped register corruption once already.
 *
 * Each button is inserted at its own index rather than all at zero, because repeated insertion at
 * zero would reverse them.
 *
 * The hotkeys follow, and go in the other way round: every one of them inserts at the same index,
 * just past the text actions, in **reverse** slot order. That is what lets a slot be skipped
 * without arithmetic — a fixed index means present slots close up behind the text actions however
 * many are empty, and reversing the emission order puts slot 1 leftmost again. The alternative, a
 * running counter, would need a register this method does not have to spare.
 */
private fun BytecodePatchContext.prependToolbarButtons(builder: AccessPointBuilder) {
    val candidates = classesMatching { classDef ->
        classDef.methods.any { it.splitsAccessPoints() }
    }.flatMap { it.methods }.filter { it.splitsAccessPoints() }

    check(candidates.size == 1) {
        "Expected exactly one access-points split method — one taking a List and splitting it " +
            "with two subList calls around Math.min — but found ${candidates.size}: " +
            "${candidates.map { it.toDescriptor() }}"
    }

    val split = candidates.single()
    val method = mutableClassDefBy(split.definingClass).methods.single {
        it.toDescriptor() == split.toDescriptor()
    }

    method.assertRegisterCount(SPLIT_REGISTER_COUNT, split.toDescriptor())

    val ids = BUTTONS.map { it.id } + (1..HOTKEY_SLOT_COUNT).map(::hotkeyId)
    check(ids.distinct().size == ids.size) {
        "Two buttons share an access-point id: $ids. Gboard keys ordering and user customisation " +
            "off that string, so a collision would lose one of them."
    }
    check(HOTKEY_ICONS.size == HOTKEY_SLOT_COUNT) {
        "${HOTKEY_ICONS.size} hotkey icons for $HOTKEY_SLOT_COUNT slots"
    }

    // Every index emitted below is a `const/4`: the text actions take 0 upwards, and the hotkeys
    // all share the one just past them. Nine buttons therefore need no wider literal than three
    // did — only a tenth *text action* would.
    check(BUTTONS.size <= MAX_NIBBLE_LITERAL) {
        "${BUTTONS.size} buttons cannot all be indexed with a `const/4`"
    }

    val built = BUTTONS.withIndex().joinToString("\n") { (index, button) ->
        """
            invoke-static { }, ${builder.newBuilder}
            move-result-object v1

            const-string v2, "${button.id}"
            invoke-virtual { v1, v2 }, ${builder.setId}

            const v2, ${button.icon}
            invoke-virtual { v1, v2 }, ${builder.setIcon}

            const v2, ${button.label}
            invoke-virtual { v1, v2 }, ${builder.setLabel}

            const v2, ${button.label}
            invoke-virtual { v1, v2 }, ${builder.setContentDescription}

            new-instance v2, $EXTENSION_CLASS
            const/4 v3, ${button.action}
            invoke-direct { v2, v3 }, $NEW_ACTION
            invoke-virtual { v1, v2 }, ${builder.setAction}

            const/4 v2, 0x1
            invoke-static { v2 }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
            move-result-object v2
            const-string v3, "closeAction"
            invoke-virtual { v1, v3, v2 }, ${builder.putExtra}

            invoke-virtual { v1 }, ${builder.build}
            move-result-object v1

            const/4 v2, $index
            invoke-virtual { v0, v2, v1 }, Ljava/util/ArrayList;->add(ILjava/lang/Object;)V
        """
    }

    val hotkeys = (HOTKEY_SLOT_COUNT downTo 1).joinToString("\n") { slot ->
        val absent = "flexboard_hotkey_${slot}_absent"
        """
            const/4 v2, $slot
            invoke-static { v2 }, $HOTKEY_LABEL_AT
            move-result-object v2
            if-eqz v2, :$absent

            invoke-static { }, ${builder.newBuilder}
            move-result-object v1

            const-string v3, "${hotkeyId(slot)}"
            invoke-virtual { v1, v3 }, ${builder.setId}

            const v3, ${HOTKEY_ICONS[slot - 1]}
            invoke-virtual { v1, v3 }, ${builder.setIcon}

            const/4 v3, 0x0
            invoke-virtual { v1, v3 }, ${builder.setLabel}
            invoke-virtual { v1, v3 }, ${builder.setContentDescription}
            iput-object v2, v1, ${builder.labelField}
            iput-object v2, v1, ${builder.contentDescriptionField}

            new-instance v3, $HOTKEY_CLASS
            const/4 v4, $slot
            invoke-direct { v3, v4 }, $NEW_HOTKEY
            invoke-virtual { v1, v3 }, ${builder.setAction}

            const/4 v3, 0x1
            invoke-static { v3 }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
            move-result-object v3
            const-string v4, "closeAction"
            invoke-virtual { v1, v4, v3 }, ${builder.putExtra}

            invoke-virtual { v1 }, ${builder.build}
            move-result-object v1

            const/4 v3, ${BUTTONS.size}
            invoke-virtual { v0, v3, v1 }, Ljava/util/ArrayList;->add(ILjava/lang/Object;)V
            :$absent
        """
    }

    method.addInstructionsWithLabels(
        0,
        """
            new-instance v0, Ljava/util/ArrayList;
            invoke-direct { v0, p1 }, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
            $built
            $hotkeys
            move-object p1, v0
        """,
    )
}

/** `const/4` carries its value in a nibble, so it can index at most eight buttons. */
private const val MAX_NIBBLE_LITERAL = 8

/** The bar-versus-overflow split, identified by what it does to its `List` parameter. */
private fun Method.splitsAccessPoints(): Boolean {
    if (parameterTypes.map(Any::toString) != listOf("Ljava/util/List;")) return false
    if (returnType != "V") return false
    val called = calledDescriptors()
    return called.count { it == SUB_LIST } == 2 && called.any { it == MATH_MIN }
}
