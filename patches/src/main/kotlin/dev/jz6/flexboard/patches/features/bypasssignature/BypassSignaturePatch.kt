package dev.jz6.flexboard.patches.features.bypasssignature

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.assertRegisterCount
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.callsMethod
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.indexOfSoleCall
import dev.jz6.flexboard.patches.shared.stringOrNull

/**
 * Bypasses only Gboard's own cold-start signature check.
 *
 * `Lrpv;->a` computes the SHA-256 of the signing certificate of the package it is handed and
 * compares it byte-for-byte against three baked-in digests. A re-signed build matches none of
 * them, so on an unpatched Flexboard the check returns false.
 *
 * There are exactly two callers of this signature check:
 *
 *  - `Lmm;->run()` case 8, reached from `LatinApp;->e()` — `new Lmm(applicationContext, 8)`, the
 *    only construction site using that selector — on cold start, guarded by `isMainProcess`. Its
 *    entire body is the check followed by `return-void`. On failure it throws
 *    `IllegalStateException("APK is signed by unrecognized certificates: …")`. On success it does
 *    nothing whatsoever, so a failing check skips no work, because there is none to skip.
 *  - `WebDebugBridgeContentProvider;->call`, which checks the *caller* of an exported developer
 *    debug provider. This must retain its original signature enforcement.
 *
 * No Flexboard subsystem references `Lrpv;` at all — not the preference store, the Phenotype flag
 * suppliers, the access points bar, the scrub handlers, or the IME.
 *
 * **Tested without this patch on 2026-08-18: the keyboard still opens.** The throw lands on the
 * background executor held in `LatinApp;->c` and does not take the process down. So what this
 * patch buys is the absence of a startup exception, not the presence of any feature.
 *
 * It is kept regardless. An exception on every cold start is worth silencing even when it is
 * survivable; it is presumably reported to Google's crash telemetry; and the alternative is
 * betting that a background throw stays harmless on every device and Android version rather than
 * only on the one it happened to be tried on. The derivation below is also among the more stable
 * in the project, so the cost per Gboard bump is low.
 *
 * ## The derivation
 *
 * We change only the result of the call in `Lmm;->run()V` immediately before its branch to the
 * self-check exception. The signature method is left untouched for the debug provider. The
 * adjacent `getPackageName`, call, move-result and if-nez, the exception message, and the frame
 * are all checked before emitting anything.
 */
@Suppress("unused")
val bypassGboardSignaturePatch = bytecodePatch(
    name = "Bypass Gboard Signature",
    description = "Bypass Gboard's own startup signature check without changing other callers.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        signatureCheckFingerprint().method // The check still has the expected owner and signature.
        signatureSelfCheckFingerprint().method.bypassOwnStartupCheck()
    }
}

private const val SIGNATURE_CHECK = "Lrpv;->a(Landroid/content/Context;Ljava/lang/String;)Z"
private const val SELF_CHECK = "Lmm;->run()V"
private const val GET_PACKAGE_NAME = "Landroid/content/Context;->getPackageName()Ljava/lang/String;"
private const val SELF_CHECK_ERROR = "APK is signed by unrecognized certificates: "
private const val SELF_CHECK_REGISTERS = 18

private fun MutableMethod.bypassOwnStartupCheck() {
    assertRegisterCount(SELF_CHECK_REGISTERS, SELF_CHECK)
    val body = instructions.toList()
    val call = body.indexOfSoleCall(SIGNATURE_CHECK, SELF_CHECK)
    check(body.count { it.stringOrNull() == SELF_CHECK_ERROR } == 1) {
        "$SELF_CHECK no longer contains the expected startup error"
    }
    check(call >= 2 && call + 2 < body.size && body[call - 2].callsMethod(GET_PACKAGE_NAME)) {
        "$SELF_CHECK no longer checks its own package immediately before $SIGNATURE_CHECK"
    }
    val result = body[call + 1]
    val register = (result as? OneRegisterInstruction)?.registerA
    check(result.opcodeName() == "MOVE_RESULT" && register != null && register <= 15) {
        "$SELF_CHECK no longer reads the boolean result of $SIGNATURE_CHECK into a const/4 register"
    }
    val branch = body[call + 2] as? OneRegisterInstruction
    check(branch?.opcodeName() == "IF_NEZ" && branch.registerA == register) {
        "$SELF_CHECK no longer branches on the signature-check result"
    }
    // Keep the stock call and move-result; only this self-check's branch sees the forced value.
    addInstruction(call + 2, "const/4 v$register, 0x1")
}
