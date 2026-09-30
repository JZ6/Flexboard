package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch

/**
 * Measures upward flicks and types what it saw, so swipe up can be tuned against numbers rather than
 * guesses.
 *
 * ### Where it measures, and why that moved
 *
 * It now observes from the scrub engine's `g(MotionEvent)` — the motion-event-handler layer that
 * swipe left and swipe right run on. The previous version reported from `Lpvi;->G`, and a device
 * showed why that could never work: **a flick from the top row reported nothing, the same flick from
 * the bottom row reported a number.** `G` only runs while the finger is still attached to a key.
 * Letter keys declare no upward action, so Gboard treats an upward slide as moving onto another key,
 * and above the top row there is no key — the finger is detached and `G` is never reached. The long,
 * clean flicks that should succeed were exactly the ones it could not see.
 *
 * A handler has no such blind spot: it receives DOWN, every MOVE and UP for the view the finger went
 * down on, attached or not. See [emitFlickProbe] and `FlickProbe.java`.
 *
 * ### What it types
 *
 * `u<rise>/<drift>s<samples>=<outcome>` after each release that rose at least a quarter of the flick
 * distance, rise and drift in dp. 6 met the 24dp threshold and the 2:1 corridor; 2 was too short; 3
 * was too diagonal. It only observes — it claims nothing, undoes nothing, and does not stop the key
 * being typed.
 *
 * Needs "Swipe Left to Delete" enabled, because it rides on that handler; it is on by default.
 */
@Suppress("unused")
val undoAutocorrectDiagnosticPatch = bytecodePatch(
    name = "Swipe up diagnostic (temporary)",
    description = "Diagnostic build only. After each upward swipe, types what the detector " +
        "measured: u<rise>/<drift>s<samples>=<outcome>, with rise and drift in dp. 6 means it " +
        "would count as a swipe up, 2 means too short, 3 means too diagonal. It only reports: it " +
        "does not undo anything and does not stop the key being typed. Needs Swipe Left to Delete " +
        "enabled. Off by default, and this patch will be removed once it has answered its question.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        emitFlickProbe()
    }
}
