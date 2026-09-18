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

### The cause, finally established by looking at the output

Three causes were proposed from the stock disassembly and none was right. The actual one came from
`tools/apk/patched.py` in a single run, and it is not a subtle verifier question at all.

This is what dev.1 shipped, read out of the installed APK:

```
sget-object v3, Lpmy;->c:Lpmy;      # v3 is now a Lpmy;
if-ne v2, v3, -> 92
invoke-static {}, …GestureProbe;->fired()V
goto/32 -> 238                      # the teardown, which reads v3 as a Lpvi;
```

**There is no `move-object v3, v13`.** The handover added in dev.1 emitted nothing, so dev.1 was
byte-identical to dev.0 in the only part that mattered.

`handoverFor` computed its shortfall with a *linear* `liveIn` walk. Walking forward from the seam
touches nearly every register eventually, so "available at the seam" came back as all sixteen, the
shortfall was empty, and the function returned `""`:

```kotlin
?: if (shortfall.isEmpty()) { return "" }
```

The CFG-correct answer, from `live_free`, is `[3]`.

So the original diagnosis was **right**: the emission makes v3 a `Lpmy;` and branches to a block
that reads it as a `Lpvi;`. A plain type conflict. Seeing "the fix didn't work", concluding the
diagnosis was incomplete, and hunting for a second cause was the error — there was no second cause,
only an absent fix.

Two things follow, and the second matters more than this feature:

- **Linear liveness, again.** The same mistake was fixed in `assertNotReadBeforeWritten` earlier in
  the same file, days before. A fresh linear scan was written instead of calling `live_free`, which
  is correct and already in the repo. When a correct implementation exists, reaching for a new one
  is the smell.
- **An emitter can emit nothing, silently.** `handoverFor` had a legitimate-looking empty-case
  return. Nothing compiles differently, no lane fails, and the patch applies. Until
  `tools/apk/patched.py` there was no way to notice.

### What this does and does not say about the architecture

It weakens the case against branching. The plan below argues that reproducing a block's register
contract by type is a bad bet on every Gboard release, and that stands. But the crash was **not**
evidence for it: the branch approach was never actually tested, because the build that implemented
it properly never shipped.

The recommendation is unchanged, on the original grounds. It should not be justified by a failure
that turned out to be a no-op.

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

**Nothing here reads what the patcher produced.** `preflight.py` takes the *stock* dex tree and the
*stock* APK. `tools/gate` compiles the patches and pins Gboard. No lane has ever looked at a patched
class. That is why two broken releases got out, and why three diagnoses were guesses.

The fix does not need the Android SDK, a device, or adb. **The disassembler is dex-generic** —
`dexlib.load()` takes any directory of `.dex`, and `dis.find`/`dis.show` work on whatever is loaded.
Morphe Manager writes a patched APK. Point the existing tooling at that and the emission can be read
directly: the actual instructions, the actual `goto` offset, the actual register state at the seam.

Concretely, in order of cost:

1. ~~**`tools/apk/patched.py`**~~ — **done.** Takes a patched APK, extracts its dex, disassembles a
   method and diffs it against the stock one. No new dependencies. Run it as:

   ```
   tools/apk/patched.py flexboard.apk 'Lpvf;->t(Lpvi;Landroid/view/MotionEvent;I)V' --stock gboard-apk
   ```

   It is deliberately not a `tools/gate` lane: the gate has no way to produce a patched APK, and a
   lane that can only ever skip is worse than a documented command.
2. ~~**A merge check over the patched method.**~~ — **done.** `tools/apk/verify.py` propagates
   register types over the real control-flow graph and reports a conflict that reaches an
   instruction requiring a type. On the installed dev.1 APK it names `v3` at pc 238; on dev.2 it is
   silent; across 3,001 untouched Gboard methods it finds nothing.
3. ~~**`:driver:run`**~~ — **done, and it was never blocked.** The SDK is needed to *build* a
   bundle, not to apply one, and CI attaches a built `.mpp` to every release:

   ```
   gh release download <tag> --dir /tmp/mpp
   FLEXBOARD_BUNDLE=/tmp/mpp/patches-*.mpp tools/gate
   ```

   This is now a gate lane, opt-in on that variable. "Blocked on the SDK" was asserted in three
   places in this repo and never tested; it cost every device round-trip this session.
4. **A logcat**, if adb ever becomes available. Names the rejected class and register outright.

Note the ordering change from the first draft, which put the SDK first and described (1) nowhere.
(1) is cheaper, needs nothing that is missing, and is strictly more informative for this class of
bug.

## Success criteria

"Feels as natural as swipe left to delete" needs to be checkable, or the next iteration argues about
taste instead of behaviour:

- an upward swipe of ordinary length fires it, on **most attempts** rather than occasionally
- **no character is inserted** — not one that is inserted and then deleted
- the last autocorrection is reverted, and a swipe with nothing to revert does nothing visible
- ordinary typing, the scrub, and flick-for-symbols are all unaffected
- the keyboard opens

The last one is not a joke. It is the one two releases failed, and it should be checked first every
time.

## Blast radius while this is in progress

- The patch stays **default off** until it has been watched working, per `AGENTS.md`. Two releases
  reached only people who ticked it, which was luck rather than design.
- Every step ships as its own dev release, smallest first, so a failure identifies itself.
- The currently shipped fall-through version works and types the key. That is a known, documented
  limitation and is a better state than broken — it stays until the replacement is confirmed.

## Phases

Each is a release on its own, smallest first, so a failure names itself.

**0. ~~Read what we ship.~~ Done, and it changed the plan.** `tools/apk/patched.py` showed the
dev.1 emission had no handover in it at all. `:driver:run` turned out never to have been blocked by
the missing SDK, `tools/apk/verify.py` now catches the crash class automatically, and CI uploads a
bundle on every push. The loop is: push, download the artifact, apply, verify — no release, nothing
published.

That also reopens the branch approach. It was abandoned on the strength of a failure that was a
no-op, and it can now be tried with the failure mode caught locally instead of on a phone.

**1. Can a handler attach at all?** The cheapest possible probe: a handler that does nothing but
type a marker on any touch, spliced into the Latin layout behind a preference. If Gboard does not
instantiate it — R8, manifest, reflection — the whole approach dies here for the price of one
release, before any gesture logic exists.

**2. Stub the base class.** Mirroring `stubs/…/CommonPreferenceFragment.java`. Compile-only, no
behaviour.

**3. Claim the pointer.** Extend the probe to recognise an upward flick and consume it, still with
no revert. Success is *the key stops typing* — which is goal 2, tested in isolation.

**4. Dispatch the revert.** Only once 3 holds. Thresholds reuse `keyboard_slide_sensitivity_ratio`,
already defaulted to 0.6.

**5. Retire the old path.** Delete the `handleActionUp` emission, the diagnostic patch and the
gesture probe; fold the findings into `undo-autocorrect.md`.

Phases 1 and 3 are the ones that can fail cheaply and informatively. Phase 0 is the one that makes
all the others debuggable.

## Open questions

- Does Gboard instantiate handlers by reflection from the class name? If so, does R8 shrinking or
  the manifest affect a class that only the XML references?
- Which layouts need it — Latin only, or every alphabet layout?
- Does a handler claiming the pointer suppress the keypress **and** leave the scrub unaffected when
  both are attached?
- Is `-10045` still the right payload from a handler context, or does the handler have a more
  direct route to the edit tracker?
- Does a handler see the pointer *before* the key machinery, or alongside it? The scrub's behaviour
  implies before, but that is inference from how it feels, not something read out of the dex.
- What actually broke dev.0 and dev.1? Still unknown. Phase 0 answers it.

None of these are answerable from the dex alone with confidence — which is the argument for phase 0
rather than more reading. The first draft of this plan put the SDK first and left the cheap,
available option out entirely.
