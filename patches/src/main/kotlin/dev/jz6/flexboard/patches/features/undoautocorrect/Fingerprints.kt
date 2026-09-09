package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.Fingerprint

/**
 * Everything this feature depends on, kept in its own package.
 *
 * See `docs/undo-autocorrect.md` for how each of these was established, including the two targets
 * that looked right and were not.
 */

/**
 * The pointer delegate every [BASIC_HANDLER] owns. Not a motion event handler itself — it is the
 * object the handler hands its pointers to, and it is where `pref_enable_flick_symbols` lands.
 */
internal const val POINTER_DELEGATE = "Lpvf;"

/**
 * The base motion event handler, named only to explain the ownership. Its own `g(MotionEvent)V`
 * dispatches on actions 7, 9 and 10 — `ACTION_HOVER_*` — and never sees a finger, which is why the
 * emission is not there.
 */
internal const val BASIC_HANDLER =
    "Lcom/google/android/libraries/inputmethod/motioneventhandler/BasicMotionEventHandler;"

/** The per-pointer tracker: start and current coordinates, and the delegate that owns it. */
internal const val POINTER = "Lpvi;"
internal const val POINTER_START_X = "$POINTER->b:F"
internal const val POINTER_START_Y = "$POINTER->c:F"
internal const val POINTER_X = "$POINTER->d:F"
internal const val POINTER_Y = "$POINTER->e:F"

/**
 * The tracker's back-reference to its delegate, declared as the interface and always a
 * [POINTER_DELEGATE] in practice — Gboard casts it to exactly that in its own flick path, which is
 * the precedent the emission copies rather than inventing a route of its own.
 */
internal const val POINTER_DELEGATE_FIELD = "$POINTER->r:Lpvj;"

/** The delegate's own delegate: how anything down here raises an IME event. */
internal const val EVENT_SINK_FIELD = "$POINTER_DELEGATE->d:Lpvo;"
internal const val DISPATCH_EVENT = "Lpvo;->n(Lnur;)V"

/**
 * `ActionDef` for a direction, or null when the key defines none.
 *
 * The anchor. On a Latin key an upward flick reaches this, gets null, and falls through — no Latin
 * layout binds any slide action, checked across all 123 that mention qwerty.
 */
internal const val ACTION_DEF_LOOKUP =
    "$POINTER->j(Lpmy;)Lcom/google/android/libraries/inputmethod/metadata/ActionDef;"

/**
 * `SLIDE_UP` on Gboard's action enum, whose constants are
 * `a` PRESS, `b` LONG_PRESS, `c` SLIDE_UP, `d` SLIDE_DOWN, `e` SLIDE_LEFT, `f` SLIDE_RIGHT,
 * `g` DOUBLE_TAP, `h` DOWN, `i` UP, `j` ON_FOCUS. Preflight pins the name against the letter.
 */
internal const val SLIDE_UP = "Lpmy;->c:Lpmy;"

/**
 * Key data, and the wrapper that turns it into an event. Gboard's own revert builds exactly this.
 *
 * `Swipe to Delete` knows the same class as the start-key holder, which is what preflight calls it.
 * One class, two jobs; named here for the job this patch gives it.
 */
internal const val KEY_DATA = "Lpnu;"
internal const val KEY_DATA_CTOR = "$KEY_DATA-><init>(ILpnt;Ljava/lang/Object;I)V"
internal const val EVENT_FROM_KEY_DATA = "Lnur;->d(Lpnu;)Lnur;"

/**
 * Revert-autocorrect. Not ours: Gboard's backspace path dispatches this exact code, and it has four
 * consumers and several other producers, one of them a click handler. Dispatching it with nothing
 * to revert is a no-op — both handlers null-check their tracked state and return.
 */
internal const val REVERT_AUTOCORRECT = -10045

/** Priority the stock revert uses. Copied so the two events are indistinguishable downstream. */
internal const val EVENT_PRIORITY = 0x7fffffff

/**
 * Pointer release. `Lpvf;->i(MotionEvent)V` calls this and then clears the tracker table when the
 * masked action is `ACTION_UP`, so this runs once, at the end of a gesture — which is what lets the
 * corridor test measure a completed flick rather than a partial one.
 *
 * Static: there is no `this`, so the event sink is reached through the pointer's own delegate.
 */
internal fun pointerReleaseFingerprint() = Fingerprint(
    definingClass = POINTER_DELEGATE,
    name = "t",
    parameters = listOf(POINTER, "Landroid/view/MotionEvent;", "I"),
    returnType = "V",
)
