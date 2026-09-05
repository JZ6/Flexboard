package dev.jz6.flexboard.patches.features.hiddenfeatures

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.forceFlagsOn

/**
 * Turns on five finished Gboard features whose flags a patched build can never receive.
 *
 * Phenotype registers flags per package **and signing identity**. A Morphe build is resigned, so
 * GMS never attributes the flags to Gboard, the sync never lands, and every flag keeps the default
 * compiled into the APK. On 18.0.3 that is 666 booleans shipping `false`. Where Google enables one
 * server-side, a patched build simply loses the feature — no error, no setting, nothing to notice
 * beyond a row that used to be there. `Grammar Check Row` fixes exactly one instance of this; these
 * are five more.
 *
 * ## Why these five and not the other 661
 *
 * Because a flag shipping `false` is not evidence that anything was lost. Most of those 666 are off
 * for everyone: experiments, staged rollouts, dead code. Forcing one of those on is not restoring a
 * feature, it is enabling an unfinished one, and the failure mode is a half-built path nobody can
 * trace back to a patch.
 *
 * These five were chosen because each gates something Google ships publicly today, so the code
 * behind the flag is finished:
 *
 * | flag | feature |
 * |---|---|
 * | `enable_on_device_proofread` | on-device proofreading, the sibling of the grammar checker |
 * | `enable_emoji_kitchen_browse` | the Emoji Kitchen browse surface |
 * | `enable_custom_sticker_tab` | the custom sticker tab |
 * | `offline_translate` | translation without a network round trip |
 * | `enable_settings_search` | search within Gboard's own settings |
 *
 * Deliberately excluded, having been looked at: anything ending `_promo` (`handwriting`,
 * `language`, `split_layout`) and `enable_signboard`, which add nag prompts rather than features;
 * every child flag whose parent stays off, such as `enable_grammar_checker_on_webview` and
 * `enable_embedded_photo_picker_leak_fix`, since a fix flag for a disabled feature does nothing;
 * and `super_insert`, which is genuinely unreleased and whose providers read browsing history and
 * contacts.
 *
 * ## The trap under the emission
 *
 * Each flag is a `const-string` + `const/4` + factory triple, and the obvious edit is to flip the
 * zero nearest the name. That is not sufficient, and this nearly shipped wrong: the boolean
 * register is reused down the whole method — six flags in one `<clinit>` all pass `v1` — so
 * "a zero near the name" can be a constant several other flags also read. Flipping a shared one
 * turns them all on, silently.
 *
 * What makes it safe here is that each of these five re-initialises the register immediately
 * before its own call, which [forceFlagsOn] verifies rather than assumes: the constant must be
 * written *between* the flag's name and the factory call. A hoisted default is rejected outright.
 */
@Suppress("unused")
val hiddenFeaturesPatch = bytecodePatch(
    name = "Hidden Features",
    description = "Turns on five finished Gboard features that a patched build cannot receive: " +
        "on-device proofreading, Emoji Kitchen browse, the custom sticker tab, offline " +
        "translation, and search in Gboard's settings. Their flags are delivered per app " +
        "signature, so resigning the APK means they never arrive and stay off.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        forceFlagsOn(
            "enable_on_device_proofread",
            "enable_emoji_kitchen_browse",
            "enable_custom_sticker_tab",
            "offline_translate",
            "enable_settings_search",
        )
    }
}
