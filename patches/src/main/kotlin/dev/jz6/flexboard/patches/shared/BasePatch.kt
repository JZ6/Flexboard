package dev.jz6.flexboard.patches.shared

import app.morphe.patcher.patch.bytecodePatch

/**
 * The foundation every Flexboard patch needs.
 *
 * Merges the extension DEX, publishes the running IME service to the extension, and adds the
 * Flexboard entry to Gboard's settings screen. Every public patch depends on this, so a user
 * cannot select a feature without the foundation it rests on — the extension is always merged,
 * the service is always reachable, and the settings row is always there.
 *
 * Internal so it never appears in Morphe's patch list. It runs as a dependency of every public
 * patch and is always selected when any of them is.
 *
 * `settingsScreenPatch` sits in this package rather than under `features/` for the same reason:
 * it is not a feature a user can decline, it is infrastructure every patch gets. Having the
 * foundation import a leaf was the wrong way round.
 */
internal val basePatch = bytecodePatch(
    description = "Merges the extension, publishes the IME service, and adds the Flexboard " +
        "settings entry to Gboard.",
) {
    extendWith("extensions/extension.mpe")

    dependsOn(settingsScreenPatch)

    execute {
        publishInputMethodService()
    }
}
