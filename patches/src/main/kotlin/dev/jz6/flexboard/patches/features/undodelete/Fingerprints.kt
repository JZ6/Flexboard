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

/*
 * The suppression flag — set while the IME is suppressing input — is deliberately **not** a constant
 * here. The stock `SCRUB_DELETE_FINISH` handler reads it and, when true, treats the event as handled
 * and does nothing, which is the branch this patch reuses to return without naming a target
 * instruction. `undoOnRightwardScrub` finds that read by the shape around it —
 * `move-result` / `iget-boolean` / `if-nez` — and takes the field descriptor from whatever is there.
 *
 * Writing the letter down is what this avoids. It was `AbstractIme->N:Z` on 17.7.7; Gboard 18
 * inserted a field and shifted every letter from `C` down one, so it is `O` on 18 — while `N` went
 * on existing as an unrelated boolean. A carried-over letter would have assembled, verified, and
 * silently tested the wrong field.
 */

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
 *
 * **Nothing about it is pinned here**, and the absence is the point. On 18.0.3 the slot is `Lqyc;`,
 * the field is `LatinIme->y`, and the three methods are `d()Z`, `a()Lj$/util/Optional;` and `c()V`
 * — but `d` shares `()Z` with two siblings and `c` shares `()V` with eight, so writing those letters
 * down buys a name that can move onto the wrong member without anything noticing. `resolveStockUndo`
 * reads all of them out of the handler that performs Gboard's own undo instead. These values are
 * recorded in this comment only so the next person has something to compare against.
 */

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

/**
 * `AbstractIme->…(L…;Z)V` — the shape of the re-commit, whatever it is called this build.
 *
 * Every `$` is written `\$`. Dex descriptors contain them (`Lj\$/util/Optional;` after desugaring),
 * and an unescaped one that happens to precede an identifier character is read by Kotlin as a
 * string template rather than a literal — which is what broke the `0.0.3-dev.2` build.
 */
private const val DESCRIPTOR_CHARS = "A-Za-z0-9/_\$;"
internal val RECOMMIT_PATTERN = Regex(
    "^" + Regex.escape(ABSTRACT_IME) + "->[A-Za-z0-9_\$]+" +
        "\\((L[$DESCRIPTOR_CHARS]+;)Z\\)V\$",
)

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
