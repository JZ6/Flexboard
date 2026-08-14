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
preference Flexboard writes. See [`glide-detection.md`](glide-detection.md).

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

| `Lpbv;` argument | ScrubDelete | ScrubMove | reading |
|---|---|---|---|
| 1 | **67** | **62** | `KeyEvent.KEYCODE_DEL` / `KEYCODE_SPACE` |
| 2 | `true` | `false` | unidentified flag |
| 3 | 2 | 1 | unidentified; direction or mode |
| 4–7 | −10050, −10051, −10052, −10063 | −10061, −10053, −10054, −10062 | Gboard-internal event codes |
| 8 | `0x7f0300b5` | `0x7f0300b6` | resource reference, `0x7f03` is the `attr` type |

**The scope of the whole feature is one integer: which key the drag must start on.** Everything
else — thresholds, direction handling, progressive delete — is generic engine code.

## What is not known

- **Where the keycode is enforced.** It is not in `o(IFF)Z` (that is the distance threshold
  against `c()F`), `fb(Lnbj;)Z` (handles internal event `-10091`), or `t(MotionEvent)Z` (terminal
  action check). Most likely `g(Landroid/view/MotionEvent;)V`, 259 instructions, unread.
- **Whether a wildcard keycode is accepted**, or whether the engine can be scoped to a region
  rather than a key.
- **Whether the engine supports the restore half.** Gboard's backspace scrub appears to restore
  when you drag back, which is what Flexboard's right-swipe does, but that has not been confirmed
  in the bytecode.
- **What `0x7f140995` is** — the preference gating scrub delete. Unresolved; the method in
  `glide-detection.md` would resolve it.
- **What the `-100xx` codes mean.** They are Gboard-internal event ids.

## Why this matters for Flexboard

If the keycode gate can be widened, or a third subclass registered with its own `Lpbv;`, then
Flexboard's core could become a resource patch plus a small config class instead of pointer
hooks, a dispatch veto, three tunable thresholds and the settings rows that drive them. It would
also inherit Gboard's own feel and, possibly, its restore behaviour.

Two things it would **not** solve:

- **The glide typing conflict.** Same pointer stream, same collision. That stays solved by
  writing the preference.
- **Risk.** It replaces the working core of a shipped release. This is a v0.4 experiment at the
  earliest, and it needs the unknowns above closed first.

## The glide angle

The same declarative gate attaches gesture handlers, so a resource patch that removes the entry
or flips `reverse_preference` is in principle another way to stop glide typing — without touching
the user's setting at all. Flexboard writes the setting instead, because that is simpler and every
consumer agrees with it. Worth remembering if the write ever stops being viable.

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
