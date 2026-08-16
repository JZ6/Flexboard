package dev.jz6.flexboard.patches.features.undodelete

import app.morphe.patcher.Fingerprint

/**
 * Everything this feature depends on, kept in its own package so nothing here is shared with the
 * scrub patches. The only thing borrowed from elsewhere is the generic instruction and register
 * helpers in `patches/shared/`.
 */

/** Gboard's Latin IME. Its `q` is the event dispatcher every key and gesture ends up in. */
internal const val LATIN_IME = "Lcom/google/android/apps/inputmethod/libs/latin5/LatinIme;"

/** The IME base class, which owns both the suppression flag and the re-commit used to undo. */
internal const val ABSTRACT_IME = "Lcom/google/android/libraries/inputmethod/ime/AbstractIme;"

/**
 * Set while the IME is suppressing input. The stock `SCRUB_DELETE_FINISH` handler reads it and, when
 * true, treats the event as handled and does nothing — which is the branch this patch reuses to
 * return without having to name the target instruction.
 *
 * **This was `N:Z` on 17.7.7.** Gboard 18 inserted a field into `AbstractIme`, shifting every letter
 * from `C` down by one, so the flag is now `O` — and `N` still exists as an unrelated boolean. A
 * letter carried over unchecked would therefore have assembled, verified and silently tested the
 * wrong field. It is pinned here from a read count instead: the suppression flag is the only
 * `AbstractIme` boolean read exactly four times in the dispatcher, in both builds.
 */
internal const val SUPPRESSED_FIELD = "$ABSTRACT_IME->O:Z"

/**
 * The IME's `Context`, and the only way to reach one from inside the dispatcher.
 *
 * **`this` is not a `Context`.** `LatinIme` extends `AbstractIme`, which extends `Object` — no
 * `Service` and no `ContextWrapper` anywhere in the chain. Passing `this` where a `Context` is
 * required assembles cleanly, then fails verification at run time and takes the dispatcher with it,
 * which is the whole keyboard. That shipped in `0.0.1-dev.1`.
 *
 * Only the field's *name* is pinned here. Its declaring class and type are resolved out of the dex
 * at patch time, because which class to name is not a free choice: the emitted `iget-object`
 * requires the register to be a subclass of whatever class the descriptor names, and all the patch
 * can prove about that register is that it is at least an [ABSTRACT_IME]. Gboard's own reads spell
 * it `$LATIN_IME->B`, which is correct for Gboard — the verifier knows `this` there — but is a
 * claim this patch cannot make. `AbstractIme` declares the field, so naming the declaring class
 * is both provable and stable.
 */
internal const val IME_CONTEXT_FIELD_NAME = "B"

/**
 * Gboard's undo slot: one deleted `CharSequence` and nothing more.
 *
 * The scrub delete already writes it. `SCRUB_DELETE_FINISH` calls `Lomu;->a(I)`, which performs the
 * deletion and returns the removed text, and the handler stores that text here. So the text a swipe
 * removed is sitting in this slot by the time the finger lifts, with no help from Flexboard.
 */
internal const val UNDO_SLOT = "Lqyc;"
internal const val UNDO_SLOT_FIELD = "$LATIN_IME->y:$UNDO_SLOT"
internal const val UNDO_SLOT_AVAILABLE = "$UNDO_SLOT->d()Z"
internal const val UNDO_SLOT_GET = "$UNDO_SLOT->a()Lj\$/util/Optional;"
internal const val UNDO_SLOT_CLEAR = "$UNDO_SLOT->c()V"

/**
 * How the stock undo re-commits the text, resolved out of Gboard rather than pinned.
 *
 * **Pinning the letter is what broke `0.0.3-dev.1`.** On 17.7.7 this was `AbstractIme->s(Lnpx;Z)V`;
 * on 18 the same 178-instruction method is called `t`, and `s` is a *different*, much smaller
 * method that inherited the letter with a signature-compatible shape. So the patch kept emitting a
 * call that resolved, verified and ran — to the wrong method. Undo silently did nothing while
 * everything else worked.
 *
 * Nothing static catches that: the method exists, the argument types check, and the base class
 * declarations of both are empty stubs (`return-void`) that `LatinIme` overrides. The only thing
 * that distinguishes them is *which one Gboard's own undo calls*, so that is what this reads.
 *
 * [UNDO_SLOT_GET] is called exactly once in the dispatcher — in the stock `UNDO_MULTI_DELETION`
 * handler — and a few instructions later that handler casts the `Optional`'s contents and hands
 * them to the re-commit. Matching the invoke that follows gives both the method *and* the
 * committable-text type, neither of which then needs a letter written down here.
 */
internal const val RECOMMIT_SEARCH_WINDOW = 40

/** `AbstractIme->…(L…;Z)V` — the shape of the re-commit, whatever it is called this build. */
internal val RECOMMIT_PATTERN =
    Regex("^${Regex.escape(ABSTRACT_IME)}->[A-Za-z0-9_$]+\\((L[A-Za-z0-9/$_;]+;)Z\\)V$")

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
internal const val SCRUB_STATE_TAKE_TEXT = "Lomu;->a(I)Ljava/lang/CharSequence;"

/**
 * `LatinIme.handleEvent`. 1,544 instructions and 34 registers — by a distance the largest method
 * this project injects into, which is why every register below is derived from the anchor rather
 * than assumed.
 *
 * Named `d` on 17.7.7 and `q` on 18. Nothing about the method changed but its name and register
 * allocation; the anchor region this patch keys off is instruction-for-instruction identical.
 */
internal object LatinImeHandleEventFingerprint : Fingerprint(
    definingClass = LATIN_IME,
    name = "q",
    parameters = listOf("Lnur;"),
    returnType = "Z",
)
