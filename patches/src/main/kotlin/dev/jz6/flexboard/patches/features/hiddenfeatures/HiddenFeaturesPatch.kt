package dev.jz6.flexboard.patches.features.hiddenfeatures

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.forceFlagsOn

/**
 * Two finished Gboard features whose flags a patched build can never receive, forced on.
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
 * | flag | feature |
 * |---|---|
 * | `enable_grammar_checker` | the grammar check settings row, and the checking behind it |
 * | `enable_close_proactive_suggestions_access_point` | a close control on the chips Gboard offers unprompted |
 *
 * ## Two, out of the seven that were tried
 *
 * v2.3.0-dev.2 shipped seven candidate flags as seven separate opt-in patches, so each could be
 * ticked alone against a device. Only these two did anything. The other five are recorded here
 * because the next person to read a flag list will otherwise try them again:
 *
 * | flag | outcome on a device |
 * |---|---|
 * | `enable_on_device_proofread` | **Gboard would not start at all** — removed |
 * | `enable_emoji_kitchen_browse` | no effect seen — kept, opt-in |
 * | `enable_custom_sticker_tab` | no effect seen — kept, opt-in |
 * | `offline_translate` | no effect seen — kept, opt-in |
 * | `enable_settings_search` | no effect seen — kept, opt-in |
 *
 * Only proofread is settled: it crashes, which is unambiguous. "No effect seen" is a weaker claim
 * than "no effect", because each of those four shows up somewhere that has to be gone looking for
 * — the emoji picker, the sticker picker's tab strip, the translate bar with the network off, a
 * search affordance inside Gboard's settings. Absence of a sighting is not absence. They stay
 * behind [hiddenFeaturesUnconfirmedPatch] so retesting costs a tick rather than a release, and
 * they are off by default so nobody is told they work.
 *
 * ## The rule those five taught
 *
 * The original test was that a flag "gates something Google ships publicly today, so the code
 * behind the flag is finished". All seven passed it, so it was not a test. The property that
 * decides the outcome is whether the flag is **self-contained**:
 *
 *  - `enable_on_device_proofread` fronts the Writing Tools / SAPI stack — an AICore LLM
 *    (`ON_DEVICE_LLM_INFERENCE_PROOFREAD`), a downloaded model (`Proofreader.downloadFeature`) and
 *    a version gate gate (`sapi_proofreader_version` against `sapi_proofreader_allowed_versions`),
 *    with roughly 170 `writing_tools_*` parameters behind the one boolean.
 *  - `enable_emoji_kitchen_browse` needs Mobile Data Download groups
 *    (`emoji_kitchen_mdd_data_file_group`, `emoji_kitchen_scam_index_data_file_group`).
 *  - `enable_custom_sticker_tab` is filtered by `custom_sticker_tab_locales`, an allowlist that
 *    arrives empty, so no locale ever qualifies.
 *  - `offline_translate` needs downloaded language packs.
 *  - `enable_settings_search` has no companion parameters in the dex at all, which is its own kind
 *    of answer: there is nothing here for it to switch on.
 *
 * Every one of those companions is server-delivered too, so forcing the parent on does not supply
 * the configuration — it skips it. A boolean that reveals finished *local* code is safe to force.
 * A boolean that is the entry point to server-*configured* machinery is not, however public the
 * feature is. The two that are default-on are the two that gate code already sitting in the APK,
 * and the two that have been watched working.
 *
 * Excluded before testing, and still excluded: anything ending `_promo` (`handwriting`, `language`,
 * `split_layout`) and `enable_signboard`, which add nag prompts rather than features; every child
 * flag whose parent stays off, such as `enable_grammar_checker_on_webview`; and `super_insert`,
 * which is genuinely unreleased and whose providers read browsing history and contacts.
 *
 * ## The trap under the emission
 *
 * Each flag is a `const-string` + `const/4` + factory triple, and the obvious edit is to flip the
 * zero nearest the name. That is not sufficient, and this nearly shipped wrong: the boolean
 * register is reused down the whole method — six flags in one `<clinit>` all pass `v1` — so
 * "a zero near the name" can be a constant several other flags also read. Flipping a shared one
 * turns them all on, silently.
 *
 * [forceFlagsOn] verifies rather than assumes: the constant must be written *between* the flag's
 * name and the factory call. A hoisted default is rejected outright unless the flag is named in
 * `isolating`, which gives it a constant scoped to its own call instead.
 */
@Suppress("unused")
val hiddenFeaturesPatch = bytecodePatch(
    name = "Hidden Features",
    description = "Turns on two finished Gboard features that a resigned build can never receive: " +
        "grammar check, and a close control on the chips Gboard offers unprompted. Their flags " +
        "are delivered per app signature, so resigning the APK means they never arrive and stay " +
        "off. Both are confirmed working on a device; five other flags were tried and dropped.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        forceFlagsOn(
            "enable_grammar_checker",
            // Gboard hoists one zero in Lqjx; and feeds it to this flag and
            // enable_auto_fill_pk_fallback_ui both. Rewriting it would turn on an unrelated
            // autofill surface, so this one gets a constant scoped to its own call.
            "enable_close_proactive_suggestions_access_point",
            isolating = setOf("enable_close_proactive_suggestions_access_point"),
        )
    }
}


/**
 * The four flags that were tried, did nothing visible, and are not proven harmless-and-useless
 * enough to delete.
 *
 * None of them crashes — that was checked one at a time on a device, which is the only reason they
 * can be offered at all. What is *not* established is that they do nothing: each surfaces somewhere
 * a tester has to navigate to, so the evidence is "looked, did not see it", which is a weaker
 * statement than it sounds.
 *
 * Where to look, if you are the one retesting:
 *
 * | flag | where it would show |
 * |---|---|
 * | `enable_emoji_kitchen_browse` | a browse surface in the emoji picker, not just suggested combos |
 * | `enable_custom_sticker_tab` | a tab for your own stickers in the sticker picker |
 * | `offline_translate` | the translate bar continuing to work with the network off |
 * | `enable_settings_search` | a search affordance inside Gboard's own settings |
 *
 * The dex says all four are gated on data a resigned build never receives — download groups, an
 * empty locale allowlist, language packs — so the expected result is still nothing. Off by default
 * for exactly that reason: a patch that probably does nothing must not claim otherwise in the
 * picker.
 */
@Suppress("unused")
val hiddenFeaturesUnconfirmedPatch = bytecodePatch(
    name = "Hidden Features (unconfirmed)",
    description = "Turns on four finished Gboard features that a resigned build can never " +
        "receive: Emoji Kitchen browse, the custom sticker tab, offline translation, and search " +
        "in Gboard's settings. Off by default because none of the four has been seen working on " +
        "a device — the flags flip, but each also depends on data Google only sends to an " +
        "unpatched install. Safe to try: none of them crashes.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        forceFlagsOn(
            "enable_emoji_kitchen_browse",
            "enable_custom_sticker_tab",
            "offline_translate",
            "enable_settings_search",
        )
    }
}
