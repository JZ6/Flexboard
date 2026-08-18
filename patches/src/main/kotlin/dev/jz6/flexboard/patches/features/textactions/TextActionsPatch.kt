package dev.jz6.flexboard.patches.features.textactions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
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
 * Instead they are read out of Gboard's own text-editing seed method, which is located by the three
 * resource ids it uses together, and which calls the setters in a known order with known values:
 * the one handed a `0x7f08…` drawable is the icon setter, and the two handed `0x7f14…` strings are
 * the label and the content description. Resource ids are build-specific, and pinning them is
 * sound for the same reason `flickSymbolsPatch` pins one: [COMPATIBILITY_GBOARD] ties the bundle to
 * a single Gboard build and signature.
 *
 * One honest caveat on that. The seed hands its two string setters **the same text** — so which is
 * the label and which the content description cannot be told apart by value, and the two names
 * below are a guess at which is which. It does not matter: each button sets both to the same
 * string, so the emitted result is identical either way. It would start mattering the moment
 * someone wanted them to differ.
 *
 * The labels are Gboard's own, already present because its text editing panel shows them. The icons
 * are Material's, which Gboard bundles — see [BUTTONS] for how they were found, given that every
 * drawable name in the app has been collapsed.
 */
@Suppress("unused")
val textActionsPatch = bytecodePatch(
    name = "Text Editing Buttons",
    description = "Add Select all, Copy and Paste buttons to the toolbar above the keyboard, so " +
        "each is one tap instead of opening Gboard's text editing panel first.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    extendWith("extensions/extension.mpe")

    execute {
        val builder = resolveAccessPointBuilder()
        publishInputMethodService()
        prependTextActionButtons(builder)
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

private const val SET_SERVICE =
    "$EXTENSION_CLASS->setService(Landroid/inputmethodservice/InputMethodService;)V"

private const val NEW_ACTION = "$EXTENSION_CLASS-><init>(I)V"

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

/**
 * Reads the builder API out of Gboard's text-editing seed method.
 *
 * The seed is located by the three resource ids it uses together, then each setter is identified by
 * the value it is handed rather than by its name — see the class KDoc for why naming them would be
 * a bet on R8's letter assignment.
 */
private fun BytecodePatchContext.resolveAccessPointBuilder(): AccessPointBuilder {
    val seeds = classesMatching { classDef ->
        classDef.methods.any { method ->
            val literals = method.literals()
            SEED_ICON in literals &&
                SEED_LABEL in literals &&
                SEED_CONTENT_DESCRIPTION in literals
        }
    }.flatMap { it.methods }.filter { method ->
        val literals = method.literals()
        SEED_ICON in literals &&
            SEED_LABEL in literals &&
            SEED_CONTENT_DESCRIPTION in literals
    }

    check(seeds.size == 1) {
        "Expected exactly one access-point seed method — one using $SEED_ICON, $SEED_LABEL and " +
            "$SEED_CONTENT_DESCRIPTION together — but found ${seeds.size}: " +
            "${seeds.map { it.toDescriptor() }}. Gboard no longer builds the text editing access " +
            "point the way this derivation assumes, and the setters below cannot be told apart " +
            "without it."
    }

    val seed = seeds.single()
    val instructions = seed.implementation?.instructions?.toList()
        ?: error("${seed.toDescriptor()} has no implementation")

    // The static that opens the builder, and with it the builder's own type.
    val factory = instructions.firstNotNullOfOrNull { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        reference?.takeIf { it.parameterTypes.isEmpty() && it.returnType != "V" }
    } ?: error("${seed.toDescriptor()} opens with no zero-argument builder factory")

    val builderType = factory.returnType

    // Each `(I)V` setter is identified by the constant loaded immediately before its call.
    fun setterFor(literal: Long): String {
        val matches = instructions.withIndex().mapNotNull { (index, instruction) ->
            if ((instruction as? WideLiteralInstruction)?.wideLiteral != literal) return@mapNotNull null
            instructions.drop(index + 1).firstNotNullOfOrNull { following ->
                val reference =
                    (following as? ReferenceInstruction)?.reference as? MethodReference
                reference?.takeIf {
                    it.definingClass == builderType &&
                        it.parameterTypes.map(Any::toString) == listOf("I") &&
                        it.returnType == "V"
                }
            }
        }
        check(matches.size == 1) {
            "Expected exactly one builder setter taking ${literal.toString(16)} in " +
                "${seed.toDescriptor()}, found ${matches.size}"
        }
        return matches.single().let { "${it.definingClass}->${it.name}(I)V" }
    }

    fun soleBuilderMethod(signature: String, what: String): String {
        val definition = classDefByOrNull(builderType)
            ?: error("$builderType is not in the APK, so its $what cannot be resolved")
        val matches = definition.methods.filter {
            "(${it.parameterTypes.joinToString("")})${it.returnType}" == signature
        }
        check(matches.size == 1) {
            "Expected exactly one $what on $builderType — a method with signature $signature — " +
                "but found ${matches.size}: ${matches.map { it.name }}"
        }
        return matches.single().toDescriptor()
    }

    return AccessPointBuilder(
        newBuilder = "${factory.definingClass}->${factory.name}()$builderType",
        setId = soleBuilderMethod("(Ljava/lang/String;)V", "id setter"),
        setIcon = setterFor(SEED_ICON),
        setLabel = setterFor(SEED_LABEL),
        setContentDescription = setterFor(SEED_CONTENT_DESCRIPTION),
        setAction = soleBuilderMethod("(Ljava/lang/Runnable;)V", "Runnable action setter"),
        putExtra = soleBuilderMethod("(Ljava/lang/String;Ljava/lang/Object;)V", "extras setter"),
        build = soleBuilderMethod("()${factory.definingClass}", "build method"),
    )
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

    val registerCount = method.implementation?.registerCount
        ?: error("${onCreate.toDescriptor()} has no implementation")
    check(registerCount == ON_CREATE_REGISTER_COUNT) {
        "${onCreate.toDescriptor()} has $registerCount registers, expected " +
            "$ON_CREATE_REGISTER_COUNT — refusing to guess the register mapping"
    }

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
 */
private fun BytecodePatchContext.prependTextActionButtons(builder: AccessPointBuilder) {
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

    val registerCount = method.implementation?.registerCount
        ?: error("${split.toDescriptor()} has no implementation")
    check(registerCount == SPLIT_REGISTER_COUNT) {
        "${split.toDescriptor()} has $registerCount registers, expected $SPLIT_REGISTER_COUNT — " +
            "refusing to guess the register mapping"
    }

    check(BUTTONS.map { it.id }.distinct().size == BUTTONS.size) {
        "Two buttons share an access-point id: ${BUTTONS.map { it.id }}. Gboard keys ordering and " +
            "user customisation off that string, so a collision would lose one of them."
    }
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

    method.addInstructions(
        0,
        """
            new-instance v0, Ljava/util/ArrayList;
            invoke-direct { v0, p1 }, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
            $built
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
