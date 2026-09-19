package dev.jz6.flexboard.patches.features.slidesensitivity

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.InvokeKind
import dev.jz6.flexboard.patches.shared.assertRegisterCount
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.checkInvokeKind
import dev.jz6.flexboard.patches.shared.indexOfSoleCall
import dev.jz6.flexboard.patches.shared.invokeRegisterAt
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.sole

/** The pointer delegate, which caches the slide thresholds. */
private const val POINTER_DELEGATE = "Lpvf;"

/** `getFloat(key, default)` on Gboard's preference store. */
private const val PREFERENCE_GET_FLOAT = "Lqhy;->A(Ljava/lang/String;F)F"

/**
 * Resource id of the preference key, whose value is the string
 * `keyboard_slide_sensitivity_ratio`.
 *
 * Pinned as the id rather than the name because the name is not in the bytecode — `Lpvf;->o` calls
 * `getString` on this id and hands the result to the store. Checking the id is what makes it certain
 * this patch is scaling the slide thresholds and not some other float preference read nearby.
 */
private const val SENSITIVITY_KEY_ID = 0x7f140ad3

/** Gboard's shipped ratio, `1.0f`. */
private const val STOCK_RATIO = 0x3f800000

/**
 * What it becomes: `0.5f`. High-16 representable, so the rewrite is the same instruction width.
 *
 * Half rather than a rounder-sounding 0.6, because `const/high16` carries only the top sixteen bits
 * of the float. 0.6f is 0x3f19999a and does not fit; widening the instruction to `const` would
 * change its size and shift everything after it for no benefit.
 */
private const val HALVED_RATIO = 0x3f000000

/**
 * Where the slide thresholds are computed:
 *
 * ```
 * Lpvf;->o()V
 *    7: getString(0x7f140ad3)          -> "keyboard_slide_sensitivity_ratio"
 *   11: const/high16 v2, #0x3f800000   -> the default, 1.0f
 *   13: Lqhy;->A(Ljava/lang/String;F)F
 *   17-39: this.e|f|g|h = (int)(this.u|v|w|x * ratio)
 *   41-44: this.i = (int) this.y       # deliberately not scaled
 * ```
 */
private fun slideThresholdsFingerprint() = Fingerprint(
    definingClass = POINTER_DELEGATE,
    name = "o",
    parameters = emptyList(),
    returnType = "V",
)

/**
 * Makes slide gestures trigger at half the distance.
 *
 * `Lpvi;->h` refuses to report a direction at all until the finger has travelled further than a
 * threshold, and that threshold is a base distance scaled by a preference Gboard reads with a
 * default of `1.0f`. Below it there is no `SLIDE_UP`, no `SLIDE_LEFT`, nothing — the motion is a
 * keypress.
 *
 * ### Why this is its own patch
 *
 * It was written for "Swipe up to undo autocorrect", which on a device fires on some flicks and not
 * others — confirmed with the diagnostic probe, which typed its marker intermittently at ordinary
 * swipe length. Folding the fix into that patch would have been easy and wrong: this lowers *every*
 * slide threshold, so it also changes swipe-left-to-delete, swipe-right-to-undo and
 * flick-for-symbols. Shipping them together would make one install answer two questions, which is
 * the mistake that produced `2.5.0-dev.0` and `dev.1`.
 *
 * ### The trade
 *
 * More sensitive slides mean more accidental ones. A short upward twitch while typing is a slide
 * that Gboard would previously have called a keypress — and on a key with no upward action bound
 * that produces *nothing at all* rather than the letter. Off by default, and worth a device before
 * it is anything else.
 */
@Suppress("unused")
val slideSensitivityPatch = bytecodePatch(
    name = "More sensitive slide gestures",
    description = "Halves how far a finger must travel before Gboard treats it as a slide rather " +
        "than a keypress. Makes \"Swipe up to undo autocorrect\", swipe left to delete and swipe " +
        "right to undo trigger on shorter, more natural motions. The trade is that brief " +
        "accidental drags become slides too, and on a key with no action bound to that direction " +
        "a slide types nothing at all. Off by default.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        halveSlideThreshold()
    }
}

private fun BytecodePatchContext.halveSlideThreshold() {
    val method = slideThresholdsFingerprint().method
    val what = "$POINTER_DELEGATE->o"
    checkInvokeKind(PREFERENCE_GET_FLOAT, InvokeKind.VIRTUAL, "the preference read this patch scales")

    val body = method.instructions.toList()

    // That the right preference is being read, not merely that a float is. Without this the patch
    // would happily scale any nearby getFloat if Gboard added one.
    body.withIndex()
        .filter { (_, instruction) ->
            (instruction as? NarrowLiteralInstruction)?.narrowLiteral == SENSITIVITY_KEY_ID
        }
        .sole {
            "$what loads the sensitivity key id 0x${SENSITIVITY_KEY_ID.toString(16)} $it times, " +
                "expected exactly one — the method no longer reads the preference this patch scales"
        }

    val callIndex = body.indexOfSoleCall(PREFERENCE_GET_FLOAT, what)

    // Argument 2 of `A(String, F)F`: receiver, key, default. Read off the invoke rather than assumed,
    // so register reallocation between builds cannot silently move it.
    val defaultRegister = body[callIndex].invokeRegisterAt(2)

    val defaultIndex = (0 until callIndex)
        .lastOrNull { index ->
            body[index].opcodeName().startsWith("CONST") &&
                (body[index] as? OneRegisterInstruction)?.registerA == defaultRegister
        }
        ?: error("$what reads v$defaultRegister as the sensitivity default but never writes it")

    val literal = (body[defaultIndex] as NarrowLiteralInstruction).narrowLiteral
    check(literal == STOCK_RATIO) {
        "$what defaults the slide sensitivity ratio to 0x${literal.toString(16)}, not the " +
            "0x${STOCK_RATIO.toString(16)} (1.0f) this patch expects. Gboard has changed the " +
            "shipped default, and halving a number that is already tuned is a decision this patch " +
            "should not make blind."
    }

    // Same opcode, same width: `const/high16` carries the top sixteen bits and 0.5f fits exactly.
    check(body[defaultIndex].opcodeName() == "CONST_HIGH16") {
        "$what writes the default with ${body[defaultIndex].opcodeName()}, not const/high16 — " +
            "replacing it in kind would change the instruction's width"
    }
    method.assertRegisterCount(method.implementation!!.registerCount, what)
    method.replaceInstruction(
        defaultIndex,
        "const/high16 v$defaultRegister, 0x${HALVED_RATIO.toString(16)}",
    )
}
