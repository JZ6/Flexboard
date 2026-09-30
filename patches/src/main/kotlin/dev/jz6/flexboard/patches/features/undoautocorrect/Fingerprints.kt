package dev.jz6.flexboard.patches.features.undoautocorrect

/*
 * The Gboard members the swipe-up undo builds its event from.
 *
 * The members it takes the gesture over with are private to SwipeUpUndoEmitter.kt, beside the
 * emission that spells them. Everything the old key-pipeline design needed — the pointer tracker's
 * fields, `Lpvi;->G`, the move path — went with that design, which could never fire.
 */

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
