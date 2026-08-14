package dev.jz6.flexboard.patches.features.scrubdelete

import app.morphe.patcher.Fingerprint

/**
 * The scrub classes are not obfuscated, so every fingerprint here can name its class outright —
 * Gboard attaches motion event handlers by class-name string from `res/aDh.xml`, which is why those
 * names survive R8. Everything *inside* them changes between builds, which is why the patches
 * verify instruction shape at patch time rather than trusting these to be sufficient.
 *
 * The obfuscated descriptors below are reached only through those stable classes.
 */

internal const val SCRUB_MOTION_EVENT_HANDLER =
    "Lcom/google/android/libraries/inputmethod/motioneventhandler/scrubmove/ScrubMotionEventHandler;"

internal const val SCRUB_DELETE_MOTION_EVENT_HANDLER =
    "Lcom/google/android/libraries/inputmethod/motioneventhandler/scrubmove/ScrubDeleteMotionEventHandler;"

/** The keycode a drag must start on, and the sentinel the patches test for. */
internal const val CONFIG_START_KEY_FIELD = "Lpbv;->a:I"

/**
 * The distance table. `r()` counts how many of its entries `abs(delta)` has passed, and that count
 * is the number of words. **Not final**, unlike everything in `Lpbu;`, so its contents can be
 * scaled in place.
 */
internal const val CONFIG_STEP_TABLE_FIELD = "Lpbv;->h:[F"

/**
 * Set by the engine constructor when the distance table is not strictly increasing; `g()` bails at
 * offset 27 when it is true, and the table then points at the shared static `Lmbs;->c:[F`.
 */
internal const val CONFIG_DISABLED_FIELD = "Lpbv;->g:Z"

/**
 * Gboard's preference store, and its string-keyed getters. It exposes these alongside a
 * resource-id-keyed set (`b(II)I`, `at(I)Z`), and the string forms are what let a patch read a
 * preference whose resource id will not exist until aapt2 recompiles.
 */
internal const val PREFERENCE_STORE = "Lpnp;"
internal const val PREFERENCE_STORE_GET =
    "$PREFERENCE_STORE->N(Landroid/content/Context;)$PREFERENCE_STORE"
internal const val PREFERENCE_GET_INT = "$PREFERENCE_STORE->b(Ljava/lang/String;I)I"

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

/**
 * The three-argument engine constructor. Reads the hold delay from `0x7f0c00ef` (200 ms) and
 * forwards it to the four-argument form, so this is where that value can be substituted without
 * touching the activation path.
 *
 * `InlineSuggestionScrubSpaceMotionEventHandler` calls the four-argument form directly with 50 ms,
 * so it never passes through here.
 */
object ScrubEngineConstructorFingerprint : Fingerprint(
    definingClass = SCRUB_MOTION_EVENT_HANDLER,
    name = "<init>",
    parameters = listOf("Landroid/content/Context;", "Lpbr;", "Lpbv;"),
    returnType = "V",
)
