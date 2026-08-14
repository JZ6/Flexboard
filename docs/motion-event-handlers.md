# Gboard's motion event handlers

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
| `a:J` | `0x7f0c00ee` | 150 ms | gate on the `ACTION_DOWN` time against `Lpbr;->c()` |
| `b:J` | `0x7f0c00ef` | **200 ms** | **hold delay before activation is even considered** |
| `c:F` | `0x7f07090f` | 8pt | activation distance, read via `c()F` in `o(IFF)Z` |
| `d:F` | `0x7f070910` | 16pt | per-step distance when `Lpbv;->j` is 1 (scrub move) |
| `e:F` | `0x7f07090e` | 8pt | per-step distance otherwise (scrub delete) |
| `f:J` | `0x7f0c00ed` | 1000 ms | delay before the `noScrubbing` toast |
| `g:F` | `0x7f07090d` | 4mm | vertical inset applied to the tracking rect |

`b:J` is the one that shapes the feel. `p(Landroid/view/MotionEvent;I)Z` opens with

```
iget-wide v5, v0, Lpbu;->b:J
add-long/2addr v3, v5          # downTime + 200ms
cmp-long v0, v1, v3
if-gez v0, :continue
return v1                      # too soon — regardless of distance travelled
```

so no amount of movement activates the gesture inside the first 200 ms. That is what makes the
stock gesture a press-and-drag rather than a flick, and it is why a Fleksy-style flick — over in
well under 200 ms — was being discarded before the distance test ever ran.

The delay is **per handler**, not global: the 3-argument constructor supplies it from
`0x7f0c00ef` (200), while `InlineSuggestionScrubSpaceMotionEventHandler` calls the 4-argument
form with `0x7f0c006f` (50). Gboard already ships two different values, so changing it on one path
follows the engine's design rather than fighting it.

Activation additionally requires, in `o(IFF)Z`:

- the finger to have left the rect captured at `ScrubMotionEventHandler->i` — the *starting key* —
  but only when `Lpbv;->b:Z` is set, which is true for delete and false for move;
- `|x - startX| >= c()`, i.e. 8pt.

Both are worth keeping: together they are what stops a tap being read as a delete.

## What is still not known

- **What `Lpbv;`'s second argument (`true`/`false`) does.** Not read by either `g()` or `r()`.
- **What the `-100xx` numbers mean individually** beyond the role each plays in `Lpbv;`.
- **How password fields differ.** `PasswordIme;->d(Lnbj;)Z` handles −10063 but none of the other
  three, which has not been chased down.

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

Two small bytecode edits, both on well-anchored sites, and the other subclasses are untouched
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
