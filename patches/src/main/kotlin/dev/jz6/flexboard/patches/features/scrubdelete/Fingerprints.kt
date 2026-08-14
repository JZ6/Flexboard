package dev.jz6.flexboard.patches.features.scrubdelete

import app.morphe.patcher.Fingerprint

/**
 * The scrub classes are not obfuscated, so both fingerprints can name them outright. Everything
 * else about them changes between Gboard builds, which is why the patch verifies instruction
 * shape at patch time rather than trusting these to be sufficient.
 */

internal const val SCRUB_MOTION_EVENT_HANDLER =
    "Lcom/google/android/libraries/inputmethod/motioneventhandler/scrubmove/ScrubMotionEventHandler;"

internal const val SCRUB_DELETE_MOTION_EVENT_HANDLER =
    "Lcom/google/android/libraries/inputmethod/motioneventhandler/scrubmove/ScrubDeleteMotionEventHandler;"

/**
 * The shared engine's entry point. Holds the single comparison that decides whether a scrub may
 * begin, for every subclass — delete, spacebar move, and inline suggestion alike.
 */
object ScrubHandleMotionEventFingerprint : Fingerprint(
    definingClass = SCRUB_MOTION_EVENT_HANDLER,
    name = "g",
    parameters = listOf("Landroid/view/MotionEvent;"),
    returnType = "V",
)

/**
 * `ScrubDeleteMotionEventHandler` declares exactly one method. Its whole contribution is building
 * the `Lpbv;` config it hands to the shared engine, the first argument of which is the keycode the
 * drag must start on.
 */
object ScrubDeleteConstructorFingerprint : Fingerprint(
    definingClass = SCRUB_DELETE_MOTION_EVENT_HANDLER,
    name = "<init>",
    parameters = listOf("Landroid/content/Context;", "Lpbr;"),
    returnType = "V",
)
