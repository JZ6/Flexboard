package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.callsMethod
import dev.jz6.flexboard.patches.shared.fieldReferenceOrNull
import dev.jz6.flexboard.patches.shared.indexOfSoleCall
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.stringOrNull
import dev.jz6.flexboard.patches.shared.toDescriptor

/**
 * Raises how many icons Gboard's toolbar can hold, from five to [TOOLBAR_CAPACITY].
 *
 * Flexboard admits nine toolbar ids — six hotkeys and three text actions — and stock Gboard shows
 * five icons, shared with Gboard's own access points. A user cannot display even the six hotkeys
 * that shipped. The capacity is the binding constraint on two released features.
 *
 * ## What this patch does not do
 *
 * It does not touch the count, and it writes no preference. That is the whole design, and it is
 * the part both previous attempts got wrong. Gboard computes the rendered count as
 *
 * ```
 * Lmku;->b(I)I  ->  Lmjv;->a(pref, capacity) = pref >= 0 ? min(pref, capacity) : capacity
 * ```
 *
 * and expresses *"take this icon off the bar"* as a **decrease of `pref`**, leaving the button in
 * the order list. So `min(pref, capacity)` is not an obstacle to be worked around — it is the
 * mechanism by which the user removes buttons. Anything that forces the count upward puts removed
 * buttons back.
 *
 * `ToolbarCountPatch` (removed in 54703a9) overrode the count at the entry of `Lmku;->b(I)I`,
 * bypassing the gate; its release note admits drag-to-reorder was never exercised.
 * `BiggerToolbarPatch` (reverted in e075526) raised the capacity and then restaged Gboard's count
 * preference on every bar construction, which was reported on device as buttons reappearing after
 * being removed. Raise the ceiling, leave the count alone, and both failures are structurally
 * impossible. See `docs/toolbar-capacity.md`.
 *
 * ## The derivation
 *
 * The capacity comes from a Phenotype flag whose compiled-in default is `-1`:
 *
 * ```
 * AccessPointsBar-><clinit>()V
 *   const-string   v0, "config_max_access_points"
 *   const-wide/16  v1, -0x1                      <- becomes TOOLBAR_CAPACITY
 *   invoke-static  {…}, Lnxs;->e(Ljava/lang/String;JLjava/lang/String;)Lnxp;
 * ```
 *
 * and the constructor accepts it only within `[3, 8]`, falling back to the styled attribute (5)
 * otherwise:
 *
 * ```
 * AccessPointsBar-><init>(Context, AttributeSet)V
 *   const/4    v3, 0x3      # lower bound -- also the getDimension index, see below
 *   const/4    v4, 0x5      # styled-attribute fallback
 *   …
 *   invoke-interface {v5}, Lnxp;->g()Ljava/lang/Object;
 *   invoke-virtual   {v5}, Ljava/lang/Long;->intValue()I
 *   move-result v4
 *   const/16   v5, 0x8      <- becomes TOOLBAR_CAPACITY
 *   if-gt      v4, v5, :reject
 *   if-lt      v4, v3, :reject
 *   goto       :accept
 *   :reject
 *   move       v4, v2
 *   :accept
 *   iput       v4, v6, AccessPointsBar->m:I
 * ```
 *
 * `-1` fails the lower bound, which is why stock is five. Both edits are needed: raising the
 * ceiling alone leaves the default failing, and raising the default alone leaves it above the
 * ceiling. Each is a literal rewrite in the same instruction format, so no offset moves and every
 * branch target in the constructor stays valid.
 *
 * ## Why the lower bound is left alone
 *
 * `const/4 v3, 0x3` is not only the clamp's floor. `v3` is read again further down as the
 * styled-attribute index for `getDimension`, so moving it would corrupt the bar's padding. Only
 * the ceiling is safe to touch, and [raiseClamp] asserts the floor's test is still there rather
 * than assuming it.
 *
 * ## Known limits
 *
 * A Phenotype push would override the default and the capacity would revert — this edits the
 * fallback, not the served value. No override is present on 18.0.3; beating one would need a hook
 * on `Lnxp;->g()` and an insertion, which is more machinery than the case currently justifies.
 *
 * A user whose `access_points_count_on_bar` is already low still sees their own number and must
 * raise it in Gboard's own toolbar settings. Seeding it for them is exactly what produced the
 * phantom 15 in f812fd9 and the regression in e075526, so it is deliberately not done.
 */
@Suppress("unused")
val biggerToolbarPatch = bytecodePatch(
    name = "Bigger Toolbar",
    description = "Raises how many icons Gboard's toolbar can hold — five on a stock build — " +
        "to $TOOLBAR_CAPACITY, so Flexboard's hotkeys and text action buttons fit alongside " +
        "Gboard's own. How many actually show stays yours, set by dragging them in Gboard's " +
        "toolbar settings. Force-stop Gboard afterwards: a cached keyboard view can go on " +
        "showing the old capacity.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        accessPointsBarStaticInitFingerprint().method.raiseFlagDefault()
        accessPointsBarConstructorFingerprint().method.raiseClamp()
    }
}

/** How many icons the bar may hold once patched. Nine Flexboard ids, plus room for Gboard's own. */
internal const val TOOLBAR_CAPACITY = 12

/**
 * Survives R8 because a layout addresses it as a string, so it can be named outright — unlike
 * everything it talks to.
 */
private const val ACCESS_POINTS_BAR =
    "Lcom/google/android/libraries/inputmethod/accesspoint/widget/AccessPointsBar;"

/** The flag name. A string, so R8 cannot rewrite it, which is why both edits anchor on it. */
private const val MAX_ACCESS_POINTS_FLAG = "config_max_access_points"

/** The Phenotype flag factory the default is handed to. */
private const val FLAG_FACTORY = "Lnxs;->e(Ljava/lang/String;JLjava/lang/String;)Lnxp;"

/** Reading a flag's current value. One call in the constructor: the capacity. */
private const val FLAG_ACCESSOR = "Lnxp;->g()Ljava/lang/Object;"

/** Gboard's compiled-in default, meaning "unset". Asserted before it is overwritten. */
private const val STOCK_FLAG_DEFAULT = -1L

/** Gboard's own upper bound on the flag. Asserted before it is overwritten. */
private const val STOCK_CEILING = 8L

private fun accessPointsBarStaticInitFingerprint() = Fingerprint(
    definingClass = ACCESS_POINTS_BAR,
    name = "<clinit>",
    parameters = emptyList(),
    returnType = "V",
)

private fun accessPointsBarConstructorFingerprint() = Fingerprint(
    definingClass = ACCESS_POINTS_BAR,
    name = "<init>",
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;"),
    returnType = "V",
)

/** Moves the flag's compiled-in default off "unset" so the constructor's clamp will accept it. */
private fun MutableMethod.raiseFlagDefault() {
    val instructions = instructions.toList()

    val keyIndex = instructions.indexOfFirst { it.stringOrNull() == MAX_ACCESS_POINTS_FLAG }
    check(keyIndex >= 0) {
        "const-string \"$MAX_ACCESS_POINTS_FLAG\" not found in ${toDescriptor()} — the toolbar " +
            "capacity flag is no longer initialised here"
    }

    val defaultOffset = instructions.drop(keyIndex + 1).indexOfFirst { it is WideLiteralInstruction }
    check(defaultOffset >= 0) {
        "No wide literal follows \"$MAX_ACCESS_POINTS_FLAG\" in ${toDescriptor()} — the flag no " +
            "longer carries a long default"
    }
    val defaultIndex = keyIndex + 1 + defaultOffset
    val default = instructions[defaultIndex]

    val literal = (default as WideLiteralInstruction).wideLiteral
    check(literal == STOCK_FLAG_DEFAULT) {
        "\"$MAX_ACCESS_POINTS_FLAG\" defaults to $literal in ${toDescriptor()}, not " +
            "$STOCK_FLAG_DEFAULT — Gboard now ships a capacity of its own and overwriting it " +
            "would discard that rather than raise it"
    }

    // The factory call must be right beside it, or this literal belongs to something else and
    // rewriting it would corrupt an unrelated flag's default.
    val factoryOffset = instructions.drop(defaultIndex + 1).indexOfFirst { it.callsMethod(FLAG_FACTORY) }
    check(factoryOffset in 0..3) {
        "$FLAG_FACTORY is not called within three instructions of \"$MAX_ACCESS_POINTS_FLAG\"'s " +
            "default in ${toDescriptor()} — not the const-string + const-wide + invoke triple " +
            "this patch expects"
    }

    replaceInstruction(
        defaultIndex,
        "const-wide/16 v${(default as OneRegisterInstruction).registerA}, 0x${TOOLBAR_CAPACITY.toString(16)}",
    )
}

/** Moves Gboard's own upper bound so the raised default is accepted rather than discarded. */
private fun MutableMethod.raiseClamp() {
    val instructions = instructions.toList()
    val descriptor = toDescriptor()

    val flagRead = instructions.indexOfSoleCall(FLAG_ACCESSOR, descriptor)

    // The clamp sits between reading the flag and storing the result, so bound the search by the
    // store rather than scanning the whole constructor.
    val capacityWrite = instructions.withIndex().drop(flagRead).firstOrNull { (_, instruction) ->
        instruction.opcodeName() == "IPUT" && instruction.fieldReferenceOrNull()?.type == "I"
    }?.index ?: error(
        "No int field is written after the capacity flag is read in $descriptor — the " +
            "constructor no longer stores the clamped value"
    )

    val ceilingIndex = (flagRead until capacityWrite).firstOrNull { index ->
        (instructions[index] as? NarrowLiteralInstruction)?.narrowLiteral?.toLong() == STOCK_CEILING
    } ?: error(
        "No literal $STOCK_CEILING between the flag read and the capacity write in $descriptor — " +
            "Gboard's upper bound on the toolbar has moved and this patch would raise nothing"
    )

    // The literal has to be the thing the flag value is tested against. A bare 8 somewhere in the
    // constructor is not the ceiling, and rewriting it would change something else silently.
    val comparison = instructions[ceilingIndex + 1]
    check(comparison.opcodeName() == "IF_GT") {
        "The literal $STOCK_CEILING in $descriptor is followed by ${comparison.opcodeName()}, " +
            "not IF_GT — this is not the upper-bound test, so refusing to raise it"
    }
    val ceilingRegister = (instructions[ceilingIndex] as OneRegisterInstruction).registerA
    check((comparison as TwoRegisterInstruction).registerB == ceilingRegister) {
        "The upper-bound test in $descriptor compares against v${comparison.registerB}, not the " +
            "v$ceilingRegister the literal was loaded into — refusing to guess the register mapping"
    }

    // The lower bound is left alone (its register is reused as a getDimension index further down),
    // so the reject path staying intact is a precondition rather than an incidental.
    val lowerBound = instructions[ceilingIndex + 2]
    check(lowerBound.opcodeName() == "IF_LT") {
        "The upper-bound test in $descriptor is not followed by IF_LT but by " +
            "${lowerBound.opcodeName()} — the lower bound this patch relies on leaving alone is gone"
    }

    replaceInstruction(
        ceilingIndex,
        "const/16 v$ceilingRegister, 0x${TOOLBAR_CAPACITY.toString(16)}",
    )
}
