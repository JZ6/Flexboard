# Gboard's motion event handlers

> **Written against Gboard 17.7.7.** The bundle now targets `18.0.3.954559732-release-arm64-v8a`,
> and the disassembly listings below — obfuscated names *and* instruction offsets — are as they
> stood on 17.7.7. Every mechanism Flexboard actually depends on was re-checked against 18 and
> still holds, which is what the patches assert at patch time; the surrounding listings were not
> re-derived, because rewriting them would mean asserting a line-by-line correspondence nobody
> verified. Read them for the reasoning, and take current names from
> [`gboard-bindings.md`](gboard-bindings.md) and offsets from the APK.

Flexboard implements its gesture from scratch: pointer hooks on `Lpbl;`, a dispatch veto on
`Lpbj;`, its own distance/drift/duration thresholds and its own settings rows. Gboard has its own
mechanism for exactly this kind of gesture, and already ships a word-scrub delete built on it.

This is the record of what that mechanism is. It is a lead, not a plan — the parts that are
verified and the parts that are guesswork are marked as such throughout.

## Handlers are attached declaratively, gated on a preference

Keyboard layouts are binary XML under `res/`. `res/aDh.xml` is the Latin one, and it ends with a
list of motion event handlers:

```xml
<view override="motion_event_handler" type="body">
  <motion_event_handler class=".libs.latin5.handler.LatinMotionEventHandler"/>
  <motion_event_handler class=".libs.latin5.handler.LatinPreemptiveDecodeHandler"/>
  <motion_event_handler class=".libs.latin5.handler.HindiDynamicKeyboardGestureMotionEventHandler"
                        preference_key="@0x7f14097b" reverse_preference="false"/>
  <motion_event_handler class=".motioneventhandler.scrubmove.ScrubDeleteMotionEventHandler"
                        preference_key="@0x7f140995" reverse_preference="false"/>
  <motion_event_handler class=".inlinesuggestion.InlineSuggestionScrubSpaceMotionEventHandler"/>
  <include href="@0x7f170e54"/>
  <motion_event_handler class=".libs.latin5.handler.LatinKeyboardLayoutHandler"/>
</view>
```

A handler is attached only when its `preference_key` resolves true, or false when
`reverse_preference="true"`. Handlers without a `preference_key` are always attached.

Note `@0x7f14097b` on the Hindi gesture handler — that is `enable_gesture_input`, the same
preference Flexboard writes. See [`glide-detection.md`](glide-detection.md). `@0x7f140995` on the
scrub delete handler is **`enable_scrub_delete`**; both were resolved with
[`../tools/apk/arsc.py`](../tools/apk/README.md).

## Scrub delete is Gboard's own word-delete

`ScrubDeleteMotionEventHandler` is the swipe-on-backspace word delete — the thing the README
means when it says Gboard's only word-delete is a swipe on the backspace key. It declares
**one method**, a constructor. All the behaviour lives in a shared `ScrubMotionEventHandler`.

Its sibling `ScrubMoveMotionEventHandler` is the drag-the-spacebar cursor move. The two
constructors are identical except for the values they pass:

```
ScrubDeleteMotionEventHandler.<init>          ScrubMoveMotionEventHandler.<init>
  const/16 v1, #67                              const/16 v1, #62
  const/4  v2, #1                               const/4  v2, #0
  const/4  v3, #2                               const/4  v3, #1
  const/16 v4, #-10050                          const/16 v4, #-10061
  const/16 v5, #-10051                          const/16 v5, #-10053
  const/16 v6, #-10052                          const/16 v6, #-10054
  const/16 v7, #-10063                          const/16 v7, #-10062
  const    v8, #0x7f0300b5                      const    v8, #0x7f0300b6
  invoke-direct/range {v0 .. v8}, Lpbv;-><init>(IZIIIIII)V
  invoke-direct {…}, ScrubMotionEventHandler;-><init>(Landroid/content/Context;Lpbr;Lpbv;)V
```

| `Lpbv;` argument | field | ScrubDelete | ScrubMove | reading |
|---|---|---|---|---|
| 1 | `a:I` | **67** | **62** | `KeyEvent.KEYCODE_DEL` / `KEYCODE_SPACE` — the start-key gate |
| 2 | `b:Z`? | `true` | `false` | not read by `g()` or `r()`; still unidentified |
| 3 | `j:I` | 2 | 1 | threshold selector: `1` picks `Lpbu;->d:F`, anything else `Lpbu;->e:F` |
| 4 | `c:I` | −10050 | −10061 | event code dispatched on the activating move |
| 5–6 | `d:I`, `e:I` | −10051, −10052 | −10053, −10054 | event codes chosen by `t(MotionEvent)Z` |
| 7 | `f:I` | −10063 | −10062 | event code dispatched when the finger leaves the rect |
| 8 | → `h:[F` | `0x7f0300b5` | `0x7f0300b6` | `attr` reference; resolves to the array of distance steps |

**The scope of the whole feature is one integer: which key the drag must start on.** Everything
else — thresholds, direction handling, progressive delete — is generic engine code.

There is already a **third** subclass, `InlineSuggestionScrubSpaceMotionEventHandler`, with the
same `<init>(Landroid/content/Context;Lpbr;)V` and the same frame (`registers=12, ins=3, outs=9`).
So the engine is not a two-off; taking a fourth config is its normal mode of use.

## Where the keycode gate is

`g(Landroid/view/MotionEvent;)V`, and it is a **single comparison**. On `ACTION_DOWN` the handler
resolves the view under the finger, requires it to be a `SoftKeyView`, requires it to carry an
`Loth;->a` action and *not* an `Loth;->e` one, then:

```
104: invoke-virtual  {v6}, Lotk;->b()Loud;
108: iget            v5, v5, Loud;->c:I     # keycode of the key under the finger
110: iget            v6, v1, Lpbv;->a:I     # the configured keycode
112: if-ne           v5, v6, -> 60          # mismatch: f = false, gesture never starts
```

Everything downstream of offset 114 is key-agnostic. In particular the tracking rect built at
118–145 is set to the **full keyboard width**:

```
123: iput  v4, v6, Landroid/graphics/Rect;->left:I         # 0
125: invoke-virtual {v5}, SoftKeyboardView;->getWidth()I
129: iput  v7, v6, Landroid/graphics/Rect;->right:I
```

with only `top`/`bottom` inset by `Lpbu;->g:F`. So the engine already tracks across the whole
keyboard once a gesture starts — the key restriction applies solely to where it *begins*.

## The engine is bidirectional by construction

`r(Landroid/view/MotionEvent;Z)V` computes a **signed** step count, so the restore half is not a
separate feature — it is the same code path with the opposite sign:

```
 64: v0 = getX(pointer) - this.k        # delta from the start X
 81: cmpl-float                         # direction = +1 if delta > 0 else -1
 88: Math.abs(delta)
 92: walk Lpbv;->h:[F                   # which distance bucket the delta falls into
118: v3 = direction * bucket            # signed step count
154: Integer.valueOf(v3)                # dispatched as the event payload
168: this.r = v3                        # only re-dispatched when the count changes
```

What a negative versus positive count *means* is the downstream processor's business, not the
engine's. Dragging back reduces the count and emits it again, which is the restore behaviour.

Two further constraints found in `r()`:

- **Apps can opt out.** `Lmvr;->w(packageName, "noScrubbing", EditorInfo)Z` is checked first; when
  an editor sets that private option the gesture is refused and a toast (`0x7f1411d8`) is shown
  once. Some text fields will therefore never scrub, through no fault of the patch.
- **Leaving the rect ends it.** `Rect.contains` failing dispatches `Lpbv;->f:I` and clears `f`.

## Who consumes the events

`q(Loud;J)V` wraps the payload in an `Lnbj;` event — action `Loth;->a`, `w = 6` — and hands it to
`Lpbr;->n(Lnbj;)V`. From there the two halves of the engine diverge:

| Codes | Consumer |
|---|---|
| −10061, −10053, −10054, −10062 (**move**) | `…/ime/processor/ScrubMoveProcessor;->af(Lnbj;)Z` and `dT(Lnsx;)Z`, driving `Lnta;` |
| −10050, −10051, −10052, −10063 (**delete**) | `…/libs/latin5/LatinIme;->d(Lnbj;)Z`, with `aq(Lnbj;)Z` as a pre-filter |

Found by scanning every method's instruction bytes for the `const/16` encodings of each code, with
the move codes as a control — the control landed exactly on `ScrubMoveProcessor` and
`ScrubMoveMotionEventHandler.<init>`, which is what makes the delete result trustworthy.

**That scan has a blind spot, and the table above is therefore a lower bound.** A `packed-switch`
stores only `first_key` and a target array — the individual keys it matches are never present in
the instruction stream, so any consumer that dispatches scrub codes through a switch is invisible
to a byte search for their encodings. `PasswordIme` is exactly such a consumer and was missed. To
find switch-based consumers, decode the payload: `ident` `0x0100`, `size` (u16), `first_key` (i32),
then `size` branch targets, and test whether a code falls in `first_key .. first_key + size - 1`.

Scrub delete has no processor of its own; `LatinIme` handles it directly, which is unsurprising
since deleting words needs the input connection. `La;->W(Lnbj;)I` is what unpacks the signed count
from the event. `LatinIme;->d(Lnbj;)Z` is 3254 code units and has not been read in full; the
branch all scrub codes share (offset 514) is generic housekeeping that cancels the Delight5
decoder's in-flight async decode.

### Why the granularity question is already answered

The gate at `g()` offset 112 sits **upstream of all of this**. It decides only whether a gesture
starts. Once started, the tracking rect, the signed count, the event codes, the dispatch and the
consumer are byte-for-byte the ones today's backspace scrub uses — nothing downstream reads which
key the finger began on. So a widened gate cannot change what a scrub *does*; it produces exactly
Gboard's existing word-scrub delete, merely startable from anywhere on the keyboard.

That is a structural argument rather than an exhaustive read of `LatinIme;->d`, and it is stated
that way deliberately.

## The tunables, and why the stock gesture feels like a hold

`Lpbu;` is the shared tuning struct, built in
`ScrubMotionEventHandler.<init>(Context;Lpbr;Lpbv;J)V` from resources. Its constructor is
`(JJFFFJF)V` and the arguments land in field order, so each one is identifiable:

| Field | Resource | Value | Role |
|---|---|---|---|
| `a:J` | `0x7f0c00ee` | 150 ms | refuses a scrub starting within 150 ms of `Lpbr;->c()`; see below |
| `b:J` | `0x7f0c00ef` | **200 ms** | **hold delay before activation is even considered** |
| `c:F` | `0x7f07090f` | 8pt | activation distance, read via `c()F` in `o(IFF)Z` |
| `d:F` | `0x7f070910` | 16pt | per-step distance when `Lpbv;->j` is 1 (scrub move) |
| `e:F` | `0x7f07090e` | 8pt | per-step distance otherwise (scrub delete) |
| `f:J` | `0x7f0c00ed` | 1000 ms | delay before the `noScrubbing` toast |
| `g:F` | `0x7f07090d` | 4mm | vertical inset applied to the tracking rect |

`b:J` is the one that shapes the feel. `p(Landroid/view/MotionEvent;I)Z` opens with it
(`regs=10, ins=3`, so v7 is `this`, v8 the event, v9 the pointer id):

```
 0: iget-object v0, v7, ScrubMotionEventHandler->c:Lpbu;
 2: invoke-virtual {v8}, MotionEvent;->getEventTime()J
 5: move-result-wide v1
 6: iget-wide v3, v7, ScrubMotionEventHandler->l:J    # the down time
 8: iget-wide v5, v0, Lpbu;->b:J                      # 200 ms
10: add-long/2addr v3, v5
11: cmp-long v0, v1, v3
13: const/4 v1, 0x0
14: if-gez v0, -> 17
16: return v1                                         # too soon, whatever the distance
17: …                                                 # findPointerIndex, then the distance tests
```

so no amount of movement activates the gesture inside the first 200 ms. That is what makes the
stock gesture a press-and-drag rather than a flick, and it is why a Fleksy-style flick — over in
well under 200 ms — was being discarded before the distance test ever ran.

**Do not branch past the gate to offset 17.** It is the obvious edit and it bricks the keyboard.
`const/4 v1, 0x0` at 13 is the only write to v1 in the method, and v1 is read four times after 17 —
as the early `return` value at 26, and as the history-loop counter at 31, 33 and 49. A branch from
before 13 to 17 leaves v1 undefined on one incoming edge; ART's verifier merges the edges, finds
the conflict, and rejects **the entire class**, so every consumer of `ScrubMotionEventHandler` —
including the spacebar cursor drag and the inline-suggestion scrub — throws `VerifyError` as soon
as the keyboard builds its handlers. Gboard crashes on open, not just the patched gesture.

This shipped as v0.1.0-dev.1 and was caught on device. Nothing in a patch-time assertion catches it:
the bytecode is well-formed and the patch applies cleanly. The working edit zeroes v5v6 between
offsets 8 and 10 instead, leaving control flow converged so the gate itself still runs.

The general rule, and it applies to any future edit here: **an inserted branch must land somewhere
every register the target reads is already defined on both edges.** Jumping forward over a
register initialisation is a verifier error, not a logic bug, and it fails at class load rather
than at the moment the skipped value would have been used.

The delay is **per handler**, not global: the 3-argument constructor supplies it from
`0x7f0c00ef` (200), while `InlineSuggestionScrubSpaceMotionEventHandler` calls the 4-argument
form with `0x7f0c006f` (50). Gboard already ships two different values, so changing it on one path
follows the engine's design rather than fighting it.

`a:J` is a **different kind of gate and is deliberately left alone.** It sits at offset 53 of `g()`,
on the `ACTION_DOWN` path, and compares the down time against `Lpbr;->c()` plus 150 ms:

```
43: invoke-virtual {v12}, MotionEvent;->getEventTime()J
47: invoke-interface {v0}, Lpbr;->c()J
53: iget-wide v9, v0, Lpbu;->a:J
55: add-long/2addr v7, v9
56: cmp-long v2, v5, v7
58: if-gez v2, -> 64          # far enough after the reference time: carry on
60: iput-boolean v4, v11, ScrubMotionEventHandler->f:Z   # otherwise refuse outright
```

Because it is evaluated once at `ACTION_DOWN` against a *past* reference time, it adds no latency to
the gesture in progress — it only declines to start one too soon after whatever `Lpbr;->c()` marks,
which appears to be the last committed keystroke. That makes it an anti-misfire measure, not a hold,
and it is why the "you have to hold the swipe" symptom traced to `b:J` alone.

Also worth knowing: `g()` bails at offset 27 when `Lpbv;->g:Z` is set, before any of this. That flag
is false for all three stock configs.

## Making the tunables configurable

**Every field of `Lpbu;` is `public final`.** Gboard writes them only inside `Lpbu;-><init>`, so a
patch cannot `iput` them — ART rejects a final-field write from outside the declaring class. The way
to change one is to substitute the **constructor argument** that produces it, and the four-argument
engine constructor makes each argument easy to identify:

```
25: const v1, 0x7f0c00ee   → getInteger        → int-to-long v1  → a:J
33: const v3, 0x7f07090f   → getDimensionPixel → int-to-float v5 → c:F
41: const v3, 0x7f070910                       → v6             → d:F
49: const v3, 0x7f07090e                       → v7             → e:F
57: const v3, 0x7f0c00ed   → getInteger        → v8v9           → f:J
65: const v3, 0x7f07090d                       → v10            → g:F
73: move-wide v3, v15      ← the J parameter                    → b:J
74: invoke-direct/range {v0 .. v10}, Lpbu;-><init>(JJFFFJF)V
```

The hold delay is the easy one, because it is a *parameter* rather than a resource read: the
three-argument constructor reads `0x7f0c00ef` (200 ms) and forwards it, so substituting the value
there means `Lpbu;->b:J` is simply built with a different number and the gate in `p()` runs exactly
as Gboard wrote it. That is strictly safer than editing `p()`, which is what caused the `VerifyError`
above.

`Lpbv;` is the opposite case — `g:Z` and `h:[F` are **not** final, and array *contents* are writable
regardless.

### Distance per word lives in `h:[F`, not in a scalar

`r()` offsets 92–108 walk the table and count how many entries the travelled distance has passed;
that count is the number of words:

```
 92: array-length v7, v5
 93: if-gt v2, v7, -> 109       # past the end: extrapolate
 95: v3 = v2 - 1
 97: aget v7, v5, v3            # h[index - 1]
 99: cmpg-float v8, v0, v7      # abs(delta) vs that threshold
101: if-gez v8, -> 105          # passed it: keep walking
103: v3 = (v2 - 1) * direction  # otherwise the count is the index reached
```

So scaling the table scales the swipe length, and a positive factor preserves the strictly
increasing order the constructor checks at offset 108 — which matters, because failing that check
sets `Lpbv;->g:Z`, points `h` at the **shared static** `Lmbs;->c:[F`, and makes `g()` bail at offset
27. Anything scaling the table must skip that case rather than mutate a global.

Past the last entry, offsets 109–118 extrapolate linearly using `ScrubMotionEventHandler->a:I`,
which is written in `g()` and `l()` rather than the constructor. Scaling the table therefore does
not scale very long swipes.

### Capping how many words a swipe deletes

The count is produced at exactly two sites, both a multiply of a magnitude by the direction, and
both feeding one convergence point:

```
103: mul-int/2addr v3, v6      # bucket index × direction              (opcode 0xb2)
118: mul-int v3, v6, v0        # extrapolated magnitude × direction    (opcode 0x92)
120: if-nez v11, -> 132        # 79, 104 and 118 all arrive here
122: iget v0, v9, ->r:I        # the last count dispatched
124: if-ne v0, v3, -> 132      # dispatched only when it changes
```

Clamping the count to ±N caps the words per swipe for free: swiping further produces a raw count
that clamps back to the same value, so the comparison at 124 sees no change and nothing more is
dispatched, while swiping back still reduces the magnitude and restores.

Two constraints make the clamp go where it does.

**It must land before offset 124.** Clamping later would leave the change detection comparing a
clamped `this.r` against an unclamped count, so every further pixel of travel would re-dispatch the
same value and delete another word — precisely the opposite of a cap.

**It cannot go at the convergence.** Offset 120 is a branch target, and *dexlib2 keeps labels
attached to the original instruction*: code inserted before it is not branched to, it is jumped
over. Inserting there would catch only the extrapolation fall-through and silently leave the common
bucket path uncapped. So both producing sites are patched instead.

That is the general rule, and it is a different failure from the verifier one above: **inserting
before a branch target only affects the fall-through path.** Anything that must run on every path
has to go where every path passes through it — which usually means the producing sites, not the
place they converge.

Register choice in `r()` is also not free-lowest-first. `v1` is set to null early and passed as the
`Louc;` argument of `Loud;-><init>` at offsets 158 and 179, so staging anything there would put the
wrong reference type into a live argument. `v4`, `v6` and `v8` are dead from both sites.

### Reading a preference from patched bytecode

`Lpnp;` is the preference store, and it exposes string-keyed getters alongside the resource-id ones:

| | |
|---|---|
| `Lpnp;->N(Landroid/content/Context;)Lpnp;` | static, store from a Context |
| `Lpnp;->b(Ljava/lang/String;I)I` | getInt(key, default) |
| `Lpnp;->a(Ljava/lang/String;F)F` | getFloat(key, default) |
| `Lpnp;->k(Ljava/lang/String;Z)Z` | getBoolean(key, default) |

The string forms are what make patch-added settings practical: a newly added resource has no id
until aapt2 recompiles, long after the bytecode patch runs, so a resource-id getter cannot address
a preference the patch itself introduced. A literal key sidesteps it on both sides.

A Context is in hand in `ScrubDeleteMotionEventHandler.<init>` and in the three-argument engine
constructor. The four-argument one overwrites its Context register with `Resources` almost
immediately, so there `this.o:Landroid/content/Context;` — set by
`AbstractMotionEventHandler.<init>` and read the same way at offset 4 of `r()` — is the way to it.

Activation additionally requires, in `o(IFF)Z`:

- the finger to have left the rect captured at `ScrubMotionEventHandler->i` — the *starting key* —
  but only when `Lpbv;->b:Z` is set, which is true for delete and false for move;
- `|x - startX| >= c()`, i.e. 8pt.

Both are worth keeping: together they are what stops a tap being read as a delete.

## What is still not known

- **What `Lpbv;`'s second argument (`true`/`false`) does.** Not read by either `g()` or `r()`.
- **What the `-100xx` numbers mean individually** beyond the role each plays in `Lpbv;`.
## Password fields do scrub

An earlier version of this document said `PasswordIme;->d(Lnbj;)Z` handled −10063 and none of the
other three. That was an artefact of the byte-scan blind spot above. `PasswordIme` extends
`AbstractIme`, not `LatinIme`, and dispatches through two packed-switches whose payloads decode to
key ranges **−10063 .. −10061** and **−10054 .. −10050** — between them every scrub code, delete and
move alike. It unpacks the signed count with the same `La;->W(Lnbj;)I` that `LatinIme` uses and
feeds it to `Lnsz;->e(I)V`.

So password fields are not an exception, and no input type is excluded from scrubbing anywhere in
the engine.

## Why this matters for Flexboard

The gate is one `if-ne`, the tracking rect is already full width, and the direction handling is
already signed. So the whole of Flexboard's gesture — swipe left to delete a word, swipe right to
restore — is behaviour Gboard already implements and merely declines to start unless the finger
lands on backspace.

The catch is that `g()` is on the **shared** engine. Simply removing the comparison would also let
the spacebar cursor-drag and the inline-suggestion scrub start anywhere, which is not wanted. The
surgical form is a sentinel:

1. Patch `ScrubDeleteMotionEventHandler.<init>` to pass a keycode no real key uses.
2. Patch the comparison at offset 112 in `g()` to skip when `Lpbv;->a` holds that sentinel.
3. Patch `p()` to zero `Lpbu;->b` for that same sentinel, so the gesture answers to a flick.

Three small bytecode edits, all on well-anchored sites, and the other subclasses are untouched
because their `a` is a genuine keycode. No pointer hooks, no dispatch veto, no thresholds of our
own, and no settings rows to drive them — the engine brings its own feel.

Two things this would **not** solve:

- **The glide typing conflict.** Same pointer stream, same collision. That stays solved by
  writing the preference — though note `g()` bails before any of this when the key under the
  finger is wrong, so the conflict window is the same one as before.
- **Editors that opt out.** `noScrubbing` is honoured by the engine and there is nothing to be
  done about it from a patch.

It also depends on **not** needing to touch `res/aDh.xml`. Attaching a new handler declaratively
would require addressing that layout by name, and its resource name is collapsed — see the
addressability note in [`development.md`](development.md). Patching the existing subclass avoids
the problem entirely, which is the main reason to prefer it.

## The glide angle — a dead end, checked

It looked as though the declarative gate might offer another way to stop glide typing: remove the
handler entry, or flip `reverse_preference`, and never touch the user's setting. It does not work
for Latin, for two reasons.

`res/bsB.xml`, the `<include>` at the end of the Latin layout, holds only the spacebar handler:

```xml
<framework>
  <if android_software_xr_api_spatial="false">
    <if free_cursor="false">
      <motion_event_handler class=".motioneventhandler.scrubmove.ScrubMoveMotionEventHandler"
                            preference_key="@0x7f140996" reverse_preference="false"/>
    <else>
      <motion_event_handler class=".freecursor.TriggerFreeCursorMotionEventHandler"
                            preference_key="@0x7f140996" reverse_preference="false"/>
```

So the full handler list for Latin contains **no glide handler at all**. The only
`preference_key="@0x7f14097b"` entry is the *Hindi* dynamic-keyboard handler.
`AbstractGestureMotionEventHandler`, the Latin glide path, is attached by some other mechanism —
unidentified, and not through this list.

Even if it were listed, the layout's resource name is collapsed, so a resource patch has no name
to address it by.

Writing the preference stays the way to resolve the conflict.

For the record, the three scrub preferences resolve as:

| Id | Name |
|---|---|
| `0x7f140995` | `enable_scrub_delete` |
| `0x7f140996` | `enable_scrub_move` |
| `0x7f14097b` | `enable_gesture_input` (glide) |

## How this was derived

Read-only inspection of the APK with Python; no apktool, aapt or adb.

1. **Find the layouts.** Search every `res/**.xml` for a resource id as little-endian bytes. The
   glide id `0x7f14097b` (`7b 09 14 7f`) hits twelve preference/layout resources.
2. **Parse the binary XML.** An AXML file is a chunked format: an 8-byte header, a string pool
   chunk (`0x0001`), then `START_ELEMENT` (`0x0102`) / `END_ELEMENT` (`0x0103`) chunks. Element
   and attribute names are string pool indices; attribute values are either a raw string index or
   a typed value, where type `0x01` is a resource reference. Walking that gives the tree above.
3. **Read the handlers.** Locate the class in the dex by name — these are not obfuscated — and
   disassemble the constructors. The comparison against the sibling is what makes the config
   struct legible; neither constructor means much alone.

## Event codes, and the undo that already exists

The scrub handlers dispatch **event codes**, and `Lovm;-><clinit>()V` is a code-to-name table — a
long run of `const/16 vN, CODE` immediately followed by `const-string vM, 'NAME'`. Decoding it is
the fastest way to learn what a code means:

| | | | |
|---|---|---|---|
| `-10050` | `SCRUB_DELETE_START` | `-10133` | `DELETE_WORD` |
| `-10051` | `SCRUB_DELETE` | `-10134` | `DELETE_WORD_OR_PUNCTUATION` |
| `-10052` | `SCRUB_DELETE_FINISH` | `-10135` | `DELETE_SENTENCE` |
| `-10063` | `SCRUB_DELETE_CANCEL` | `-10136` | `DELETE_ALL` |
| `-10045` | `UNDO` | `-10137` | `UNDO_MULTI_DELETION` |

`LatinIme->d(Lnbj;)Z` is where they land — 1,608 instructions, 36 registers. Its scrub codes are
routed by a `packed-switch` whose payload decodes to `-10050 → pc 2520`, `-10051 → 2952`,
`-10052 → 2969`, `-10063 → 3150`. **Switch keys never appear in the instruction stream**, so
byte-scanning for a code will not find the handler it routes to; decode the payload, or anchor on a
call unique to the handler. `Lnsz;->a(I)Ljava/lang/CharSequence;` is called exactly once in the
whole method, which is what makes `SCRUB_DELETE_FINISH` addressable.

### The delete already records its own undo

This was the surprise. `SCRUB_DELETE_FINISH` does:

```java
CharSequence text = this.S.a(count);            // Lnsz; deletes, and returns what it removed
if (text.length() > 0 && this.C().B(this.B(), text))
    this.y.b(text, this.H);                      // Lqcy; — a one-slot undo store
```

`Lqcy;` is as small as it looks: `b(CharSequence, boolean)` set, `c()` clear, `d()` available?,
`a()` get, where `a()` wraps the text in an `Lnpx;` via `Lnpu;` and returns an `Optional`. The
restore is inline in the same method on `UNDO_MULTI_DELETION`: check `d()`, pull `a()`, re-commit
through `AbstractIme->s(Lnpx;Z)V`, then `c()`. Its gate is
`Lned;->a("nga_enable_undo_delete", true)` — **default on**.

So a scrub delete is undoable by Gboard already; nothing consumes it. Two caveats before relying on
it: `Lqcy;->c()` is called from six points in `d()` alone and from `k()`, `G()`, `H()`, `I()`, `J()`
and `s()`, so the slot survives roughly one further input; and it holds one phrase, not a stack.

### A rightward scrub is a no-op by construction

`Lnsz;->e(I)` opens with `count = Math.min(0, count)`. Positive counts clamp to zero, so a rightward
drag never deletes forward — which is both a safety property and the reason a right swipe was free
to be given a meaning. The clamp is inside `Lnsz;`, not in the event, so the **signed** count still
reaches the finish handler and `count > 0` is a reliable "this was a rightward gesture".

### Reusing a stock branch instead of naming a target

The finish handler opens `iget-boolean vFlag, vThis, AbstractIme->N:Z` / `if-nez vFlag, :handled`,
where `:handled` is the stock treat-as-handled exit. A patch that wants that exit should **set
`vFlag`** and let the stock test jump, rather than resolve a label reachable only through the
switch. It also avoids an early `return`, which would skip the `Trace.endSection()` in the epilogue
and leave the trace stack unbalanced — the method body sits inside a `try` whose handler exists to
call it.
