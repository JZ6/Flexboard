package dev.jz6.flexboard.patches.features.bypasssignature

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Gboard's signature whitelist check.
 *
 * `Lrpv;` is a bare R8 letter and will move between builds, so the name is not the defence: the
 * patch asserts the method's shape -- its field reads, its register count, its return registers --
 * before overwriting anything, and a recycled letter fails that assertion rather than the build.
 * Anchor shape, not names.
 */
internal fun signatureCheckFingerprint() = Fingerprint(
    definingClass = "Lrpv;",
    name = "a",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    returnType = "Z",
)
