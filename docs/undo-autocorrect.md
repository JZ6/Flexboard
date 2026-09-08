# Undoing an autocorrect, and the gesture layer underneath it

Research for a swipe-up-to-undo-autocorrect patch, on 18.0.3. Nothing here is implemented yet. The
gesture-layer half is general and applies to any future gesture, not just this one.

## Gboard's own revert, and the action code

Gboard ships **backspace-to-undo-autocorrect** as a toggle, `pref_key_latin_enable_ac_revert`. The
chain is short and worth knowing because the patch reuses its second half:

| Step | Where |
|---|---|
| Preference read at start-input into `->c:Z` | `EditTrackingImeWrapper->fq(EditorInfo;ZLppa;)V` |
| Arms `->d:Z` when the pref is on *and* the last edit was a user-history update of kind 3 | `Lfyh;->g(IILjava/lang/CharSequence;)Z` |
| On keycode **67** with `->d` armed: build an event, dispatch, disarm, consume | `EditTrackingImeWrapper->q(Lnur;)Z` |

The dispatch is four instructions:

```smali
new-instance   v0, Lpnu;
const/16       v3, #-10045
const          v4, #0x7fffffff
invoke-direct  {v0, v3, v5, v5, v4}, Lpnu;-><init>(ILpnt;Ljava/lang/Object;I)V   # v5 = null
invoke-static  {v0}, Lnur;->d(Lpnu;)Lnur;
invoke-virtual {v7, v0}, Lokm;->h(Lnur;)V
```

**`-10045` is the revert-autocorrect action code.** It is not ours and not invented: it has four
consumers (`Lfbl;`, `Lhuw;`, `Lrjv;`, `Lhkr;`, all `m(Lnur;)Z`) and several producers besides
backspace, including a click handler (`Lkbv;->onClick`) and two `Runnable`s. Firing it from a
gesture is the same shape as firing it from a tap.

`LatinIme->q`'s `packed-switch` does **not** carry `-10045` — the event travels a different dispatch
path, via `Lokm;->h`.

## Dispatching it cold is safe

The main safety question was what happens on a swipe when there is nothing to revert, since a
gesture bypasses the `->d` arming that gates backspace. Answered by reading the consumers:

```
Lhuw;->m   if Lrja;->f            → return early
           payload null (it is)   → Lrja;->t(int)
Lrja;->t   iget Lrjq;->d:Integer ; if-eqz → 114
     114   if-nez v3 → 117 ; return-void
```

`Lrja;->s(I)` has the same shape, bailing to 147 and `return-void`. Two independent guards, both
ending in a clean return. **An unarmed `-10045` is a no-op.**

## It does not need Gboard's toggle

No consumer reads `EditTrackingImeWrapper->c` or `->d`. The preference gates *reinterpreting keycode
67* and nothing further along. A patch that dispatches `-10045` directly therefore works with the
toggle off, which makes the feature independent rather than a re-skin of Gboard's.

Turning `pref_key_latin_enable_ac_revert` on by default is still independently worthwhile — it is an
ordinary Gboard preference of the kind Suggested Settings already seeds — but it is not a
prerequisite and the two should be decided separately.

## The gesture layer

### Direction is already computed, generically

`Lpvi;->h(FFLpmy;)Lpmy;` turns a pointer delta into a direction constant:

```
v5 = x - Lpvi;->b      (deltaX)          v6 = y - Lpvi;->c      (deltaY)
v0 = threshold, selected by the key's Lppr; ordinal from Lpvf;->e/f/g/h/i:I

abs(dy) > abs(dx) ?
     dy >  threshold  →  Lpmy;->d   SLIDE_DOWN
     dy < -threshold  →  Lpmy;->c   SLIDE_UP
   else
     dx >  threshold  →  Lpmy;->f   SLIDE_RIGHT
     dx < -threshold  →  Lpmy;->e   SLIDE_LEFT
```

`Lpmy;` is the action enum: `a` PRESS, `b` LONG_PRESS, `c` SLIDE_UP, `d` SLIDE_DOWN, `e` SLIDE_LEFT,
`f` SLIDE_RIGHT, `g` DOUBLE_TAP, `h` DOWN, `i` UP, `j` ON_FOCUS. `Lpvi;->K(Lpmy;)Z` answers "is this
one of the four slides".

**SLIDE_UP is already produced for Latin keys.** Nothing needs to detect an upward flick; the
detection exists, is threshold-driven, and runs today. What is missing is anything to do with it.

### The call site

`BasicMotionEventHandler->g(Landroid/view/MotionEvent;)V`, around pc 203:

```
193  Lpvi;->i()                     → the default action
203  Lpvi;->h(x, y, default)        → v4 = direction          ← detection
207  Lpvi;->K(v4)                   → is it a slide?
211  if-eqz → 275                   → not a slide, skip
213  Lpvi;->j(v4)                   → v10 = ActionDef for that direction
217  if-eqz v10 → 232               → no action defined; continues with a null ActionDef
```

On a Latin key an upward flick reaches 213, finds no `ActionDef`, and falls through. That
fall-through is the seam.

### Latin keys define no slide actions at all

Checked against the APK: of **123 layouts mentioning qwerty, zero bind `SLIDE_UP` — and zero bind
`SLIDE_DOWN`**. `SLIDE_UP` appears in 21 layouts, all Japanese (godan, 12-key hiragana/alphabet).

So flick-for-symbols is **not** a per-key `SLIDE_DOWN` action. It is implemented in
`Lpvi;->n(ActionDef;ZZZJI)V`, gated on:

```
ActionDef->c == Lpmy;->d (SLIDE_DOWN)
Lpvf;->m                     (pref_enable_flick_symbols, 0x7f140a01)
Lpvj;->s()  false
SoftKeyDef->n(Lpmy;->c)  ==  0      ← the key must NOT define a SLIDE_UP action
```

That last condition is worth noting: Gboard already treats "this key has a SLIDE_UP" as a reason to
stand down. Anything that gives Latin keys a real SLIDE_UP action would disable flick-for-symbols on
them.

### Which handlers are attached, and when

Read out of the layouts with `tools/apk/axml.py`, ids resolved with `tools/apk/arsc.py`:

| Handler | Gate | Attached |
|---|---|---|
| `LatinMotionEventHandler` | none | always |
| `LatinPreemptiveDecodeHandler` | none | always |
| `InlineSuggestionScrubSpaceMotionEventHandler` | none | always |
| `LatinKeyboardLayoutHandler` | none | always |
| `LatinGestureMotionEventHandler` | `enable_gesture_input` | glide only |
| `KoreanGestureMotionEventHandler`, `Pinyin*`, `Zhuyin*`, `HindiDynamic*` | `enable_gesture_input` | glide only |
| `ScrubDeleteMotionEventHandler` | `enable_scrub_delete` | scrub only |
| `ScrubMoveMotionEventHandler` | `0x7f140a20` | — |

Resolved values: `0x7f140a05` = `enable_gesture_input`, `0x7f140a1f` = `enable_scrub_delete`,
`0x7f140a01` = `pref_enable_flick_symbols`.

Two consequences:

**Every `*Gesture*` handler is glide-gated.** Flexboard forces glide off for as long as Swipe to
Delete is applied (`glide-detection.md`), so on a default install `LatinGestureMotionEventHandler`
is not attached at all. Hooking it would reach nobody.

**Flick-for-symbols is not collateral damage from that.** It was worth checking, because
`pref_enable_flick_symbols` is read inside the glide-gated handler. But the flag lands in
`Lpvf;->m`, and `Lpvf;` is constructed by `BasicMotionEventHandler`, which carries no
`preference_key`. Flick survives glide being off.

`LatinMotionEventHandler` overrides `g(MotionEvent)V` from `BasicMotionEventHandler`, is ungated,
and is **first** in the handler list — ahead of `ScrubDeleteMotionEventHandler`.

## Routes considered and rejected

| Route | Why not |
|---|---|
| Bind a `SLIDE_UP` action on Latin keys, declaratively | Latin layouts bind no slide actions, and attaching to them means addressing a layout whose resource name is collapsed (`motion-event-handlers.md`). It would also switch off flick-for-symbols on every key it touched, per the `SoftKeyDef->n(SLIDE_UP)` condition above. |
| Hook `LatinGestureMotionEventHandler` | Glide-gated; absent exactly for the users who have our other gestures. |
| Hook `ScrubMotionEventHandler` | It is the most delicate thing the bundle patches, and `trackAcrossFullKeyboard` has already widened its vertical corridor. |
| Write our own direction detection | `Lpvi;->h` already does it, threshold-driven and per-key-class. |

## Open risks

**Scrub contention.** `trackAcrossFullKeyboard` sets `rect.top = 0; rect.bottom = getHeight()`
(`ScrubEmitter.kt:361-367`) so vertical movement no longer cancels a scrub. A straight-up swipe
keeps a scrub alive with near-zero horizontal delta — harmless in itself, since zero words are
deleted, but the two gestures share one pointer stream. That is the same collision that forced glide
typing off, and it is the likeliest way this feature ends up conflicting with Swipe to Delete.
Handler ordering helps — `LatinMotionEventHandler` runs first — but ordering has not been confirmed
to mean "first to consume".

**Threshold suitability.** The up-flick threshold comes from `Lpvf;->e/f/g/h/i:I`, chosen by the
key's `Lppr;` class. Those values were tuned for down-flick symbol entry. Whether they make an
upward undo feel deliberate rather than accidental is a device question.

**Accidental triggers.** Unlike backspace-undo, a gesture fires with no arming. The dispatch is a
no-op when there is nothing to revert, so the cost of a false positive is zero — but a false
*negative* (a flick read as a keypress) types a character.
