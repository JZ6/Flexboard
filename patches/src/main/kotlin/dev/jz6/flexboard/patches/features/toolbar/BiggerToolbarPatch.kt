package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.assertRegisterCount
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.callsMethod
import dev.jz6.flexboard.patches.shared.fieldReferenceOrNull
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.toDescriptor

/**
 * Bigger toolbar: let the toolbar hold more buttons than the device's stock capacity.
 *
 * ## Mechanism (full trace: docs/toolbar-capacity.md)
 *
 * Gboard computes the bar's maximum in {@code AccessPointsBar.<init>}: an XML styleable value
 * floor, a phenotype flag allowed only inside [3, 8]. The final value lands in one field with
 * one {@code iput} — the single int write after the flag read (preflight's "one int field
 * written after it" pin). Everything downstream computes {@code min(pref, capacity, count)}, so
 * raising just the capacity is the entire safe move: Gboard's own clamps do the rest.
 *
 * The emission is four instructions right after that {@code iput}, and deliberately branchless
 * (labels have a history here — check_emission_lint.py):
 *
 * ```
 * iget      v2, p0, FIELD                    // current capacity back out
 * invoke-static { p1, v2 }, ToolbarCapacity->maxFor(Context, I)I
 * move-result v2
 * iput      v2, p0, FIELD                    // the user's floor wins if bigger
 * ```
 *
 * `v2`/`v5` are pinned free at that exact site (preflight's "scratch registers are dead") and
 * `p0`/`p1` are the receiver and its Context argument, both only read.
 *
 * The user's pick lives under one InlineSlider on the settings screen (row key
 * {@code flexboard_toolbar_max}, 0 = stock, in the static screen XML — same page as everything
 * else). The power side of "the slider wins {@code min(pref, capacity, count)}" is the
 * extension's job: above-stock it also stages Gboard's own count preferences (phone +
 * foldable), so the customize sheet keeps its stock semantics while the slider sets the floor.
 */
@Suppress("unused")
val biggerToolbarPatch = bytecodePatch(
    name = "Bigger Toolbar",
    description = "Let Gboard's toolbar hold more buttons — a slider in Flexboard's settings " +
        "(default 0 = stock capacity). Ordering and overflow stay Gboard's own.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        val barClass = classDefByOrNull(ACCESS_POINTS_BAR)
            ?: error("$ACCESS_POINTS_BAR is not in the APK — the bar's class name moved")
        val initDescriptor = barClass.methods.singleOrNull {
            it.name == "<init>" &&
                it.parameterTypes.map(Any::toString) ==
                    listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;")
        }?.toDescriptor()
            ?: error("$ACCESS_POINTS_BAR has no <init>(Context, AttributeSet) — the constructor" +
                " shape moved")
        val init = mutableClassDefBy(ACCESS_POINTS_BAR).methods.single {
            it.toDescriptor() == initDescriptor
        }
        init.assertRegisterCount(CAPACITY_CTOR_REGISTER_COUNT, initDescriptor)

        val instructions = init.implementation!!.instructions.toList()
        val flagRead = instructions.indexOfFirst { it.callsMethod(FLAG_READ) }
        check(flagRead >= 0) { "$FLAG_READ gone from $initDescriptor" }
        // preflight's "one int field written after the flag read" names the write our emission
        // extends; the descriptor is derived HERE because the field letter moves every build —
        // the count assertion is what makes the derivation safe.
        val writes = instructions.withIndex().filter { (i, ins) ->
            i > flagRead &&
                ins.opcodeName() == "IPUT" &&
                ins.fieldReferenceOrNull()?.toString()?.endsWith(":I") == true
        }
        check(writes.size == 1) {
            "expected exactly one int field write after the flag read in $initDescriptor, " +
                "found ${writes.size}"
        }
        val capacityField = writes.single().value.fieldReferenceOrNull()!!.toString()

        init.addInstructions(
            writes.single().index + 1,
            """
            iget v2, p0, $capacityField
            invoke-static { p1, v2 }, $CAPACITY_MAX_FOR
            move-result v2
            iput v2, p0, $capacityField
            """.trimIndent(),
        )
    }
}

// The extension descriptor as a const, so the constants checker can verify the Java side
// declares exactly this signature.
private const val CAPACITY_CLASS = "Ldev/jz6/flexboard/extension/toolbar/ToolbarCapacity;"
private const val CAPACITY_MAX_FOR =
    "$CAPACITY_CLASS->maxFor(Landroid/content/Context;I)I"

// The bar's class name survives R8 (its own layouts reference it by string), so nothing pins it.
private const val ACCESS_POINTS_BAR =
    "Lcom/google/android/libraries/inputmethod/accesspoint/widget/AccessPointsBar;"

// The phenotype flag access in the constructor — pinned shape-wise in preflight's toolbar
// section; a rename of Lnxp still resolves because the flag-read call shape is the anchor.
private const val FLAG_READ = "Lnxp;->g()Ljava/lang/Object;"

// Register-count pin at the constructor; preflight asserts the same on the stock ctor.
private const val CAPACITY_CTOR_REGISTER_COUNT = 9

// The settings row's contract, mirrored in check_shared_constants.py's XML_ROWS.
internal const val TOOLBAR_MAX_KEY = "flexboard_toolbar_max"
internal const val TOOLBAR_MAX_DEFAULT = 0
internal const val TOOLBAR_MAX_MIN = 0
internal const val TOOLBAR_MAX_CAP = 12
