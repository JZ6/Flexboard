package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import dev.jz6.flexboard.patches.features.scrubdelete.PREFERENCE_STORE_GET
import dev.jz6.flexboard.patches.features.scrubdelete.checkPreferenceStorePins
import dev.jz6.flexboard.patches.features.scrubdelete.resolvePreferenceGetInt
import dev.jz6.flexboard.patches.features.scrubsettings.scrubSettingsScreenPatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.indexOfSoleCall
import dev.jz6.flexboard.patches.shared.opcodeName

/**
 * Makes the number of icons on Gboard's toolbar adjustable.
 *
 * ## Where the number lives
 *
 * The toolbar is the **access points bar**. How many of the ordered access points land on it, rather
 * than in the overflow panel behind the chevron, is decided in `Lmlh;->C(Ljava/util/List;)V`:
 *
 * ```
 * n = min(Lmku;->b(bar.i()), list.size())
 * subList(0, n)     -> the bar
 * subList(n, size)  -> the overflow panel
 * ```
 *
 * `bar.i()` returns [ACCESS_POINTS_BAR]`->m:I`, and that field is written exactly once, in the
 * constructor:
 *
 * ```
 * 44: const/4 v4, #5
 * 45: v2 = typedArray.getInt(2, v4)              // the style attribute, defaulting to 5
 * 49: sget-object v5, AccessPointsBar->a:Lnxp;   // the 'config_max_access_points' flag
 * 51: v4 = ((Long) v5.g()).intValue()
 * 63: if (v4 > 8 || v4 < 3) v4 = v2              // the flag is honoured only within [3, 8]
 * 72: iput v4, v6, ->m:I
 * ```
 *
 * Gboard does expose a *preference* for this further down — `Lmjv;->a` takes
 * `min(access_points_count_on_bar, m)` — but it can only ever lower the count, never raise it, and
 * Gboard's own "reduce your toolbar icons" flow (`Lmjr;->b`) writes that same key. So the ceiling is
 * what has to move, and the key has to be one of ours.
 *
 * ## Why this one is worth a preference read
 *
 * Every preference this project reads is an *insertion*, and an insertion needs registers proved
 * dead against each Gboard build. That is what makes configs expensive here, and why three of them
 * were removed rather than carried. This one is worth the cost twice over:
 *
 *  - **There is no single right value.** How many icons fit depends on how wide the screen is —
 *    [ACCESS_POINTS_BAR]`->K(II)I` gives each item `min((width + 2·padding)/(n + 1), width/n)`, so
 *    more icons simply means narrower ones. Unlike the hold delay, no constant is right everywhere.
 *  - **It is the cheapest insertion in the project.** `AccessPointsBar` is one of the few classes R8
 *    leaves unobfuscated; the anchor is a *string literal*, which R8 never renames; the Context is
 *    already a constructor parameter; and only two scratch registers are needed.
 *
 * ## The field is never named
 *
 * `m:I` is obfuscated, and nothing here writes it down. The insertion goes *before* Gboard's own
 * `iput` and leaves that instruction to do the write, so the patch has to locate an instruction
 * rather than name a field — and a moved letter cannot silently land on the wrong one.
 */
val toolbarCountPatch = bytecodePatch(
    name = "Bigger Toolbar",
    description = "Makes the number of icons on the toolbar above the keyboard adjustable, from " +
        "Gboard's own settings. Anything past the limit stays in the overflow menu.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    // The entry that reaches the screen writing this preference. Shipping the reader without it
    // would leave a value nothing can ever set. Both the manifest entry and the settings row it
    // adds are idempotent, so depending on it alongside `scrubTuningPatch` is safe.
    dependsOn(scrubSettingsScreenPatch)

    // Carries FlexboardSettingsActivity, which the manifest entry that patch writes names.
    extendWith("extensions/extension.mpe")

    execute {
        checkPreferenceStorePins()

        // Resolving it is the assertion that the class is still the one that caps the bar. The
        // fingerprint requires the flag name as a literal, and R8 renames classes, methods and
        // fields but never string contents — so this is the one anchor here that a rebuild of
        // Gboard cannot move.
        AccessPointsBarStaticInitFingerprint.method

        AccessPointsBarConstructorFingerprint.method.readCountFromPreference(this)
    }
}

/**
 * Unobfuscated, because Android instantiates views declared in XML by name and R8 cannot rename
 * what a layout addresses as a string. `res/HNz.xml` declares it.
 */
internal const val ACCESS_POINTS_BAR =
    "Lcom/google/android/libraries/inputmethod/accesspoint/widget/AccessPointsBar;"

/**
 * The Phenotype flag that sets the ceiling, read in `<clinit>` with a
 * `ro.com.google.ime.top_icon_num` system-property override. Used here only as the proof that this
 * class is still the thing being patched; the flag's own value is irrelevant once the preference
 * overrides what it computed.
 */
private const val MAX_ACCESS_POINTS_FLAG = "config_max_access_points"

/**
 * The preference key.
 *
 * **Duplicated in `FlexboardSettingsActivity`**, which writes what this reads, along with [MIN] and
 * [MAX] below. They cannot be shared: that class is compiled into the extension DEX, a separate
 * Gradle module with no dependency on the patches. `check_shared_constants.py` is what keeps the two
 * sides honest — a comment alone would let them drift silently.
 */
internal const val TOOLBAR_COUNT_KEY = "flexboard_toolbar_count"

/**
 * The slider's range, and the bounds a stored value has to fall within to be used at all.
 *
 * Three is Gboard's own floor, and the count it drops to on a narrow screen regardless
 * (`Lmjv;->b`). Ten is past the eight the flag path accepts, which is as far as Google's own layout
 * has been built against — writing the field directly means that clamp does not bind us, and nothing
 * clips beyond it, the icons just keep getting narrower.
 */
internal const val TOOLBAR_COUNT_MIN = 3
internal const val TOOLBAR_COUNT_MAX = 10

/** `this`, the Context and the AttributeSet — asserted so the scratch registers are provably free. */
private const val BAR_CONSTRUCTOR_REGISTER_COUNT = 9
private const val BAR_CONSTRUCTOR_PARAMETER_WORDS = 3

/**
 * Dead at the `iput`, by backward liveness over the real control flow.
 *
 * Not by reading forward from the insertion point: v2 is written two instructions later and v5 four
 * before, so a first-touch scan would reach the same answer here by luck, and the same scan gets
 * `ScrubMotionEventHandler->r` wrong in a way that corrupts a word count silently. `preflight.py`
 * runs the fixpoint properly and guards that v0, v1, v3 and v7 are correctly *not* free.
 */
private val TOOLBAR_SCRATCH_REGISTERS = listOf(2, 5)

private const val STOCK_COUNT_LABEL = "flexboard_stock_count"

/**
 * `AccessPointsBar.<clinit>`, required to still declare the flag by name.
 *
 * Matching on the string is the whole point: it is a semantic assertion that this class is the one
 * that caps the toolbar, and it survives obfuscation in a way that no member name does.
 */
internal object AccessPointsBarStaticInitFingerprint : Fingerprint(
    definingClass = ACCESS_POINTS_BAR,
    name = "<clinit>",
    parameters = emptyList(),
    returnType = "V",
    strings = listOf(MAX_ACCESS_POINTS_FLAG),
)

/**
 * The two-argument view constructor, which is the one XML inflation calls and the only place
 * `->m:I` is ever written.
 */
internal object AccessPointsBarConstructorFingerprint : Fingerprint(
    definingClass = ACCESS_POINTS_BAR,
    name = "<init>",
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;"),
    returnType = "V",
)

/**
 * Overrides the computed ceiling with the stored one, immediately before it is written.
 *
 * ## Locating the write
 *
 * Two anchors, in order, neither of them a member name — on top of the string literal the
 * `<clinit>` fingerprint has already matched, which is what says this class is the toolbar's cap at
 * all:
 *
 *  1. the single call returning the flag's boxed value, `Lnxp;->g()Ljava/lang/Object;`;
 *  2. the single `iput` after it whose **field type** is `I`.
 *
 * The type is what does the work in step 2. `iput` (`0x59`) covers int and float alike, so filtering
 * on the opcode would also catch `->e:F` and `->f:F`, the two dimensions read out of the same
 * `TypedArray` just below. Filtering after the flag call is what excludes `->y:I`, which is written
 * up at offset 31.
 *
 * ## What is emitted
 *
 * The stored value is read with Gboard's own computed ceiling as its **default**, so an unset
 * preference leaves the register exactly as Gboard left it and the behaviour is stock down to the
 * instruction. Out-of-range values fall back the same way rather than being clamped into range: a
 * corrupt or hand-edited preference should read as "unset", not as a number nobody chose.
 *
 * The insertion lands at the start of a `try` range whose handler recycles the `TypedArray` and
 * rethrows. That is benign in both directions — whether or not the range ends up covering these
 * instructions, a throw from the store propagates out of the constructor either way, and neither
 * path leaves anything half-written.
 *
 * `v5` is left holding a `String` where the two paths merge. Safe for the same reason the undo
 * epilogue is: the register is dead there, so nothing reads it before it is written again.
 */
private fun MutableMethod.readCountFromPreference(context: BytecodePatchContext) {
    // Resolved, not named: the store has a second (String, I)I method that reads the value as text
    // and parses it. Emitting that one would compile, verify and quietly parse a preference that
    // was never written as a string.
    val getInt = context.resolvePreferenceGetInt()

    val registerCount = implementation?.registerCount
        ?: error("$ACCESS_POINTS_BAR-><init> has no implementation")
    check(registerCount == BAR_CONSTRUCTOR_REGISTER_COUNT) {
        "$ACCESS_POINTS_BAR-><init> has $registerCount registers, expected " +
            "$BAR_CONSTRUCTOR_REGISTER_COUNT — refusing to guess which registers are free"
    }

    // The Context is the constructor's own first parameter, so unlike the scrub patches there is no
    // field to resolve and nothing to prove assignable: the signature the fingerprint matched on
    // already says what this register holds.
    val contextRegister = registerCount - BAR_CONSTRUCTOR_PARAMETER_WORDS + 1

    val flagIndex = instructions.indexOfSoleCall(FLAG_ACCESSOR, "$ACCESS_POINTS_BAR-><init>")

    val ceilingWrites = instructions.withIndex().filter { (index, instruction) ->
        index > flagIndex &&
            instruction.opcodeName() == "IPUT" &&
            instruction.fieldReferenceOrNull()?.type == "I"
    }
    check(ceilingWrites.size == 1) {
        "Expected exactly one int-typed field write after $FLAG_ACCESSOR in " +
            "$ACCESS_POINTS_BAR-><init>, found ${ceilingWrites.size} — the ceiling is no longer " +
            "the one value stored from the flag comparison"
    }
    val ceilingIndex = ceilingWrites.single().index
    val ceilingWrite = ceilingWrites.single().value

    // The register Gboard's own `iput` is about to read. Writing into it is the whole edit; the
    // instruction itself is left exactly as it is.
    val ceilingRegister = (ceilingWrite as? TwoRegisterInstruction)?.registerA
        ?: error("The ceiling write in $ACCESS_POINTS_BAR-><init> is not a two-register `iput`")

    val (store, scratch) = TOOLBAR_SCRATCH_REGISTERS
    check(TOOLBAR_SCRATCH_REGISTERS.distinct().size == TOOLBAR_SCRATCH_REGISTERS.size) {
        "Scratch registers $TOOLBAR_SCRATCH_REGISTERS are not distinct"
    }
    check(ceilingRegister !in TOOLBAR_SCRATCH_REGISTERS &&
        contextRegister !in TOOLBAR_SCRATCH_REGISTERS) {
        "Scratch registers $TOOLBAR_SCRATCH_REGISTERS collide with the ceiling " +
            "(v$ceilingRegister) or the Context (v$contextRegister) in $ACCESS_POINTS_BAR-><init>"
    }
    check(TOOLBAR_SCRATCH_REGISTERS.all { it < PACKED_INVOKE_REGISTER_LIMIT }) {
        "Scratch registers $TOOLBAR_SCRATCH_REGISTERS do not all fit a 35c invoke's nibbles"
    }

    addInstructionsWithLabels(
        ceilingIndex,
        """
            invoke-static { v$contextRegister }, $PREFERENCE_STORE_GET
            move-result-object v$store
            const-string v$scratch, "$TOOLBAR_COUNT_KEY"
            invoke-virtual { v$store, v$scratch, v$ceilingRegister }, $getInt
            move-result v$store
            const/16 v$scratch, $TOOLBAR_COUNT_MIN
            if-lt v$store, v$scratch, :$STOCK_COUNT_LABEL
            const/16 v$scratch, $TOOLBAR_COUNT_MAX
            if-gt v$store, v$scratch, :$STOCK_COUNT_LABEL
            move v$ceilingRegister, v$store
        """,
        ExternalLabel(STOCK_COUNT_LABEL, ceilingWrite),
    )
}

/** A `35c` invoke addresses its registers in 4-bit nibbles, so v15 is the highest usable one. */
private const val PACKED_INVOKE_REGISTER_LIMIT = 16

/**
 * The flag supplier's getter. Pinned rather than derived: it is used only to *order* the search for
 * the ceiling write, and if it moves the count assertion below fails loudly rather than patching
 * something else.
 */
private const val FLAG_ACCESSOR = "Lnxp;->g()Ljava/lang/Object;"

private fun Instruction.fieldReferenceOrNull(): FieldReference? =
    (this as? ReferenceInstruction)?.reference as? FieldReference
