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
 * it runs through two gates:
 *
 *  1. **`Lphn;->b(Context)I`** — the settings fragment and the key-release dispatch both call
 *     this. Its return decides which rows survive on the settings screen and whether the effect
 *     reaches the vibrator. Forcing it to **1** ("Gboard owns vibration") keeps the slider.
 *
 *  2. **`Lpho;->n()Z`** — a suppression gate in the key-release path. Even with the slider shown,
 *     a mode-1 Pixel on SDK ≥ 33 with `d:Z` set skips `f(I)V` (the `Vibrator.vibrate` call).
 *     Forcing it to **false** clears the gate.
 *
 * Both anchors are bare R8 letters, and the build pin does not protect them — `Patcher` never
 * reads `compatiblePackages`. So each overwrite is gated on the *shape* of the method it is about
 * to destroy: if R8 recycles `Lphn;`/`Lpho;` onto unrelated classes, the shape check fails the
 * patch instead of silently blanking someone else's method. See `docs/vibration.md`.
 */
@Suppress("unused")
val vibrationSliderPatch = bytecodePatch(
    name = "Vibration slider everywhere",
    description = "Forces Gboard to show its own vibration strength slider on every device, " +
        "rather than deferring to the system haptic settings page. The slider actually reaches " +
        "the vibrator instead of being suppressed by a server-side rollout.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        val mode = vibrationModeFingerprint().method
        mode.assertModeSelectorShape()
        mode.overwriteWithConstantReturn(1, "$VIBRATION_MODE_CLASS->b(Context)I")

        val suppression = vibrationSuppressionFingerprint().method
        suppression.assertSuppressionGateShape()
        suppression.overwriteWithConstantReturn(0, "$VIBRATION_PROVIDER_CLASS->n()Z")
    }
}

/** `Landroid/os/Build$VERSION;->SDK_INT:I`, read by both gates to branch on the platform. */
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
 * The suppression gate reads `SDK_INT` once and two of its own `boolean` fields (`d` and `g` on
 * this build). Both reads being owned by the defining class is the part that makes a recycled
 * letter fail here.
 */
private fun MutableMethod.assertSuppressionGateShape() {
    val body = implementation?.instructions?.toList()
        ?: error("$VIBRATION_PROVIDER_CLASS->n()Z has no implementation")

    val sdkReads = body.count { it.opcodeName().startsWith("SGET") && it.fieldDescriptor() == SDK_INT }
    check(sdkReads == 1) {
        "$VIBRATION_PROVIDER_CLASS->n()Z reads SDK_INT $sdkReads times, expected 1 — this is not " +
            "the suppression gate, so refusing to blank it"
    }

    val ownFlags = body.count {
        it.opcodeName() == "IGET_BOOLEAN" && it.fieldOwnerType() == VIBRATION_PROVIDER_CLASS
    }
    check(ownFlags == 2) {
        "$VIBRATION_PROVIDER_CLASS->n()Z reads $ownFlags of its own boolean fields, expected 2 — " +
            "this is not the suppression gate, so refusing to blank it"
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
