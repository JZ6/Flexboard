package dev.jz6.flexboard.patches.features.swipetodelete

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import dev.jz6.flexboard.patches.shared.checkMethodExists
import dev.jz6.flexboard.patches.shared.soleMethodCalling

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

/** The per-handler config. Gboard reads it this way itself, at offset 23 of `g()`. */
internal const val CONFIG_FIELD = "$SCRUB_MOTION_EVENT_HANDLER->g:Lpvs;"

/** The keycode a drag must start on, and the sentinel the patches test for. */
internal const val CONFIG_START_KEY_FIELD = "Lpvs;->a:I"

/**
 * The Context the base handler stores, set by `AbstractMotionEventHandler.<init>` and read exactly
 * this way at offset 4 of `r()`. The engine's own Context register is overwritten with `Resources`
 * early in its constructor, so this is how later code gets one.
 */
internal const val HANDLER_CONTEXT_OWNER = SCRUB_MOTION_EVENT_HANDLER
internal const val HANDLER_CONTEXT_FIELD_NAME = "o"
internal const val HANDLER_CONTEXT_FIELD =
    "$HANDLER_CONTEXT_OWNER->$HANDLER_CONTEXT_FIELD_NAME:Landroid/content/Context;"

/** Boxes the signed word count into the dispatched event, which is what identifies it. */
internal const val INTEGER_VALUE_OF = "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;"

/**
 * The distance table. `r()` counts how many of its entries `abs(delta)` has passed, and that count
 * is the number of words. **Not final**, unlike everything in `Lpvr;`, so its contents can be
 * scaled in place.
 */
internal const val CONFIG_STEP_TABLE_FIELD = "Lpvs;->h:[F"

/**
 * Set by the engine constructor when the distance table is not strictly increasing; `g()` bails at
 * offset 27 when it is true, and the table then points at the shared static `Lmvf;->c:[F`.
 */
internal const val CONFIG_DISABLED_FIELD = "Lpvs;->g:Z"

/**
 * Gboard's preference store, and its string-keyed getters. It exposes these alongside a
 * resource-id-keyed set (`b(II)I`, `at(I)Z`), and the string forms are what let a patch read a
 * preference whose resource id will not exist until aapt2 recompiles.
 */
internal const val PREFERENCE_STORE = "Lqhy;"

/**
 * **Signature-unique** on the store: no other method takes a `Context` and returns the store. So a
 * rename cannot hide behind a sibling, and [checkPreferenceStorePins] asserting it still exists is
 * enough — the letter cannot survive on the wrong member the way `AbstractIme->s` did.
 *
 * The store's `(String, Z)Z` getter used to be pinned alongside this, from when the scrub patches
 * read a boolean preference. Nothing emits it now, and an assertion guarding no emission can only
 * fail a build that would otherwise have been fine, so it is gone rather than kept "for symmetry".
 */
internal const val PREFERENCE_STORE_GET =
    "$PREFERENCE_STORE->I(Landroid/content/Context;)$PREFERENCE_STORE"

/** `Integer.parseInt`, which the string-valued getter calls and the typed int getter does not. */
private const val INTEGER_PARSE_INT = "Ljava/lang/Integer;->parseInt"

/**
 * The string-keyed getInt that **parses**.
 *
 * **Its signature is not unique.** Two methods on the store take `(String, I)` and return `I`:
 * this one, which reads the preference as a *string* and runs it through `Integer.parseInt`, and a
 * typed sibling that casts the stored value directly. The distinction is load-bearing in both
 * directions: Flexboard's native settings rows (`InlineSliderPreference` on
 * `res/xml/flexboard_settings.xml`) persist integer-as-string, which the typed getter would throw
 * a `ClassCastException` on — and an int-typed leftover (the step-scale seed) makes this one
 * throw either. Emitting the wrong candidate compiles, verifies, and crashes on the phone, so
 * the letter is derived from that difference instead of written down.
 */
internal fun BytecodePatchContext.resolvePreferenceGetParsedInt(): String =
    soleMethodCalling(
        PREFERENCE_STORE,
        "(Ljava/lang/String;I)I",
        INTEGER_PARSE_INT,
        "the store's string-keyed getInt that parses",
    )

// There was a `resolvePreferenceContains` here, for the store's id-keyed `contains`, and it needed
// resolving rather than naming because two methods take `(I)` and return `Z` — the other resolves
// the id to a key and delegates to a boolean *getter*, so confusing them turns "has the user ever
// set this?" into "is it currently true?". For flick keys, the one thing that used it, that is the
// difference between a default and something forced back on at every start.
//
// It is gone because nothing writes a Gboard preference from bytecode any more. A preference key is
// just a string resource's value, and the file is an ordinary SharedPreferences, so the extension
// does it in Java against `SharedPreferences.contains`, which has no sibling to be confused with.
// See `GboardSettings` and `shared/AppStart.kt`.

/** Asserts the store descriptors that are safe to pin are still present. */
internal fun BytecodePatchContext.checkPreferenceStorePins() {
    checkMethodExists(PREFERENCE_STORE_GET, "The preference store's singleton getter")
}

/**
 * ### Why these are functions and not `object`s
 *
 * `Fingerprint` memoises its `Match` in `_matchOrNull`, and `matchOrNull(context)` returns that
 * cache without ever checking the cached `Match` came from the context being asked. The patcher's
 * cleanup does not save you: `clearFingerprints()` calls `clearMatch()` on every registered
 * fingerprint and *then* empties the registry, while a `Fingerprint` only registers itself from its
 * constructor. A Kotlin `object` runs that constructor once per classloader, so it is registered
 * for the first run and never again — run two resolves fresh and is then never cleared, and run
 * three onwards hands back a `Match` bound to a discarded `BytecodePatchContext`. The edits land in
 * the previous run's object graph and the output APK is quietly unpatched, with every
 * `assertRegisterCount` still passing because the numbers are identical.
 *
 * That needs the host to reuse the bundle's classloader across patching sessions, which the local
 * driver never does — one session per JVM — so it cannot reproduce here. A factory sidesteps the
 * question entirely: a fresh instance has nothing cached and re-registers itself for the run it
 * belongs to. Resolve once per `execute` and share the result, rather than calling twice.
 *
 * ---
 *
 * The shared engine's entry point. Holds the single comparison that decides whether a scrub may
 * begin, for every subclass — delete, spacebar move, and inline suggestion alike.
 */
fun scrubHandleMotionEventFingerprint() = Fingerprint(
    definingClass = SCRUB_MOTION_EVENT_HANDLER,
    name = "g",
    parameters = listOf("Landroid/view/MotionEvent;"),
    returnType = "V",
)

/**
 * `ScrubDeleteMotionEventHandler` declares exactly one method. Its whole contribution is building
 * the `Lpvs;` config it hands to the shared engine, the first argument of which is the keycode the
 * drag must start on.
 */
fun scrubDeleteConstructorFingerprint() = Fingerprint(
    definingClass = SCRUB_DELETE_MOTION_EVENT_HANDLER,
    name = "<init>",
    parameters = listOf("Landroid/content/Context;", "Lpvo;"),
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
fun scrubEngineConstructorFingerprint() = Fingerprint(
    definingClass = SCRUB_MOTION_EVENT_HANDLER,
    name = "<init>",
    parameters = listOf("Landroid/content/Context;", "Lpvo;", "Lpvs;"),
    returnType = "V",
)

/**
 * Where the signed word count is computed and dispatched. Two sites produce it — the in-table
 * bucket walk and the past-the-table extrapolation — and both multiply a magnitude by the
 * direction, which is what makes them identifiable.
 */
fun scrubDispatchFingerprint() = Fingerprint(
    definingClass = SCRUB_MOTION_EVENT_HANDLER,
    name = "r",
    parameters = listOf("Landroid/view/MotionEvent;", "Z"),
    returnType = "V",
)
