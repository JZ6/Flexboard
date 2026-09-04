package dev.jz6.flexboard.patches.features.vibration

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.fieldDescriptor
import dev.jz6.flexboard.patches.shared.fieldOwnerType
import dev.jz6.flexboard.patches.shared.opcodeName

/**
 * Makes Gboard's own vibration strength slider appear and work on every device.
 *
 * On stock Android (Pixels), Gboard strips the slider and the "Vibrate on keypress" toggle from
 * its settings screen and defers to the system haptic settings page — the "Keyboard vibration"
 * gear link is all that survives. On Samsung (and other OEMs that don't answer the haptic-settings
 * Intent Gboard probes for), Gboard keeps its own slider and the value flows to the vibrator.
 *
 * The split is driven by server-side Phenotype flags rolled per-device, not by a code defect, and
 * one method decides it: **`Lphn;->b(Context)I`**. The settings fragment and the key-release
 * dispatch both call it, its return picks which rows survive, and forcing it to **1** ("Gboard
 * owns vibration") is the whole patch.
 *
 * ## There was a second edit here, and it was the bug
 *
 * `Lpho;->n()Z` was read as a suppression gate — "returns true to skip the vibrator" — and forced
 * to **false** to clear it. That is backwards in both halves. The method is
 * `isVibrationEnabled`, which its own `dump()` prints under exactly that label, and every caller
 * treats it that way: `d(View, I)V` and `e(View, I)V` both `if-eqz` straight past the vibration
 * when it is false, and `h()Z` returns it verbatim as availability. Forcing false did not clear a
 * gate, it turned the vibrator off — the slider appeared and moved and did nothing, on the device
 * this patch was written for.
 *
 * It never needed patching. `n()` is `d && (SDK >= 33 || g || l())`, and `d` is the user's
 * "vibrate on keypress" toggle, read from `Lqhy;->am(String, Z, Z)Z`. On a modern Pixel with the
 * toggle on, the SDK arm already returns true. Overriding it would also override the toggle.
 *
 * The anchor is a bare R8 letter and the build pin does not protect it — `Patcher` never reads
 * `compatiblePackages` — so the overwrite is gated on the *shape* of the method it is about to
 * destroy: if R8 recycles `Lphn;` onto an unrelated class, the shape check fails the patch rather
 * than silently blanking someone else's method. See `docs/vibration.md`.
 */
@Suppress("unused")
val vibrationSliderPatch = bytecodePatch(
    name = "Vibration Slider Everywhere",
    description = "Forces Gboard to show its own vibration strength slider on every device, " +
        "rather than deferring to the system haptic settings page, so the strength is adjustable " +
        "instead of being fixed by whichever rollout the device landed in.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        val mode = vibrationModeFingerprint().method
        mode.assertModeSelectorShape()
        mode.overwriteWithConstantReturn(1, "$VIBRATION_MODE_CLASS->b(Context)I")
    }
}

/** `Landroid/os/Build$VERSION;->SDK_INT:I`, which the mode selector branches on. */
private const val SDK_INT = "Landroid/os/Build\$VERSION;->SDK_INT:I"

/**
 * The mode selector reads `SDK_INT` once and returns one of three distinct small constants — the
 * 1/2/3 the settings fragment switches on. A recycled `Lphn;` with an unrelated `b(Context)I`
 * will not have that shape.
 */
private fun MutableMethod.assertModeSelectorShape() {
    val body = implementation?.instructions?.toList()
        ?: error("$VIBRATION_MODE_CLASS->b(Context)I has no implementation")

    val sdkReads = body.count { it.opcodeName().startsWith("SGET") && it.fieldDescriptor() == SDK_INT }
    check(sdkReads == 1) {
        "$VIBRATION_MODE_CLASS->b(Context)I reads SDK_INT $sdkReads times, expected 1 — this is " +
            "not the mode selector, so refusing to blank it"
    }

    val returns = body.count { it.opcodeName().startsWith("RETURN") }
    check(returns >= 3) {
        "$VIBRATION_MODE_CLASS->b(Context)I has $returns return sites, expected at least 3 (one " +
            "per mode) — this is not the mode selector, so refusing to blank it"
    }
}

/**
 * Replaces a method body with `const/4 v0, <value>` / `return v0`.
 *
 * The tail is left in place and becomes unreachable, which is fine, but only under conditions the
 * caller cannot eyeball: nothing may branch back into the two instructions being replaced, no try
 * block may cover them, and `v0` has to exist. Those were true of both vibration gates on 18.0.3
 * when this was written; asserting them means a build where they stop being true fails loudly
 * rather than emitting a method the verifier rejects.
 */
private fun MutableMethod.overwriteWithConstantReturn(value: Int, what: String) {
    check(value in 0..7) { "$what: const/4 encodes 4-bit signed; $value is out of the 0..7 used here" }

    val implementation = implementation ?: error("$what has no implementation to overwrite")
    check(implementation.registerCount >= 1) {
        "$what declares ${implementation.registerCount} registers, so v0 is not addressable"
    }
    check(implementation.tryBlocks.isEmpty()) {
        "$what has ${implementation.tryBlocks.size} try block(s); replacing its head would leave " +
            "a handler range covering instructions that no longer exist"
    }

    val body = implementation.instructions.toList()
    check(body.size >= 2) { "$what has ${body.size} instruction(s); the overwrite needs 2" }

    // Code addresses are in 16-bit units, and a branch offset is relative to its own address.
    val addresses = IntArray(body.size)
    var end = 0
    body.forEachIndexed { index, instruction ->
        addresses[index] = end
        end += instruction.codeUnits
    }
    val firstSurviving = if (body.size > 2) addresses[2] else end

    body.forEachIndexed { index, instruction ->
        check(instruction !is SwitchPayload) {
            "$what contains a switch payload, whose targets this overwrite does not verify"
        }
        if (instruction !is OffsetInstruction) return@forEachIndexed
        val target = addresses[index] + instruction.codeOffset
        check(target >= firstSurviving) {
            "$what branches to code address $target, which is inside the two instructions being " +
                "replaced — the overwrite would leave an invalid branch target"
        }
    }

    replaceInstruction(0, "const/4 v0, 0x$value")
    replaceInstruction(1, "return v0")
}
