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
 * compiled into the APK. The corrected 18.0.3 scan finds 662 booleans shipping `false`. Where
 * Google enables one server-side, a patched build simply loses the feature — no error, no setting,
 * nothing to notice beyond a row that used to be there.
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
 * | `enable_emoji_kitchen_browse` | no effect seen — removed |
 * | `enable_custom_sticker_tab` | no effect seen — removed |
 * | `offline_translate` | no effect seen — removed |
 * | `enable_settings_search` | no effect seen — removed |
 *
 * Proofread crashes, which is unambiguous. "No effect seen" is a weaker claim than "no effect",
 * because each of the other four would show up somewhere a tester has to go looking: the emoji
 * picker, the sticker picker's tab strip, the translate bar with the network off, a search
 * affordance inside Gboard's settings. So they were kept for a month as a default-off patch,
 * "Hidden Features (unconfirmed)", where retesting cost a tick. Nobody saw any of them work, and
 * the dex says why (below): each needs something a resigned build does not receive, or has nothing
 * behind it at all. The patch was removed after 2.5.2-dev.0, whose bundle still has it if one
 * ever needs retesting.
 *
 * The full write-up, including the inventory and a classifier that did not work, is
 * `docs/phenotype-flags.md`.
 *
 * ## The rule those five taught
 *
 * The original test was that a flag "gates something Google ships publicly today, so the code
 * behind the flag is finished". All seven passed it, so it was not a test. The property that
 * decides the outcome is whether the flag is **self-contained**:
 *
 *  - `enable_on_device_proofread` fronts the Writing Tools / SAPI stack — an AICore LLM
 *    (`ON_DEVICE_LLM_INFERENCE_PROOFREAD`), a downloaded model (`Proofreader.downloadFeature`) and
 *    a version gate (`sapi_proofreader_version` against `sapi_proofreader_allowed_versions`),
 *    with roughly 170 `writing_tools_*` parameters behind the one boolean.
 *  - `enable_emoji_kitchen_browse` needs Mobile Data Download groups
 *    (`emoji_kitchen_mdd_data_file_group`, `emoji_kitchen_scam_index_data_file_group`).
 *  - `enable_custom_sticker_tab` is filtered by `custom_sticker_tab_locales`, an allowlist that
 *    arrives empty, so no locale ever qualifies.
 *  - `offline_translate` needs downloaded language packs.
 *  - `enable_settings_search` has no companion parameters in the dex at all, which is its own kind
 *    of answer: there is nothing here for it to switch on.
 *
 * The companions that exist are server-delivered too, so forcing the parent on does not supply
 * the configuration — it skips it. A boolean that reveals finished *local* code is safe to force.
 * A boolean that is the entry point to server-*configured* machinery is not, however public the
 * feature is. The two this patch forces on are the two that gate code already sitting in the APK,
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
            // Grammar checker's own zero is read by three later flags in Ljpf;. Close proactive
            // suggestions instead inherits the zero written for an earlier flag in Lqjx;.
            isolating = setOf(
                "enable_grammar_checker",
                "enable_close_proactive_suggestions_access_point",
            ),
        )
    }
}
