package dev.jz6.flexboard.patches.features.selectall

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
 * Adds a **Select all** button to Gboard's toolbar.
 *
 * ## Why this is not built the way it looks like it should be
 *
 * Gboard has a select-all already, and it is unreachable from anywhere but its own panel.
 * `TEXT_EDITING_SELECT_ALL` is **-10086**, and that number appears exactly once in the whole
 * app — as an entry in the name-to-value map `Lppf;-><clinit>` builds so keyboard XML can resolve
 * `<key_code>`. Two packed-switches cover it and neither acts on it: one is a classifier
 * ("is this a text-editing keycode"), the other is usage metrics. The only implementation is the
 * text-editing keyboard's own consume-event hook, which runs only while that panel is open.
 *
 * That was checked in every encoding a keycode can take — `const/16`, 32-bit `const`,
 * sparse-switch payloads, and packed-switch **ranges**. The last one is the check that matters and
 * the one that is easy to skip: a packed-switch stores only its first key, so a literal search for
 * -10086 misses it entirely.
 *
 * Undo is a misleading template here. UNDO (-10045) *is* consumed at IME level, by four separate
 * handlers, which is why Flexboard's undo works from anywhere. Select-all has no equivalent. A
 * button emitting -10086 would render, press, highlight, and do nothing.
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
 * So the button carries a Runnable, the Runnable lives in the extension, and it calls
 * `InputConnection.performContextMenuAction(android.R.id.selectAll)` — the same thing Gboard's own
 * panel does, reached without any of Gboard's own plumbing.
 *
 * ## Why nothing is published or registered
 *
 * The obvious route is to build an access-point *notification* and register it the way Gboard's own
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
 * bypassed. The button is prepended, which is why it appears first.
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
 * One honest caveat on that. The seed hands its two string setters **the same text** — both
 * `0x7f140720` and `0x7f141218` read "Text editing" — so which is the label and which the content
 * description cannot be told apart by value, and the two names below are a guess at which is which.
 * It does not matter: both are set to "Select all" here, so the emitted result is identical either
 * way. It would start mattering the moment someone wanted them to differ.
 *
 * The label is Gboard's own **"Select all"** string, which already exists because the text-editing
 * panel uses it. The icon is Material's `select_all`, which Gboard ships and never draws — see
 * [SELECT_ALL_ICON] for how it was found, given that 1,679 drawables have had their names stripped.
 *
 * Note that [SEED_ICON] and [SELECT_ALL_ICON] are now different ids and are doing different jobs.
 * The seed is an *input to the derivation*: the value handed to the builder's icon setter in
 * Gboard's own code, used to work out which of five `(I)V` setters the icon setter is. What the
 * button is actually given is [SELECT_ALL_ICON]. They were briefly the same number, which made the
 * distinction invisible; they are not any more.
 */
@Suppress("unused")
val selectAllPatch = bytecodePatch(
    name = "Select All Button",
    description = "Add a Select all button to the toolbar above the keyboard. One tap selects " +
        "everything in the text field, without opening Gboard's text editing panel first.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    extendWith("extensions/extension.mpe")

    execute {
        val builder = resolveAccessPointBuilder()
        publishInputMethodService()
        prependSelectAllAccessPoint(builder)
    }
}

// -------------------------------------------------------------------------------------------
// Anchors
// -------------------------------------------------------------------------------------------

/**
 * The three resource ids Gboard's text-editing access-point seed uses together, and which nothing
 * else in the app uses together. Resolved with `tools/apk/arsc.py`: the drawable is the panel's
 * icon, `0x7f140720` reads "Text editing", `0x7f141218` is its content description.
 */
private const val SEED_ICON = 0x7f080546L
private const val SEED_LABEL = 0x7f140720L
private const val SEED_CONTENT_DESCRIPTION = 0x7f141218L

/** Gboard's own "Select all", already present because its text-editing panel shows it. */
private const val SELECT_ALL_LABEL = "0x7f140576"

/**
 * Material's own `select_all` glyph — the dashed marquee with a filled inner square.
 *
 * Gboard ships it and never draws it. Its own text-editing panel renders "Select all" as a **text
 * label with no icon at all**, which is why the first version of this button borrowed the panel's
 * icon instead: the obvious place to look genuinely has nothing to take.
 *
 * It was found by shape rather than by name, because aapt2 `--collapse-resource-names` leaves 1,679
 * drawables with no names to search — every vector's `pathData` was scanned for the signature of a
 * dashed border, ~20 two-unit hops, and exactly one drawable in the app carries the glyph.
 *
 * Its `fillColor` is hardcoded white and it declares no `tint`, which looks alarming and is not:
 * [SEED_ICON] is hardcoded near-black and is what *Gboard itself* puts on the toolbar, where it
 * stays legible on dark themes. Access-point icons are therefore tinted downstream, and since both
 * drawables are solid single-colour shapes the source colour is replaced either way.
 */
private const val SELECT_ALL_ICON = "0x7f080218"

/**
 * Flexboard's own access-point id. Gboard keys ordering and user customisation off this string, so
 * it must not collide with one of Gboard's; the prefix is what guarantees that.
 */
private const val ACCESS_POINT_ID = "flexboard_select_all"

private const val EXTENSION_CLASS = "Ldev/jz6/flexboard/extension/selectall/SelectAll;"

private const val SET_SERVICE =
    "$EXTENSION_CLASS->setService(Landroid/inputmethodservice/InputMethodService;)V"

private const val INPUT_METHOD_SERVICE = "Landroid/inputmethodservice/InputMethodService;"

private const val SUB_LIST = "Ljava/util/List;->subList(II)Ljava/util/List;"

private const val MATH_MIN = "Ljava/lang/Math;->min(II)I"

/**
 * Asserted rather than assumed, in the house pattern: an insertion is only sound against a known
 * register layout, and R8 re-rolls register allocation on every Gboard build.
 */
private const val SPLIT_REGISTER_COUNT = 7
private const val SPLIT_PARAMETER_WORDS = 2
private const val ON_CREATE_REGISTER_COUNT = 12
private const val ON_CREATE_PARAMETER_WORDS = 1

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
 * button silently wires itself to whichever came first.
 *
 * All of v0..v10 are dead at entry by backward liveness, so `p0` is read and nothing else is
 * touched.
 */
private fun BytecodePatchContext.publishInputMethodService() {
    val services = classesMatching { it.superclass == INPUT_METHOD_SERVICE }
    check(services.size == 1) {
        "Expected exactly one InputMethodService subclass, found ${services.size}: " +
            "${services.map { it.type }}. The select-all action needs an unambiguous one."
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
 * Prepends the button to the list the bar is built from.
 *
 * The target is derived by shape rather than name: the sole method taking a `List` that splits it
 * with two `subList` calls around a `Math.min`. Those are framework references, so the derivation
 * survives R8 renaming everything around them.
 *
 * The incoming list is Guava-immutable, so it is copied into an `ArrayList` rather than added to.
 * `p1` is only ever read by the stock body — a size and the two `subList` calls — so substituting
 * it at entry is safe. v0..v4 are dead there by backward liveness over the real CFG, not by a
 * forward first-touch scan, which is unsound and nearly shipped register corruption once already.
 */
private fun BytecodePatchContext.prependSelectAllAccessPoint(builder: AccessPointBuilder) {
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

    method.addInstructions(
        0,
        """
            new-instance v0, Ljava/util/ArrayList;
            invoke-direct { v0, p1 }, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V

            invoke-static { }, ${builder.newBuilder}
            move-result-object v1

            const-string v2, "$ACCESS_POINT_ID"
            invoke-virtual { v1, v2 }, ${builder.setId}

            const v2, $SELECT_ALL_ICON
            invoke-virtual { v1, v2 }, ${builder.setIcon}

            const v2, $SELECT_ALL_LABEL
            invoke-virtual { v1, v2 }, ${builder.setLabel}

            const v2, $SELECT_ALL_LABEL
            invoke-virtual { v1, v2 }, ${builder.setContentDescription}

            new-instance v2, $EXTENSION_CLASS
            invoke-direct { v2 }, $EXTENSION_CLASS-><init>()V
            invoke-virtual { v1, v2 }, ${builder.setAction}

            const/4 v2, 0x1
            invoke-static { v2 }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
            move-result-object v2
            const-string v3, "closeAction"
            invoke-virtual { v1, v3, v2 }, ${builder.putExtra}

            invoke-virtual { v1 }, ${builder.build}
            move-result-object v1

            const/4 v2, 0x0
            invoke-virtual { v0, v2, v1 }, Ljava/util/ArrayList;->add(ILjava/lang/Object;)V

            move-object p1, v0
        """,
    )
}

/** The bar-versus-overflow split, identified by what it does to its `List` parameter. */
private fun Method.splitsAccessPoints(): Boolean {
    if (parameterTypes.map(Any::toString) != listOf("Ljava/util/List;")) return false
    if (returnType != "V") return false
    val called = calledDescriptors()
    return called.count { it == SUB_LIST } == 2 && called.any { it == MATH_MIN }
}
