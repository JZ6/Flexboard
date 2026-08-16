package dev.jz6.flexboard.patches.features.undodelete

import app.morphe.patcher.Fingerprint

/**
 * Everything this feature depends on, kept in its own package so nothing here is shared with the
 * scrub patches. The only thing borrowed from elsewhere is the generic instruction and register
 * helpers in `patches/shared/`.
 */

/** Gboard's Latin IME. Its `d` is the event dispatcher every key and gesture ends up in. */
internal const val LATIN_IME = "Lcom/google/android/apps/inputmethod/libs/latin5/LatinIme;"

/** The IME base class, which owns both the suppression flag and the re-commit used to undo. */
internal const val ABSTRACT_IME = "Lcom/google/android/libraries/inputmethod/ime/AbstractIme;"

/**
 * Set while the IME is suppressing input. The stock `SCRUB_DELETE_FINISH` handler reads it and, when
 * true, treats the event as handled and does nothing — which is the branch this patch reuses to
 * return without having to name the target instruction.
 */
internal const val SUPPRESSED_FIELD = "$ABSTRACT_IME->N:Z"

/**
 * The IME's `Context`, and the only way to reach one from inside the dispatcher.
 *
 * **`this` is not a `Context`.** `LatinIme` extends `AbstractIme`, which extends `Object` — no
 * `Service` and no `ContextWrapper` anywhere in the chain. Passing `this` where a `Context` is
 * required assembles cleanly, then fails verification at run time and takes `d` with it, which is
 * the whole keyboard. That shipped in `0.0.1-dev.1`.
 *
 * Gboard reads the field this way itself, inside the same method, so the descriptor below is the
 * stock instruction rather than a guess.
 */
internal const val IME_CONTEXT_FIELD = "$LATIN_IME->B:Landroid/content/Context;"

/**
 * Gboard's undo slot: one deleted `CharSequence` and nothing more.
 *
 * The scrub delete already writes it. `SCRUB_DELETE_FINISH` calls `Lnsz;->a(I)`, which performs the
 * deletion and returns the removed text, and the handler stores that text here. So the text a swipe
 * removed is sitting in this slot by the time the finger lifts, with no help from Flexboard.
 */
internal const val UNDO_SLOT = "Lqcy;"
internal const val UNDO_SLOT_FIELD = "$LATIN_IME->y:$UNDO_SLOT"
internal const val UNDO_SLOT_AVAILABLE = "$UNDO_SLOT->d()Z"
internal const val UNDO_SLOT_GET = "$UNDO_SLOT->a()Lj\$/util/Optional;"
internal const val UNDO_SLOT_CLEAR = "$UNDO_SLOT->c()V"

/**
 * What the slot hands back. `Lqcy;->a()` builds one through `Lnpu;` and wraps it in an `Optional`,
 * so the cast below is the one the stock `UNDO_MULTI_DELETION` handler makes too.
 */
internal const val COMMITTABLE_TEXT = "Lnpx;"

/** The type name already carries its own `;`, so the parameter list interpolates it bare. */
internal const val RECOMMIT = "$ABSTRACT_IME->s(${COMMITTABLE_TEXT}Z)V"

/** Desugared, so the `$` is part of the type name rather than an inner-class separator. */
internal const val OPTIONAL = "Lj\$/util/Optional;"
internal const val OPTIONAL_IS_PRESENT = "$OPTIONAL->isPresent()Z"
internal const val OPTIONAL_GET = "$OPTIONAL->get()Ljava/lang/Object;"

/**
 * The scrub-delete state holder. Its `a(I)` is called exactly once in the whole of
 * `LatinIme->d`, which is what makes it a usable anchor for a handler that is otherwise reachable
 * only through a `packed-switch` — and switch keys never appear in the instruction stream. See the
 * note in `tools/apk/README.md`.
 */
internal const val SCRUB_STATE_TAKE_TEXT = "Lnsz;->a(I)Ljava/lang/CharSequence;"

/**
 * `LatinIme.handleEvent`. 1,608 instructions and 36 registers — by a distance the largest method
 * this project injects into, which is why every register below is derived from the anchor rather
 * than assumed.
 */
internal object LatinImeHandleEventFingerprint : Fingerprint(
    definingClass = LATIN_IME,
    name = "d",
    parameters = listOf("Lnbj;"),
    returnType = "Z",
)
