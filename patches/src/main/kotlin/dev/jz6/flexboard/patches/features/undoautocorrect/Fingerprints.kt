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

// --------------------------------------------------------------------------- option B

/**
 * Gboard's own "this pointer was already handled" check, and the anchor the consuming emission
 * prepends to.
 *
 * `handleActionUp` calls it before any per-direction dispatch:
 *
 * ```
 *  56: invoke-virtual {v13,v14,v1,v0,v15}, Lpvi;->G(…)Z
 *  59: move-result v2
 *  60: if-nez v2, -> 16          # handled
 * 116: …Lpvi;->u(…)              # the keypress commit, skipped
 *  16: move-object v3, v13       # the handover, written by Gboard
 *  17: goto/16 -> 256            # clean exit
 * ```
 *
 * Returning true is therefore the whole of goal 2: the commit never runs, and `v3` is set to the
 * pointer by Gboard's own instruction. `2.5.0-dev.0` and `dev.1` crashed jumping into that block
 * with `v3` holding a `Lpmy;`. Here no merge arises, because nothing jumps — the method returns and
 * Gboard branches.
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
 * Register count of [alreadyHandledFingerprint]'s method, pinned because the emission depends on it.
 *
 * Twenty, with five parameters, so `this` is v15 and v0–v14 are locals. **Every one of those locals
 * is uninitialised at pc 0**, which is the reason this emission needs no liveness analysis, no
 * scratch-register handover and no `live_free` call. The failure mode that produced two broken
 * releases is absent by construction rather than by care.
 */
internal const val ALREADY_HANDLED_REGISTER_COUNT = 20

/**
 * The direction of the **already-resolved** `ActionDef`, or null when none is resolved.
 *
 * Not the gesture direction, which is what `2.5.1-dev.0` used it as and why that build did nothing
 * at all. Its body is:
 *
 * ```
 * i():  invoke-virtual {v1}, Lpvi;->I()Z     # is an ActionDef resolved?
 *       if-eqz -> 11
 *       iget-object v1, v1, Lpvi;->n:…ActionDef;   # ← reads the resolved one
 *       …return its direction
 *   11: return null
 * ```
 *
 * `Lpvi;->n` is written at pc 250 of `handleActionUp`, in the teardown, long after `G` runs at 56.
 * So at `G`'s entry this returns null every time, and a guard comparing it to [SLIDE_UP] can never
 * be true. It is the *input* to the real computation, not the computation.
 */
internal const val POINTER_DIRECTION = "$POINTER->i()Lpmy;"

/**
 * The gesture direction, computed from where the finger started and where it ended.
 *
 * This is the one that matters. `handleActionUp` calls it at pc 72 with the current coordinates and
 * whatever [POINTER_DIRECTION] returned, and passes the result straight to [ACTION_DEF_LOOKUP]:
 *
 * ```
 *  62: invoke-virtual {v13}, Lpvi;->i()Lpmy;      # prior/resolved, usually null here
 *  68: iget v15, v13, Lpvi;->d:F                  # current x
 *  70: iget v0,  v13, Lpvi;->e:F                  # current y
 *  72: invoke-virtual {v13,v15,v0,v2}, Lpvi;->h(FFLpmy;)Lpmy;
 *  76: invoke-virtual {v13,v2}, Lpvi;->j(Lpmy;)…ActionDef;
 * ```
 *
 * Reusing it keeps the emission on Gboard's own threshold — the same reckoning that decides whether
 * a motion was a slide at all — rather than inventing a second one that could disagree.
 *
 * **Safe to call an extra time.** Its body writes no field: it reads the start coordinates, asks
 * `M()`, `Lpvj;->r()` and the `SoftKeyDef`, and returns. Calling it once at `G`'s entry and letting
 * Gboard call it again at pc 72 computes the same answer twice and changes nothing.
 */
internal const val POINTER_SLIDE_DIRECTION = "$POINTER->h(FFLpmy;)Lpmy;"

/** Where the emission jumps when the gesture is not ours: straight into stock `G`. */
internal const val STOCK_LABEL = "flexboard_not_our_flick"

/**
 * Scratch for the consuming emission. Five slots, all locals of `G` and all dead at pc 0.
 *
 * Low deliberately: a `35c` invoke encodes each register in four bits, and these are passed to
 * `Math.abs` and the key-data constructor.
 */
internal val CONSUME_SCRATCH = listOf(0, 1, 2, 3, 4)

/** Anything this project emits a call to, whatever the payload. */
internal const val EXTENSION_PACKAGE = "Ldev/jz6/flexboard/extension/"

// --------------------------------------------------------------------------- journey tracking

/**
 * `TouchActionBundle.handleActionMove` — named by its own trace string at pc 21, not inferred.
 *
 * Iterates every live pointer on every move event and writes each one's current coordinates:
 *
 * ```
 *  53-57: v1.d = getX(index)
 *  59-63: v1.e = getY(index)
 * ```
 *
 * Two pointers are skipped: one whose `findPointerIndex` is stale, and one whose `M()` is false.
 * The second costs nothing here, because `Lpvi;->h` gates on `M()` too — anything it excludes was
 * never going to produce a slide direction anyway.
 *
 * The emission goes in after the y write, where the pointer in `v1` carries both the gesture start
 * (`b`, `c`) and the current position (`d`, `e`). Having both is what removes the need for a
 * separate DOWN hook: a changed start *is* the signal that a new gesture began.
 */
internal fun pointerMoveFingerprint() = Fingerprint(
    definingClass = POINTER_DELEGATE,
    name = "h",
    parameters = listOf("Landroid/view/MotionEvent;"),
    returnType = "V",
)

/** The pointer's id, as `findPointerIndex` uses it, and the tracker's key. */
internal const val POINTER_ID = "$POINTER->a:I"

/**
 * Scratch for the move emission, all dead at the insertion point by `preflight.live_free`.
 *
 * Five, because `track` takes five arguments and a `35c` invoke holds exactly five registers. The
 * insertion is **inside the per-pointer loop**, so a register that is not actually free corrupts
 * every later pointer in the same event rather than failing where it was written — which is why
 * this came from the CFG-correct liveness helper rather than from reading the disassembly.
 */
internal val MOVE_SCRATCH = listOf(3, 4, 5, 6, 7)

