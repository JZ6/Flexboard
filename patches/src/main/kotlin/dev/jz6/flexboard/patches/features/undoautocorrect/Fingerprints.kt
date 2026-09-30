package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.Fingerprint

/*
 * Every Gboard member the key-pipeline swipe-up emission depends on.
 *
 * Several comments that used to sit here asserted things the dex contradicts, and each one cost a
 * release: `Lpvi;->i()` documented as the gesture direction, `Lpvi;->b/c` as the gesture start,
 * `Lpvi;->j` as returning null for a direction a key does not bind. What follows was re-read
 * against the dex; where a member is used for something it only approximates, that is said here.
 */

/** The pointer delegate: owns the live pointers and dispatches their events. */
internal const val POINTER_DELEGATE = "Lpvf;"

/** The per-pointer tracker. */
internal const val POINTER = "Lpvi;"

/**
 * Where the pointer entered **the key it is currently on** — not where the gesture began.
 *
 * `Lpvi;->B(SoftKeyView, F, F, J, I)V` rewrites `a` through `e` whenever the finger is retargeted to
 * another key, which for an upward slide on a letter key happens after 0.8 of a key height.
 * `Lpvi;->v` also shifts all four when the keyboard view moves. Anything measuring a gesture from
 * these measures from the last key boundary crossed.
 */
internal const val POINTER_START_X = "$POINTER->b:F"
internal const val POINTER_START_Y = "$POINTER->c:F"

/** The pointer's current position, written by the move path for every attached pointer. */
internal const val POINTER_X = "$POINTER->d:F"
internal const val POINTER_Y = "$POINTER->e:F"

/** The pointer's id, as `findPointerIndex` uses it. Preserved across a retarget by `B`. */
internal const val POINTER_ID = "$POINTER->a:I"

/**
 * The tracker's back-reference to its delegate, declared as the interface. `Lpvf;` is its only
 * implementer and Gboard casts it the same way, which is the precedent the emission copies.
 */
internal const val POINTER_DELEGATE_FIELD = "$POINTER->r:Lpvj;"

/** The delegate's event sink: how anything down here raises an IME event. */
internal const val EVENT_SINK_FIELD = "$POINTER_DELEGATE->d:Lpvo;"
internal const val DISPATCH_EVENT = "Lpvo;->n(Lnur;)V"

/**
 * The `ActionDef` the pointer's current key binds for a direction.
 *
 * **Falls back to the key's PRESS action when the key binds nothing for that direction.** The lookup
 * behind it searches for an exact match and, finding none, searches again for PRESS. So on a letter
 * key it is never null for SLIDE_UP, and a check written as "skip if this key owns an upward
 * action" is always true — which is why the real patch has never fired. `SoftKeyDef.n(Lpmy;)` is the
 * exact-match question; it is what Gboard's own `ae()` uses.
 */
internal const val ACTION_DEF_LOOKUP =
    "$POINTER->j(Lpmy;)Lcom/google/android/libraries/inputmethod/metadata/ActionDef;"

/**
 * `SLIDE_UP` on Gboard's action enum, whose constants are
 * `a` PRESS, `b` LONG_PRESS, `c` SLIDE_UP, `d` SLIDE_DOWN, `e` SLIDE_LEFT, `f` SLIDE_RIGHT,
 * `g` DOUBLE_TAP, `h` DOWN, `i` UP, `j` ON_FOCUS. Preflight pins the name against the letter.
 */
internal const val SLIDE_UP = "Lpmy;->c:Lpmy;"

/** Key data, and the wrapper that turns it into an event. Gboard's own revert builds exactly this. */
internal const val KEY_DATA = "Lpnu;"
internal const val KEY_DATA_CTOR = "$KEY_DATA-><init>(ILpnt;Ljava/lang/Object;I)V"
internal const val EVENT_FROM_KEY_DATA = "Lnur;->d(Lpnu;)Lnur;"

/**
 * Gboard's UNDO keycode — the one Ctrl+Z sends, named "UNDO" in Gboard's own keycode table and
 * routed to its undo extension. Backspace-after-autocorrect uses it too, which is how it was found.
 *
 * With an autocorrection pending it reverts that; with none, it undoes the last edit. That second
 * behaviour was accepted as the design on 2026-09-30, so a swipe up is "undo" rather than strictly
 * "revert the last autocorrection".
 */
internal const val UNDO_KEYCODE = -10045

/** Priority the stock revert uses. */
internal const val EVENT_PRIORITY = 0x7fffffff

/**
 * `Lpvi;->G`: commits the long-press popup's choice when a popup is showing and returns true;
 * otherwise returns false.
 *
 * `handleActionUp` asks it before any per-direction dispatch and exits early when it returns true:
 *
 * ```
 *  56: invoke-virtual {v13,v14,v1,v0,v15}, Lpvi;->G(…)Z
 *  60: if-nez v2, -> 16          # true: skip everything below
 * 116: …Lpvi;->u(…)              # the keypress commit
 *  16: move-object v3, v13 ; goto/16 -> 256
 * ```
 *
 * So returning true skips the commit without any branch of ours — confirmed on a device. Two limits:
 * it is only reached while the finger is attached to a key (`handleActionUp` exits at pc 54 when
 * `Lpvi;->m()` is null, and a finger that slid off the top row has been detached), and it has a
 * second caller in `BasicMotionEventHandler.g` for TalkBack's hover exit.
 */
internal fun alreadyHandledFingerprint() = Fingerprint(
    definingClass = POINTER,
    name = "G",
    parameters = listOf(
        "Landroid/view/MotionEvent;",
        "Lcom/google/android/libraries/inputmethod/metadata/SoftKeyDef;",
        "I",
        "I",
    ),
    returnType = "Z",
)

/**
 * `G`'s frame: twenty registers, five of them parameters, so `this` is v15 and every local is
 * unwritten at pc 0. Pinned in preflight.
 */
internal const val ALREADY_HANDLED_REGISTER_COUNT = 20

/** Where the emission jumps when the gesture is not ours: straight into stock `G`. */
internal const val STOCK_LABEL = "flexboard_not_our_flick"

/** Scratch for the `G` emission: five locals, all unwritten at pc 0, all nibble-addressable. */
internal val CONSUME_SCRATCH = listOf(0, 1, 2, 3, 4)

/** Anything this project emits a call to, whatever the payload. */
internal const val EXTENSION_PACKAGE = "Ldev/jz6/flexboard/extension/"

/**
 * `TouchActionBundle.handleActionMove`, named by its own trace string at pc 21.
 *
 * Writes each live pointer's current position once per move event, skipping a pointer whose index
 * is stale or whose `M()` is false. `M()` is false once a pointer has detached from its key, so a
 * flick that leaves the top of the keyboard stops being written here the moment it leaves.
 */
internal fun pointerMoveFingerprint() = Fingerprint(
    definingClass = POINTER_DELEGATE,
    name = "h",
    parameters = listOf("Landroid/view/MotionEvent;"),
    returnType = "V",
)

/**
 * Scratch for the move emission, all dead at the insertion point by `preflight.live_free`. Five,
 * because the tracker takes five arguments and a `35c` invoke holds exactly five registers.
 */
internal val MOVE_SCRATCH = listOf(3, 4, 5, 6, 7)
