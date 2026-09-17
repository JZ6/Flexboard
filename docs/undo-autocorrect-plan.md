# Swipe up to undo autocorrect — replanning after two broken releases

> Written after `2.5.0-dev.0` and `dev.1` both shipped a keyboard that would not open. This
> supersedes the design in [`undo-autocorrect.md`](undo-autocorrect.md), which is kept because its
> research is still correct — only its *architecture* is wrong.

## The goal, stated properly

The gesture should feel the way **Swipe Left to Delete** feels: a deliberate motion that the
keyboard recognises as its own thing.

1. An upward swipe on a key **reverts the last autocorrection**.
2. It **does not type the key**. Not "types it and then reverts something else" — the keypress
   must not happen at all.
3. It is reliable at normal swipe length, not only when exaggerated.
4. It does not interfere with the scrub, with flick-for-symbols, or with ordinary typing.

Point 2 is the one every attempt so far has failed, and it is the one that decides the
architecture.

## Why the current approach cannot deliver that

The emission hooks `Lpvf;->t` — `TouchActionBundle.handleActionUp` — at the point where the
`ActionDef` lookup comes back null. That is **after Gboard has already decided this pointer is a
keypress**. The commit at `Lpvi;->u(...)` is further down the same method, on the same path.

So the design is: let Gboard conclude it is a keypress, then try to prevent the keypress. Three
ways to do that were tried:

| Attempt | Result |
|---|---|
| Fall through after dispatching | Fires **and** types the key |
| `goto` to Gboard's teardown | Verify error at class load — keyboard never opens |
| Same, plus handing over `v3` | Still a verify error |

The third failed because the handover was built from a **liveness** check when the verifier does a
**type** check. `iput-object v4, v3, Lpvi;->n:…` reads `v4`: null on every stock arm, an
`ActionDef` on ours. `v4` is live in both, so "what is missing" found nothing — and ART still
rejects the merge.

Branching into an existing block means reproducing that block's entire incoming contract —
`{v0, v1, v2, v3, v4, v14}` here, by type, as an internal detail Gboard is free to change. That is
not a thing to get right once; it is a thing to get right on every Gboard release, with no local
way to test it.

**The approach is wrong, not unfinished.** Intercepting late and suppressing is fighting the
design.

## What Swipe Left to Delete actually does

It does not suppress anything, because the keypress never starts.

`ScrubDeleteMotionEventHandler` is attached in the layout XML:

```xml
<view override="motion_event_handler" type="body">
  <motion_event_handler class=".libs.latin5.handler.LatinMotionEventHandler"/>
  <motion_event_handler class=".motioneventhandler.scrubmove.ScrubDeleteMotionEventHandler"
                        preference_key="@0x7f140995" reverse_preference="false"/>
  …
</view>
```

A handler sits **in front of** the key machinery and claims the pointer. Once claimed, the pointer
never reaches the keypress path, so there is nothing to suppress and no merge to get right. That is
why the scrub feels native: it *is* native, using the extension point Gboard provides.

Everything the gesture needs is already available at that layer, and none of it requires
understanding an internal register contract.

## Gboard's own "already handled" path, for completeness

There is a second mechanism worth recording, because it nearly became the plan.
`Lpvi;->G(MotionEvent, SoftKeyDef, int, int)Z` is checked early in `handleActionUp`:

```
invoke-virtual {v13, v14, v1, v0, v15}, Lpvi;->G(…)Z
move-result v2
if-nez v2, -> 16      # true -> move-object v3, v13 ; goto exit
```

Returning true skips the whole direction dispatch **and sets `v3` correctly on the way out** — it
is the clean version of the jump that crashed. It exists for popup/gesture consumption
(`Lpvi;->q:Lqer;`), and `Lpvi;->q(ActionDef, …)` is the canonical "fire this key action".

This is a real option and it is strictly better than what shipped. It is still an *interception*,
though: it requires our code to run inside `handleActionUp` and persuade Gboard it already did
something. The handler approach means never being in that method at all.

## Options

**A. Motion event handler in the extension** *(recommended)*
Write a handler, attach it in the layout XML beside the scrub's, claim the pointer on an upward
flick, dispatch the revert.
*For:* the mechanism the goal describes; no register contracts; no merge; keypress never starts;
attachable behind a preference key like every other handler.
*Against:* the largest piece of work. Needs a stub for the base class, an axml splice, and a new
extension class.

**B. Return true from `Lpvi;->G`**
Prepend a guard that recognises our gesture, fires the revert and returns true.
*For:* much smaller; uses Gboard's own consumption path; prepending has no merge problem.
*Against:* still interception. Depends on `G` keeping its meaning, and on our guard sitting
correctly beside the `Lqer;` logic already there.

**C. Bind a real SLIDE_UP `ActionDef` to the keys**
Make the keys genuinely define an upward action, so Gboard dispatches it natively.
*For:* the most native of all — zero bytecode in the gesture path.
*Against:* conflicts head-on with flick-for-symbols, which is what SLIDE_UP is for on those keys.
Rejected earlier for this reason and the reason still holds.

**D. Keep falling through, accept the key is typed**
*Against:* fails goal 2. Not a candidate; recorded only because it is what ships today.

## Recommendation

**A**, with **B** as the fallback if the handler cannot be attached.

Every piece of A already has a precedent here:

| Piece | Precedent |
|---|---|
| Extension class extending a Gboard internal | `FlexboardSettingsFragment` extends `CommonPreferenceFragment` via `stubs/` |
| Compile-time stub for a Gboard base class | `stubs/…/CommonPreferenceFragment.java` |
| Splicing an element into a resource XML | `ToolbarIdAdmissionPatch`, `SettingsScreenPatch` |
| Binary XML handling | `tools/apk/axml.py`, and the resource replay lane |
| Gating on a preference | every handler in the layout already does it |

## The thing that has to be fixed first

**Nothing here is verifiable locally.** The gate compiles patches and pins the stock APK; it never
loads a patched class. A verify error is invisible to every lane, which is why two releases shipped
broken and why each attempt costs a device round-trip.

Four device failures this session were all discoveries that a local apply-and-load would have made
in seconds. Before more bytecode goes into this gesture:

- get `:driver:run` applying a built bundle locally (blocked on the Android SDK), **or**
- get one logcat from a crashing build — `adb logcat` around the failure names the rejected class
  and register, **or**
- accept that each iteration costs a release, and plan the smallest possible steps accordingly

This is the highest-value item on the list. It is worth more than the feature.

## Phases

1. **Verification first.** Establish a local or on-device way to see a verify error. Everything
   below is cheaper and safer once this exists.
2. **Confirm the handler attachment point.** Decode the Latin layout, find the handler list, and
   confirm an added `<motion_event_handler>` with a `preference_key` is honoured. Pin it.
3. **Stub the base class**, mirroring how `CommonPreferenceFragment` is stubbed. Compile-only.
4. **Write the handler**, claiming the pointer on an upward flick and dispatching `-10045`.
   Thresholds reuse `keyboard_slide_sensitivity_ratio`, already defaulted to 0.6.
5. **Splice the handler into the layout** behind a Flexboard preference, so it can be switched off
   without rebuilding.
6. **Delete the old emission** and the diagnostic patch, and fold what was learned into
   `undo-autocorrect.md`.

## Open questions

- Does Gboard instantiate handlers by reflection from the class name? If so, does R8 shrinking or
  the manifest affect a class that only the XML references?
- Which layouts need it — Latin only, or every alphabet layout?
- Does a handler claiming the pointer suppress the keypress **and** leave the scrub unaffected when
  both are attached?
- Is `-10045` still the right payload from a handler context, or does the handler have a more
  direct route to the edit tracker?

None of these are answerable from the dex alone with confidence, which is the same reason phase 1
comes first.
