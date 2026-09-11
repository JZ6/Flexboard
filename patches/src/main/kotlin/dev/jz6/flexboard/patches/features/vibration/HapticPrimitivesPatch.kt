package dev.jz6.flexboard.patches.features.vibration

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.sole
import dev.jz6.flexboard.patches.shared.stringOrNull

private const val VIBRATION_EFFECT_MIN_SDK = "vibration_effect_min_sdk"

/**
 * What Gboard ships: an API level no device will ever report, which is a disabled feature written
 * as a number rather than as a boolean.
 */
private const val STOCK_MIN_SDK = 1024L

/**
 * `VibrationEffect.Composition.addPrimitive` is API 30, and `Lpho;->k` already refuses below 30 on
 * the line above this comparison. So 30 is both the honest minimum and a no-op relative to the
 * check that precedes it — the patch is not lowering a real floor, it is removing a fake ceiling.
 */
private const val COMPOSITION_MIN_SDK = 30L

private fun hapticFlagHolderFingerprint() = Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC),
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    strings = listOf(VIBRATION_EFFECT_MIN_SDK),
)

/**
 * Gives keypresses Android's modern haptic primitives instead of a plain buzz.
 *
 * `Lpho;->f(I)V` is Gboard's vibrate call and it has two arms:
 *
 *  - **primitives** — `VibrationEffect.startComposition().addPrimitive(1, strength * 0.01)`, the
 *    crisp tick a Pixel uses everywhere else in the system
 *  - **legacy** — `VibrationEffect.createOneShot(duration, DEFAULT_AMPLITUDE)`, an undifferentiated
 *    buzz for a number of milliseconds
 *
 * Which arm runs is decided by `Lpho;->k(Vibrator)`, and that is three conditions:
 *
 * 1. `SDK_INT >= 30`
 * 2. `SDK_INT >= vibration_effect_min_sdk`
 * 3. `Vibrator.areAllEffectsSupported(...)` says the hardware can do it
 *
 * The flag in (2) ships as **1024**. No Android version is or will be 1024, so (2) can never hold
 * and the primitive arm is dead code on every device. This patch rewrites that literal to 30.
 *
 * ## Why this one is safe to force
 *
 * `docs/phenotype-flags.md` records the rule the expensive way: a flag revealing finished *local*
 * code is safe, a flag fronting server-*configured* machinery is not. This is as self-contained as
 * the rule gets — the code behind it is `android.os.VibrationEffect`, in the platform, on the
 * device. There is no model, no download, no allowlist and no consent.
 *
 * It is also the rare case where the **hardware check survives the patch**. Condition (3) is
 * Gboard asking the vibrator what it supports, and this patch does not touch it, so a device
 * without primitive support still takes the legacy arm. The worst outcome is that nothing changes.
 *
 * ## What it changes about the slider
 *
 * Worth knowing before turning it on, because it is not purely additive. On the legacy arm the
 * strength value is a **duration in milliseconds**. On the primitive arm the same value is scaled
 * by `0.01` and used as a primitive **intensity** — so the number behind the slider stops meaning
 * milliseconds. Gboard's own design, not something this patch introduces, but it means
 * [vibrationPatch]'s slider will feel differently calibrated once this is on.
 *
 * **Unverified on a device.** Off by default.
 */
@Suppress("unused")
val hapticPrimitivesPatch = bytecodePatch(
    name = "Modern keypress haptics",
    description = "Uses Android's haptic primitives for keypresses — the crisp tick the rest of " +
        "the system uses — instead of a plain buzz. Gboard has the code and disables it with an " +
        "impossible minimum Android version; this removes that. Devices whose vibrator cannot do " +
        "primitives are unaffected, because Gboard's own hardware check still runs. Note that it " +
        "changes what the vibration strength slider means, from milliseconds to intensity. Off " +
        "by default and not yet confirmed on a device.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        lowerHapticMinimumSdk()
    }
}

private fun BytecodePatchContext.lowerHapticMinimumSdk() {
    val method = hapticFlagHolderFingerprint().method
    val body = method.instructions.toList()

    val nameIndex = body.withIndex()
        .filter { (_, instruction) -> instruction.stringOrNull() == VIBRATION_EFFECT_MIN_SDK }
        .sole {
            "\"$VIBRATION_EFFECT_MIN_SDK\" is loaded $it times in ${method.definingClass}, expected 1"
        }
        .index

    // const-string, const-wide, invoke-static — searched rather than assumed adjacent.
    val defaultIndex = (nameIndex + 1 until minOf(nameIndex + 5, body.size))
        .firstOrNull { body[it].opcodeName().startsWith("CONST_WIDE") }
        ?: error(
            "No const-wide follows \"$VIBRATION_EFFECT_MIN_SDK\" in ${method.definingClass} — it " +
                "is no longer declared as a long flag",
        )

    val literal = (body[defaultIndex] as WideLiteralInstruction).wideLiteral
    check(literal == STOCK_MIN_SDK) {
        "\"$VIBRATION_EFFECT_MIN_SDK\" is $literal, not $STOCK_MIN_SDK. Gboard has changed the " +
            "minimum it ships, and a value that is no longer an impossible sentinel is a rollout " +
            "decision this patch should not be overriding blind."
    }

    val register = (body[defaultIndex] as OneRegisterInstruction).registerA
    method.replaceInstruction(defaultIndex, "const-wide/16 v$register, 0x${COMPOSITION_MIN_SDK.toString(16)}")
}
