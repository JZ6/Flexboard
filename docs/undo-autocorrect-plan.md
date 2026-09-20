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

## Answered: Gboard does not instantiate handlers by reflection

This was the first open question at the bottom of this document, and the one Option A rests on. It
is answerable from the dex, and the answer is **no**, which makes Option A materially bigger than
this plan claimed.

The handler class names are unobfuscated — `ScrubDeleteMotionEventHandler` survives in a build where
everything else is `Lpvf;` — which looks like reflection and is not. What actually happens:

```
Leqt;->ba()Llhl;                       # a 44-entry map, built in code
  const-string  '…motioneventhandler.scrubmove.ScrubDeleteMotionEventHandler'
  sget-object   Lmlt;->a:Liwp;         # → a provider singleton
  invoke-virtual Lvvz;->a(Object, Object)V
  const-string  '.motioneventhandler.scrubmove.ScrubDeleteMotionEventHandler'   # short form too
  …
```

and the provider hands off to a factory that is a plain switch:

```
Lhxr;->a(Landroid/content/Context;Lpvo;)Lpvn;
   0: iget          v0, v0, Lhxr;->a:I
   2: packed-switch v0, -> 54
   5: new-instance  v0, …scrubmove/ScrubDeleteMotionEventHandler;
   7: invoke-direct {v0, v1, v2}, …-><init>(Context, Lpvo;)V
  10: return-object v0
  …
```

`Class.forName` appears 96 times in the APK and **not once** anywhere near a motion event handler.
The names survive R8 because they are string keys in a table, not because anything reflects on them.

So the handler set is closed at compile time. Naming `dev.jz6.flexboard.extension.UndoMotionEventHandler`
in the layout XML would resolve against a 44-entry map, miss, and do nothing — or crash, depending on
how the miss is handled. **The XML splice alone cannot work.**

Option A therefore needs, beyond what is listed below: a provider object of the shape the map values
have (`<init>(I)V`, `b()`, `iM()Ljava/lang/Object;` — a tag plus a supplier), and a bytecode insert
adding our name and provider into `Leqt;->ba()`. That insert is at least a *friendly* shape — a
linear run of `const-string` / `sget-object` / `invoke-virtual` with no branch and no merge, so it is
not the register-contract problem that broke dev.0 and dev.1. But it is a fifth piece of work on a
plan that listed four, and it puts a Flexboard object inside a Gboard lookup table.

### The consumer, read out in full

`Lozj;->i(I)Lpvn;` is `MotionEventHandlerManager.newHandlerInstance` — the file and method names are
in the log strings three instructions later, so this is not inference:

```
 25: iget-object   v4, v0, KeyboardViewDef$MotionEventHandlerInfo;->…   # the class name from the XML
 43: invoke-virtual {v5, v4}, Lvwd;->get(Object)Object                  # the 44-entry map
 47: check-cast    v4, Labjb;                                           # ← the provider
 49: if-eqz        v4, -> 61                                            # ← a miss is null, not a throw
 51: invoke-interface {v4}, Labjb;->iM()Object                          # → the factory
 55: check-cast    v4, Lpvm;
 57: invoke-interface {v4, v3, v2}, Lpvm;->a(Context, Lpvo;)Lpvn;       # → the handler
```

Two things follow.

**A name that is not in the map is a silent no-op.** `if-eqz v4, -> 61` falls into the logging block
and returns null; nothing throws. So phase 1 as written — splice the XML, install, see what happens
— would have produced "the handler did not attach" with no way to tell *why* from the device. It
would have cost a release to learn nothing.

**What the extension would have to implement**, minimally:

| Type | Kind | Contract |
|---|---|---|
| `Labjb;` | interface | `iM()Ljava/lang/Object;` — the provider |
| `Lpvm;` | interface | `a(Context, Lpvo;)Lpvn;` — the factory |
| `Lpvo;` | interface | passed in; 10+ methods, only consumed |
| `Lpvn;` | interface | the handler itself — **free**, `AbstractMotionEventHandler` already implements it |

One class can be both provider and factory, returning itself from `iM()`. So the real cost is **two
obfuscated interfaces stubbed and implemented in the extension**, plus `Lpvo;` as a parameter type.

That is the part worth pausing on. Every obfuscated name this project pins today — `Lpvf;->t`,
`Lpnu;`, the flag holders — is pinned at *patch time*, checked by `preflight.py` and re-resolved when
Gboard bumps. An extension implementing `Labjb;` and `Lpvm;` bakes those names into a dex that is
compiled before the APK is ever seen. A bump that renames them is then a **class-load failure on a
device**, which is exactly the failure this project has shipped twice and has spent this whole
session learning to catch earlier.

It is recoverable — patch-time pins on `Labjb;->iM()` and `Lpvm;->a(…)` would refuse the build rather
than ship it, which is the right shape. But it means Option A permanently owns two obfuscated
compile-time dependencies, and no other patch here has one.

## Options

**A. Motion event handler in the extension** *(recommended — but see above; the cost is understated)*
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

**Revised: B first, A only if B cannot claim the gesture.**

The original recommendation was A on the grounds that it avoids interception, with B as a fallback.
That reasoning assumed A's cost was a stub, an XML splice and a new class. It is not: it is those
plus a provider, plus a factory, plus a map insert, plus two obfuscated interfaces compiled into the
extension. The gap between A and B narrowed from "architecture versus hack" to "five pieces and a
compile-time coupling versus one prepend".

B also sits better with what this session built. It is a prepend into `Lpvi;->G` — no branch, no
merge, no register contract, which is the entire class of failure that produced dev.0 and dev.1 —
and `verify.py` plus the `driver +undo` lane can check it locally before anything is installed.

A is not dead. If B cannot make the keypress not happen, A is the way that genuinely can, and the
research above is what it needs. But it should be reached for second, and with its real cost in view.

**Original recommendation, kept for the record:** A, with B as the fallback if the handler cannot be
attached.

Every piece of A already has a precedent here:

| Piece | Precedent |
|---|---|
| Extension class extending a Gboard internal | `FlexboardSettingsFragment` extends `CommonPreferenceFragment` via `stubs/` |
| Compile-time stub for a Gboard base class | `stubs/…/CommonPreferenceFragment.java` |
| Splicing an element into a resource XML | `ToolbarIdAdmissionPatch`, `SettingsScreenPatch` |
| Binary XML handling | `tools/apk/axml.py`, and the resource replay lane |
| Gating on a preference | every handler in the layout already does it |

## Option B, drafted against the dex

Everything below was read out of 18.0.3 rather than assumed, because the last three diagnoses in
this document were assumptions and all three were wrong.

### The call site does the handover for us

```
Lpvf;->t  (TouchActionBundle.handleActionUp)
  54: if-eqz  v1, -> 16                      # no SoftKeyDef -> exit
  56: invoke-virtual {v13,v14,v1,v0,v15}, Lpvi;->G(…)Z
  59: move-result v2
  60: if-nez  v2, -> 16                      # G said "handled"
  …
 116: invoke-virtual/range {v3..v12}, Lpvi;->u(…)   # the keypress commit
  …
  16: move-object v3, v13                    # ← the handover
  17: goto/16 -> 256
 256: invoke-static Trace;->endSection()V    # clean exit
```

Returning true skips pc 62 through 255, **including the commit at 116**, and lands on a block that
sets `v3` to the pointer itself. That is the same `v3` the teardown reads as a `Lpvi;`, and it is
written by Gboard rather than by us. dev.0 and dev.1 crashed jumping into that block with `v3`
holding a `Lpmy;`; here the merge does not arise, because we never jump — we return, and Gboard
branches.

### The prepend is register-free

`Lpvi;->G` is `registers=20, ins=5`, so `this` is v15 and the parameters are v16–v19. **At pc 0
every one of v0–v14 is uninitialised**, so an insertion at the top needs no scratch analysis, no
`live_free` call and no handover. This is the entire reason to prefer a prepend.

### Every signal the guard needs is on `this`

`G` is an instance method on `Lpvi;` — which is `POINTER`, the class the existing corridor test
already reads. So:

| Needed | Available as |
|---|---|
| start x, start y | `this.b:F`, `this.c:F` |
| current x, current y | `this.d:F`, `this.e:F` |
| "does this key bind an upward flick?" | `this.j(Lpmy;)ActionDef;` — the existing `ACTION_DEF_LOOKUP` |

That last row is what keeps goal 4. The current patch gets "no slide action on this key" for free by
anchoring where the lookup returns null; a prepend has to ask the question itself, and it can, on
the same object, through the same method already pinned in `Fingerprints.kt`.

### Sketch

```
# pc 0 of Lpvi;->G — v0..v14 all dead, v15 = this
  sget-object   v0, Lpmy;->c:Lpmy;            # SLIDE_UP
  invoke-virtual {v15, v0}, Lpvi;->j(Lpmy;)…ActionDef;
  move-result-object v0
  if-nez        v0, :stock                    # key owns the flick — leave it entirely alone

  iget v0, v15, Lpvi;->d:F                    # dx = x - startX
  iget v1, v15, Lpvi;->b:F
  sub-float/2addr v0, v1
  iget v1, v15, Lpvi;->e:F                    # dy = y - startY, negative is upward
  iget v2, v15, Lpvi;->c:F
  sub-float/2addr v1, v2
  …corridor: dy <= -threshold and 2*|dx| <= |dy|…
  if-…          :stock

  <dispatch the revert — unchanged from today's emission>
  const/4       v0, 1
  return        v0
:stock
  <original pc 0 onwards>
```

### What this does and does not settle

Settled, by reading: the keypress is skipped, the handover is Gboard's, no merge arises, the
registers are free, and all three guard inputs are reachable. Goals 1, 2 and 4.

Not settled: **goal 3**, the threshold. Round two found the gesture detected only intermittently at
ordinary swipe length, and nothing above changes that — it is the same geometry, just evaluated
earlier. The threshold has to be tuned and then watched on a device, and that is the only part of
this feature that a local check cannot answer.

Also unverified: whether `G` is reached on any path other than `handleActionUp`. The guard is
geometric, so a call with no movement cannot fire it, but "cannot fire" is reasoning and not a
measurement.

## Confirmed on a device: the pointer can be claimed

`2.5.1-dev.1`, diagnostic patch alone, upward flick on a letter key:

> **the 6 appears alone**

That is goal 2, met for the first time. Returning true from `Lpvi;->G` does suppress the keypress —
the commit at pc 116 is skipped, Gboard's own `move-object v3, v13` runs, and no character is
inserted. The architecture question this document was written to answer is settled: **option B
works, and option A is not needed.**

It took one more mistake to get there. `2.5.1-dev.0` could never fire, because it read the gesture
direction from `Lpvi;->i()` — which returns the direction of the *already-resolved* `ActionDef` from
a field written at pc 250, two hundred instructions after `G` runs. It returns null there, always.
The device symptom was "nothing at all", which looks exactly like the gesture not being detected and
was in fact our code never executing. The real classifier is `Lpvi;->h(FFLpmy;)Lpmy;`, and the fix
was to call it the way `handleActionUp` does.

Worth naming the error rather than just the fix: the call sites of `i()` were checked, the register
count was checked, the branch target was checked, the skip label was checked against stock pc 0 —
and the three-line body of the one method whose meaning was taken from its name was not read.

### What remains: goal 3, and it is a dial rather than a design

The `6` arrives **intermittently**, which is the same reliability problem round two found and now
has an exact cause. Inside `h()`:

```
 99-121: v0 = Lpvf;->e|f|g|h|i : I        # base distance, chosen by Lppr;->ordinal()
    123: v2 = abs(dy)      127: v3 = abs(dx)
    131: cmpl-float v2, v2, v3            # more vertical than horizontal?
    143: v5 = -(float) threshold
    145: cmpg-float v5, v6, v5            # dy vs -threshold
    147: if-gez -> 168                    # not far enough: no direction at all
    149: return SLIDE_UP
```

and those fields come from a preference, in `Lpvf;->o()V`:

```
  7: getString(0x7f140ad3)          -> "keyboard_slide_sensitivity_ratio"
 11: const/high16 v2, #0x3f800000   -> default 1.0f
 13: Lqhy;->A(String, F)F
 17-39: this.e|f|g|h = (int)(this.u|v|w|x * ratio)
 41-44: this.i = (int) this.y       # not scaled by the ratio
```

So the threshold is a base distance times a user-settable ratio whose default is **1.0**. (An
earlier draft of this document said 0.6. That was wrong and is corrected here.)

`0.5f` is `0x3f000000`, high-16 representable, so halving the default is a same-width
`const/high16` rewrite — one instruction, no insertion, no registers, the same shape as the Rambler
and haptics patches.

It should ship as **its own patch, default off**, not folded into the gesture. It lowers every slide
threshold, not just upward ones, so it changes the scrub and flick-for-symbols too. Fusing it with
the gesture would make one install answer two questions again.

### Remaining order

1. ~~claim the pointer~~ — done, confirmed.
2. sensitivity, as a separate default-off patch.
3. the revert itself: already written, ships behind the same anchor, untested only because the
   gesture was not reliable enough to test it with.
4. retire the `Lpvf;->t` emitter and the diagnostic.

## Why it fires intermittently, and it is not the threshold

`2.5.1-dev.2` halved `keyboard_slide_sensitivity_ratio`. The marker still arrives on some flicks and
not others. The device report that explains it:

> swipe left, right and down all work 100% of the time

That asymmetry is the finding. Those gestures are not a better-tuned version of this one — they run
on entirely different machinery.

### Two mechanisms, not one

**The scrub** (`ScrubMotionEventHandler->g(MotionEvent)V`) is a motion event handler. It receives
*every* event: on DOWN it records the start, and on each MOVE it re-evaluates and claims the pointer
the moment the drag qualifies. Where the finger is at release never enters into it.

**The slide direction** (`Lpvi;->h`) runs once, on ACTION_UP, over start→end displacement. Three
things follow, and together they are the whole problem:

 - **It measures the release point, not the journey.** An upward flick decelerates, and a finger
   commonly drifts back down as it lifts. Peak `dy` can clear the threshold comfortably while the
   `dy` that `h()` actually sees does not.
 - **One comparison, one chance.** The scrub has dozens of move events in which to succeed.
 - **The arc is invisible.** Curvature, a pause, speed — none of it exists in two points.

Halving the ratio made that single comparison easier to pass. It did not stop it being a single
comparison taken at the worst possible instant, which is why the result is "more often" rather than
"reliably".

### The scrub engine cannot simply be reused

Worth recording, because it looked like configuration and is not. `g(MotionEvent)V` is horizontal by
construction:

```
 104-112: starts only if the touched key's keycode == Lpvs;->a:I
 162-166: records getX(...) only — no Y is stored
 176-186: threshold from Lpvr;->d:F or ->e:F, chosen by Lpvs;->j:I
```

Teaching it about Y means changing what it stores and what it compares, for every subclass that
shares the engine — delete, space-move and inline suggestion. That is a much larger and riskier
change than the gesture is worth.

### The tractable fix: measure the journey ourselves

Keep `Lpvi;->G` for the claim. That part is proven and goal 2 is met. Replace only the *detection*.

`Lpvf;->h(Landroid/view/MotionEvent;)V` is the move path: it iterates every tracked pointer and
writes `Lpvi;->e:F` from `getY` at pc 63, once per motion event. Hook there, hand the coordinates to
the extension, and keep the peak upward displacement per pointer. Then at `G`, ask *did this gesture
ever travel far enough upward* rather than *is the release point high enough*.

The extension is the right home and is already proven on exactly this path — `GestureProbe.fired()`
is called from `G` today and types its marker, so the plumbing works. This adds two floats of state
and one earlier call site.

That gives the gesture the same property that makes the scrub reliable — continuous observation
instead of a single endpoint sample — without modifying Gboard's scrub engine at all.

Open, for whoever picks this up:

 - where to key the per-pointer state. `Lpvf;->h` has the `Lpvi;` in hand; passing its identity hash
   or the pointer id is probably enough, and it must be reset on DOWN or the peak leaks between
   gestures.
 - whether `Lpvf;->h` is reached for every pointer on every move, or only for pointers the delegate
   already owns. Read it before relying on it.
 - whether the corridor test should also move to peak-relative rather than release-relative.

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

- ~~Does Gboard instantiate handlers by reflection from the class name?~~ **Answered: no.** A
  44-entry compile-time map from name to provider, and a switch that constructs. See above. The
  follow-on question is which interface a provider must implement for the map's consumer to accept
  it.
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
