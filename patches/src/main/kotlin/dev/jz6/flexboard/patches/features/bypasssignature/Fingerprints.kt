package dev.jz6.flexboard.patches.features.bypasssignature

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/** The signature check is pinned so an unexpected Gboard build fails before editing the caller. */
internal fun signatureCheckFingerprint() = Fingerprint(
    definingClass = "Lrpv;",
    name = "a",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    returnType = "Z",
)

/** The *self-check* call site, not the exported debug provider's other call site. */
internal fun signatureSelfCheckFingerprint() = Fingerprint(
    definingClass = "Lmm;",
    name = "run",
    parameters = emptyList(),
    returnType = "V",
)
