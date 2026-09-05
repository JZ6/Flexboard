package dev.jz6.flexboard.patches.features.swipetodelete

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.features.swipetodelete.scrubTuningPatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.SettingsSection
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.selectedSettingsSections


/**
 * Gboard already implements swipe-to-delete-a-word. `ScrubDeleteMotionEventHandler` is the swipe
 * on the backspace key, and everything it does — progressive delete, drag back to restore,
 * distance thresholds, tracking across the full keyboard width — lives in a shared
 * `ScrubMotionEventHandler` that is entirely key-agnostic once a gesture has begun.
 *
 * The only thing scoping it to backspace is one comparison in `g(Landroid/view/MotionEvent;)V`:
 *
 * ```
 * iget   v5, v5, Lpnu;->c:I     # keycode of the key under the finger
 * iget   v6, v1, Lpvs;->a:I     # the configured start keycode, 67 for delete
 * if-ne  v5, v6, -> bail
 * ```
 *
 * So this patch does not implement a gesture. It widens where Gboard's own gesture is allowed to
 * start, and where it is allowed to travel, in three edits:
 *
 *  1. `ScrubDeleteMotionEventHandler.<init>` passes **-1** instead of `KEYCODE_DEL`.
 *  2. `g()` skips the comparison when the configured keycode is negative.
 *  3. `g()` gives the tracking rect the full keyboard height, not one key's worth.
 *
 * A negative sentinel is what makes the second edit register-free: every Android keycode is
 * non-negative, so the test is `if-ltz` — format 21t, one register, no constant, and therefore no
 * need to prove some register is dead at that point in a 259-instruction method. The same sentinel
 * is what [scrubTuningPatch] tests to scope its values to this handler, and what scopes the
 * full-height rect below.
 *
 * ## There is no in-app off switch, deliberately
 *
 * The sentinel used to be a *choice*, read from a `flexboard_enabled` preference at construction, so
 * that turning Flexboard off left Gboard exactly as it shipped. That was removed: reading a
 * preference there cost three scratch registers whose deadness had to be re-proved against every
 * Gboard build, and it was the single most intricate insertion in the project. Morphe already lets
 * a user not apply a patch, which is where turning a feature off belongs.
 *
 * One consequence worth stating plainly: [forceScrubPreferencesPatch] now forces glide typing off
 * unconditionally for as long as this patch is applied, and [glideTypingRowPatch] greys those rows
 * out statically rather than through a dependency. Getting glide typing back means re-patching
 * without this patch.
 *
 * The sentinel still does the scoping work it always did. `ScrubMoveMotionEventHandler` (spacebar
 * cursor) and `InlineSuggestionScrubSpaceMotionEventHandler` both pass 62, so their gate still
 * enforces and none of Flexboard's tuning reaches them.
 *
 * See `docs/motion-event-handlers.md` for how all of this was derived.
 */
@Suppress("unused")
val swipeToDeletePatch = bytecodePatch(
    name = "Swipe Left to Delete",
    description = "Swipe left anywhere on the keyboard to delete the previous word, and swipe " +
        "right to restore it. Uses Gboard's own word-scrub engine, so it behaves exactly like " +
        "swiping on the backspace key already does — only it can start anywhere.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    // Widening the gate is pointless if the handler is never attached, and unusable while glide
    // typing is live on the same pointer stream.
    dependsOn(forceScrubPreferencesPatch)

    // Greys out Gboard's glide typing row for as long as the above is forcing it off, so the
    // conflict shows up in the settings rather than as a setting that will not stay on.
    dependsOn(glideTypingRowPatch)

    // Supplies the word cap and the settings row behind it, and substitutes the hold delay so the
    // widened gesture answers to a flick rather than Gboard's 200 ms press-and-drag. It no longer
    // supplies a swipe length: that emitter is parked, and there is no row for either it or the
    // delay.
    dependsOn(scrubTuningPatch)

    execute {
        scrubDeleteConstructorFingerprint().method.writeWildcardStartKey()

        // Both edit `g()`, and both add an instruction to it. Neither depends on running first:
        // each locates what it needs by a shape the other does not produce. See the note on
        // [trackAcrossFullKeyboard] for why that mattered enough to design for.
        //
        // Resolved once and shared. Two calls would match the same method twice — correct, since
        // matching runs against the original definition rather than the mutated one, but it is
        // wasted work and reads as though the second edit wanted a fresh view of the first's
        // output, which it explicitly does not.
        val handleMotionEvent = scrubHandleMotionEventFingerprint().method
        handleMotionEvent.acceptWildcardStartKey()
        handleMotionEvent.trackAcrossFullKeyboard()

        // Registered last, on purpose. A failing patch does not abort the run: the patcher records
        // the exception and moves on, and `settingsScreenPatch` — which did not fail — still
        // finalizes and reads this set. Registering before the edits above would ship the Swipe
        // rows for a build whose bytecode never got the feature.
        selectedSettingsSections += SettingsSection.SWIPE_TO_DELETE
    }
}
