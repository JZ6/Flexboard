package dev.jz6.flexboard.patches.features.hiddenfeatures

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.forceFlagsOn

/**
 * Seven finished Gboard features whose flags a patched build can never receive, one patch each.
 *
 * Phenotype registers flags per package **and signing identity**. A Morphe build is resigned, so
 * GMS never attributes the flags to Gboard, the sync never lands, and every flag keeps the default
 * compiled into the APK. On 18.0.3 that is 666 booleans shipping `false`. Where Google enables one
 * server-side, a patched build simply loses the feature — no error, no setting, nothing to notice
 * beyond a row that used to be there.
 *
 * This began as `Grammar Check Row`, which fixed a single instance: the grammar checker's settings
 * row vanished on patched builds and nobody could say why. The mechanism turned out to be general,
 * so that patch is folded in here rather than left as one of two things doing the same job.
 *
 * ## Why these seven and not the other 659
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
 * | `enable_grammar_checker` | the grammar check settings row, and the checking behind it |
 * | `enable_on_device_proofread` | on-device proofreading, the grammar checker's sibling |
 * | `enable_emoji_kitchen_browse` | the Emoji Kitchen browse surface |
 * | `enable_custom_sticker_tab` | the custom sticker tab |
 * | `offline_translate` | translation without a network round trip |
 * | `enable_close_proactive_suggestions_access_point` | a close control on the chips Gboard offers unprompted |
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
 * What makes it safe here is that each of these re-initialises the register immediately before
 * its own call, which [forceFlagsOn] verifies rather than assumes: the constant must be written
 * *between* the flag's name and the factory call. A hoisted default is rejected outright.
 *
 * ## Why one patch per flag
 *
 * v2.3.0-dev.0 shipped these as a single default-on patch and Gboard would not start. Unticking it
 * restored the keyboard, which localises the fault to this file but not to a flag -- and the list is
 * compiled in, so narrowing it by rebuilding costs a release per bisection step.
 *
 * Split, the flags become checkboxes and the search runs on-device against one build. That is the
 * whole reason for the shape. The verified-safe emission is unchanged; only the grouping moved.
 *
 * All seven are opt-in. Six have never run on hardware, and the seventh reaches its flag through
 * machinery that is also new, so none of them has earned a default. Whichever flag turns out to be
 * at fault, the rest should stay opt-in until each has actually been seen working.
 */

/**
 * `enable_grammar_checker` -- grammar mistakes underlined as you type, and the settings row that switches them on.
 *
 * Gboard would not start with all seven forced on at once (v2.3.0-dev.0). Which flag is
 * responsible is not yet known, so every one of them is opt-in.
 */
@Suppress("unused")
val hiddenGrammarCheckerPatch = bytecodePatch(
    name = "Hidden: grammar check",
    description = "Turns on one finished Gboard feature that a resigned build can never " +
        "receive: grammar mistakes underlined as you type, and the settings row that switches them on. Phenotype delivers flags per app " +
        "signature, so a patched APK never receives them and the feature stays off. " +
        "Opt-in: one of these seven stops Gboard starting and it is not yet known which.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        forceFlagsOn(
            "enable_grammar_checker",
        )
    }
}

/**
 * `enable_on_device_proofread` -- on-device proofreading, the grammar checker's sibling.
 *
 * Gboard would not start with all seven forced on at once (v2.3.0-dev.0). Which flag is
 * responsible is not yet known, so every one of them is opt-in.
 */
@Suppress("unused")
val hiddenProofreadPatch = bytecodePatch(
    name = "Hidden: proofread",
    description = "Turns on one finished Gboard feature that a resigned build can never " +
        "receive: on-device proofreading, the grammar checker's sibling. Phenotype delivers flags per app " +
        "signature, so a patched APK never receives them and the feature stays off. " +
        "Opt-in: one of these seven stops Gboard starting and it is not yet known which.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        forceFlagsOn(
            "enable_on_device_proofread",
        )
    }
}

/**
 * `enable_emoji_kitchen_browse` -- browsing Emoji Kitchen rather than only being offered its suggestions.
 *
 * Gboard would not start with all seven forced on at once (v2.3.0-dev.0). Which flag is
 * responsible is not yet known, so every one of them is opt-in.
 */
@Suppress("unused")
val hiddenEmojiKitchenBrowsePatch = bytecodePatch(
    name = "Hidden: Emoji Kitchen browse",
    description = "Turns on one finished Gboard feature that a resigned build can never " +
        "receive: browsing Emoji Kitchen rather than only being offered its suggestions. Phenotype delivers flags per app " +
        "signature, so a patched APK never receives them and the feature stays off. " +
        "Opt-in: one of these seven stops Gboard starting and it is not yet known which.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        forceFlagsOn(
            "enable_emoji_kitchen_browse",
        )
    }
}

/**
 * `enable_custom_sticker_tab` -- a tab for stickers you added yourself.
 *
 * Gboard would not start with all seven forced on at once (v2.3.0-dev.0). Which flag is
 * responsible is not yet known, so every one of them is opt-in.
 */
@Suppress("unused")
val hiddenCustomStickerTabPatch = bytecodePatch(
    name = "Hidden: custom sticker tab",
    description = "Turns on one finished Gboard feature that a resigned build can never " +
        "receive: a tab for stickers you added yourself. Phenotype delivers flags per app " +
        "signature, so a patched APK never receives them and the feature stays off. " +
        "Opt-in: one of these seven stops Gboard starting and it is not yet known which.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        forceFlagsOn(
            "enable_custom_sticker_tab",
        )
    }
}

/**
 * `offline_translate` -- translation without a network round trip.
 *
 * Gboard would not start with all seven forced on at once (v2.3.0-dev.0). Which flag is
 * responsible is not yet known, so every one of them is opt-in.
 */
@Suppress("unused")
val hiddenOfflineTranslatePatch = bytecodePatch(
    name = "Hidden: offline translate",
    description = "Turns on one finished Gboard feature that a resigned build can never " +
        "receive: translation without a network round trip. Phenotype delivers flags per app " +
        "signature, so a patched APK never receives them and the feature stays off. " +
        "Opt-in: one of these seven stops Gboard starting and it is not yet known which.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        forceFlagsOn(
            "offline_translate",
        )
    }
}

/**
 * `enable_close_proactive_suggestions_access_point` -- a close control on the chips Gboard offers unprompted.
 *
 * The one flag of the seven with no constant of its own, so it takes the
 * isolating emission rather than a straight flip.
 *
 * Gboard would not start with all seven forced on at once (v2.3.0-dev.0). Which flag is
 * responsible is not yet known, so every one of them is opt-in.
 */
@Suppress("unused")
val hiddenDismissableChipsPatch = bytecodePatch(
    name = "Hidden: dismissable chips",
    description = "Turns on one finished Gboard feature that a resigned build can never " +
        "receive: a close control on the chips Gboard offers unprompted. Phenotype delivers flags per app " +
        "signature, so a patched APK never receives them and the feature stays off. " +
        "Opt-in: one of these seven stops Gboard starting and it is not yet known which.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        forceFlagsOn(
            "enable_close_proactive_suggestions_access_point",
            // Gboard hoists one zero in Lqjx; and feeds it to this flag and
            // enable_auto_fill_pk_fallback_ui both. Rewriting it would turn on an
            // unrelated autofill surface, so this one gets a constant scoped to its
            // own call.
            isolating = setOf("enable_close_proactive_suggestions_access_point"),
        )
    }
}

/**
 * `enable_settings_search` -- search within Gboard's own settings.
 *
 * Gboard would not start with all seven forced on at once (v2.3.0-dev.0). Which flag is
 * responsible is not yet known, so every one of them is opt-in.
 */
@Suppress("unused")
val hiddenSettingsSearchPatch = bytecodePatch(
    name = "Hidden: settings search",
    description = "Turns on one finished Gboard feature that a resigned build can never " +
        "receive: search within Gboard's own settings. Phenotype delivers flags per app " +
        "signature, so a patched APK never receives them and the feature stays off. " +
        "Opt-in: one of these seven stops Gboard starting and it is not yet known which.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        forceFlagsOn(
            "enable_settings_search",
        )
    }
}
