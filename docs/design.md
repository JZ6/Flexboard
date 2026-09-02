# Design notes

Why Flexboard behaves the way it does. The [README](../README.md) says what it does; this is the
reasoning behind the choices a user would otherwise have to guess at.

## What the swipe settings default to, and why

**Swipe length, 60% — a middle.** This shipped at 36% up to `1.1.0-dev.1`, reasoning that Gboard's
distance assumes a thumb travelling from the backspace key and back, which is the whole journey
this patch exists to remove, so a gesture starting under your thumb wants a shorter one. The
reasoning is sound and the number was too aggressive: at 36% an ordinary swipe crosses three or
four thresholds, and the word cap is then doing the work of hiding it. It went to 100% — Gboard's
own — as the safe correction, which asks for the whole journey again.

Sixty keeps the reasoning without the overshoot. With the word cap at its default of 1 a swipe
still deletes one word however far it travels, so this changes how far that is and nothing else,
and 100% remains available to anyone who wants Gboard's distance exactly.

**Max words per swipe, 1 rather than no limit.** One word per swipe makes each deletion deliberate
rather than a run that then has to be swiped back.

**Hold delay, 0 ms rather than 200 ms.** Gboard waits before its delete swipe engages, which is what
makes it feel like a press-and-drag rather than a flick. Zero is not an improvement on that so much
as continuity: it is what Flexboard did before the delay was adjustable at all, so existing installs
keep the feel they had.

**Only the word cap is still a slider.** Swipe length and hold delay were, and are not: the swipe
length consumers are commented out in `ScrubTuningPatch`, and the hold-delay row was dropped while
its smali read stayed, so it is fixed at 0 ms. The reasoning above is kept because it is why the
fixed values are the values they are, not because all three are still adjustable.

## Why the starting values are written rather than defaulted

Every value in this project is read out of Gboard's preference store by patched bytecode, and the
read carries a fallback operand. Making that operand the starting value is the obvious thing, costs
one literal, and is what swipe length did for its first several releases.

It has one property worth avoiding: **a read-side default follows the code.** Change the number in
a later release and it moves every user who never touched the slider. Someone who has spent a month
with a keyboard finds it different after an update they did not ask for, and nothing they did
caused it.

So the starting value is instead written into the store on the first run after installing —
`seedDefaultsPatch`, one instruction, calling the extension's `Defaults.seed`. Afterwards it is an
ordinary stored preference, indistinguishable from one the user set, and later releases can pick a
different starting number for new installs without disturbing anyone.

In practice this now seeds **one** key, `flexboard_scrub_step_scale`, and nothing reads it: the
swipe-length scaling it exists for is parked. The patch is kept so re-enabling that finds an
established value rather than a fresh one, but it currently has no runtime effect at all, and the
reasoning above describes the three-value era rather than what ships.

**It is also much less work than doing it in bytecode**, which is not the reason but is worth
recording, because the first attempt went the other way. The extension has always been able to
write preferences — it is how the settings screen stores a slider — so the patch does not need to
reach a setter on Gboard's store at all. It hands `Defaults` a `Context` and stops. Doing it in
smali would have meant deriving a string-keyed setter and reusing the `getInt` whose two
same-signature siblings on that class are a documented trap, for an identical result.

### The same mistake was already in two other patches

`forceScrubPreferencesPatch` and `suggestedSettingsPatch` write **Gboard's** own settings at startup, and
both did it in bytecode through the store's id-keyed accessors. That looked unavoidable, because a
Gboard preference is addressed by resource id rather than by name.

It is not: `Lqhy;` resolves an id through `PreferenceKeyCache`, which is `Resources.getString`
behind a map. So a preference key is a string resource's value, and both patches are now the same
one-instruction handoff — `getString(id)` for the key, `SharedPreferences` for the write.

What that deleted is the point. The id-keyed `contains` had to be *derived*, because its signature
is shared with a sibling that answers "is it currently true?" rather than "has the user ever set
this?" — and for flick keys, a write-once default, that difference is between behaving as a default
and forcing the setting back on at every start. The framework's `contains` has no sibling. Two
derivations, two register-liveness arguments and one silently-wrong failure mode, all gone, and the
resource ids picked up a preflight check they never had.

The general shape, worth applying to anything added later: **before deriving an accessor on an
obfuscated class, ask whether the extension could do it against a framework API instead.** It can
whenever the data is reachable from a `Context`, which for anything preference-shaped it is.

The toolbar counts had no starting value at all before this: an unset preference fell through to
whatever Gboard computed, and the 5 the settings screen showed was only ever displayed. That is why
`README.md` no longer says leaving the slider alone changes nothing.

**Neither of those two defaults could be moved by editing one number**, because each number was
doing two jobs — the default *and* a control-flow sentinel, at which the scaling or the clamp is
skipped. Setting a default to its sentinel value makes the chosen setting the one setting that does
nothing. They are four constants rather than two for that reason, and `ScrubTuningPatch.kt`
documents each.

Swipe length is the worked example, and it has now moved three times. Going to 36 forced the split
in the first place; coming back to 100 made `STEP_SCALE_DEFAULT` and `STEP_SCALE_IDENTITY` hold the
same number and look redundant; going to 60 has separated them again. That third move cost one
constant precisely because the second one did not collapse them.

## Why the text editing buttons carry a Runnable rather than a keycode

Every other button on Gboard's toolbar works by emitting a keycode, so the obvious build is a button
emitting `TEXT_EDITING_SELECT_ALL`. That button would render, press, highlight, and do nothing. The
same is true of copy and paste.

`TEXT_EDITING_SELECT_ALL` is -10086, and the number appears exactly **once** in the app: as an entry
in the map that resolves `<key_code>` when Gboard parses keyboard XML. Two packed-switches cover it
and neither acts on it — one is a classifier, the other is metrics. The only implementation lives in
the text-editing keyboard's own consume-event hook, so it runs only while that panel is open.

Undo is what makes this counter-intuitive, and it is why the assumption survived as long as it did.
Undo is also a "text editing" keycode, Flexboard's undo works from anywhere, and the difference is
invisible from outside: undo is consumed at *IME* level by four separate handlers, and select-all by
none. Reasoning from undo to select-all gives the wrong answer.

What the button uses instead is a Gboard mechanism that is genuinely global: the access-point
builder's `Runnable` setter, which does not store a field but wraps the Runnable as key data under
keycode **-40007** — and *that* is dispatched at IME level. So the button carries code rather than
an instruction, and the code calls `performContextMenuAction` on the input connection, which is what
Gboard's own panel does.

**One extension class serves all three buttons**, told apart by an ordinal the patch passes to its
constructor. Three classes would mean three copies of the IME-service holder and three places for
the null handling to drift, or one holder the others reach into — the same coupling with more
indirection. The ordinal is Flexboard's own rather than `android.R.id.copy` directly, so the
framework constants stay symbolic in the one language that can name them instead of appearing as
`0x0102001b` in Kotlin.

**Nothing is published or registered**, which is the part worth recording because a lot of work went
into the route that is not used. Gboard's access-point providers build notification objects and
store them in fields, and what later publishes them was never established — that question blocked
the feature for a while. It turned out to be avoidable: the method that splits the ordered list into
"on the bar" and "in the overflow" takes that list **as a parameter** and only reads it. Substituting
a longer list at entry adds buttons, and the whole notification machinery is bypassed.

**The builder's setters are derived, not named.** Five of them share the signature `(I)V`. That is
exactly the shape behind this project's worst bug — `AbstractIme->s` was the undo re-commit on
17.7.7, and on 18 a *different* method inherited `s` with the same signature. So they are told
apart by **Gboard's own words for them**: the builder is generated code that refuses to build an
incomplete access point and names what is missing, and each name is tested against one bit of a
completeness mask that exactly one setter writes. A bit therefore leads from a setter to a string
literal, and string literals are the one thing R8 does not rename. `preflight.py` asserts that
`(I)V` is still ambiguous there — if it ever stops being, this machinery is over-built and can go.

That replaced an earlier derivation which read the setters off the resource ids Gboard's own seed
handed them. It worked, and it carried an admitted caveat: the seed passes the label and the
content description **the same string**, so the two could not be told apart by value. Harmless
while both were set to the same text, and not harmless once hotkeys arrived — see below.

## Why a hotkey wears the user's own text as its name

Six hotkey slots need six names, and there is no Gboard string that means "whatever you typed into
slot four". Nor is there a numbered icon: matching all 2,170 published Material Icons against the
APK found 29 bundled shapes and no digits, so the icons are arbitrary markers.

The way out is that the access point carries **both** forms of its label — a resource id and a
literal `String` — and its accessor returns the literal whenever the resource id is zero. So a
hotkey sets the id to zero and writes the snippet into the literal.

**Whether that is safe is a question about readers, and it was answered by sweeping for them.** The
label resource id is read in exactly five places: the copy-constructor, `equals`, `hashCode`, and
the accessor. Nothing renders from it directly, so zero cannot reach a rendering path. The content
description is *not* like that — four rendering methods read its id straight off the access point —
but every one of them guards with `if-eqz` before calling `getString`, so zero means "no resource"
rather than a lookup of resource 0. Both facts are now `preflight.py` checks, because the second is
the only thing standing between a hotkey and `NotFoundException` while the toolbar is being built.

There is no builder setter for either literal; they are pass-throughs the generated `build` never
validates, so the patch writes the fields directly. Which field is derived rather than named, from
the order `build` reads its fields into the constructor's arguments: each property is a resource id
**immediately** followed by its literal. Adjacency matters — the icon's literal is an `Icon`, not a
`String`, so a looser "next String field" rule walks past it onto the label's. That is not
hypothetical: it is what the first version of this did, and the preflight check caught it.

## Why undo is Gboard's own, not a reimplementation

The first estimate for the feature assumed Flexboard would have to capture the deleted text and
reinsert it. It does not. Gboard's delete swipe already records what it removed, and Gboard already
knows how to put it back — its own `UNDO_MULTI_DELETION` path, enabled by default. The only missing
piece was a way to ask for it, and a rightward swipe after the gesture had ended was doing nothing
at all beforehand.

That inheritance is also where the two limits come from. One deleted phrase is kept and it is
cleared on almost any other input, so undo only works as the very next thing you do, and only once.
Neither is a decision; both are Gboard's, and lifting them would mean the reimplementation that was
avoided. `UndoDeletePatch.kt` has the full account.

Undo is unconditional. It had a switch, outside the master switch's group because Gboard fills the
same undo slot from the backspace key; both switches are now gone — see below.

## Why flick keys has no runtime switch

It writes Gboard's own preference exactly once, only if it has never been set, so it behaves as a
default rather than something forced. A runtime switch would therefore do nothing after the first
run — and a control that silently stops working is worse than no control. Unticking the patch in
Morphe is the honest way to turn it off.

The same reasoning does not apply to the glide settings, which are rewritten on every start
precisely because Gboard must not be left able to break the gesture.

## Why there is no on/off switch for anything

Flexboard carried two: a master switch, and one for undo. Both were removed, and Morphe unticking a
patch is now the only off switch.

The argument is about cost per Gboard version rather than about the feature. Reading a preference
from patched bytecode is an *insertion*, and an insertion needs registers proved dead at the point
it goes in — which is precisely what R8 re-rolls on every build. The master switch needed three
scratch registers inside a constructor, with a proof that every intervening instruction was a
`const`, a `Context` parameter shown unclobbered, all three registers under the `35c` nibble limit,
and an argument that an uninitialised `Lpvs;` live across the inserted branch still verified. The
undo switch needed the IME's `Context` resolved out of a field on `AbstractIme` — the derivation
whose absence bricked the keyboard in `0.0.1-dev.1` — plus a borrow of the suppression flag that had
to be exactly undone or Gboard would swallow every delete finish.

None of that was buying much. Every one of those facts had to be re-established for 18.0.3, and
between them the two switches accounted for most of the port. What they offered a user, Morphe
already offers properly and for free.

The sliders stay, because their values genuinely vary by thumb and by screen, and because two of
the three are substitutions of a constructor argument read from a resource — the cheap shape, with
no scratch registers and no control flow touched.

The trade is real and worth naming: **glide typing can no longer be handed back from inside
Gboard.** It is forced off for as long as the patch is applied.

## Why the glide rows are greyed out rather than left alone

Glide delete is written on, glide typing off, at every start. A user who changed either would see
the change appear to take and then quietly revert — the worst of the three options. Greying them out
states the constraint instead of hiding it.

They are greyed with a plain `android:enabled="false"`. It used to be an androidx `dependency` on
the master switch, which had the advantage of un-greying live when the switch was tapped; with the
switch gone that mechanism became unusable, and not merely redundant. androidx requires a preference
carrying the dependency's key to exist in the same hierarchy and throws `IllegalStateException` from
`registerDependency` otherwise — so removing the switch while leaving the dependencies would have
taken out Gboard's whole gesture settings screen. A static attribute has no such requirement.

A greyed row with no explanation is still worse than a tappable one, so a non-selectable note sits
above them saying what is doing it and that re-patching without Swipe to Delete is the way back.
[`gboard-settings-ui.md`](gboard-settings-ui.md) covers how the rows are reached and disabled.

## Why the signature bypass stays, though it gates nothing

`docs/roadmap.md` asked whether the bypass patch is still needed. The answer is that nothing needs
it, and it stays anyway.

Gboard hashes its own signing certificate and compares it byte-for-byte against three baked-in
digests. `Lrpv;->a` has exactly two callers. One is `WebDebugBridgeContentProvider;->call`, which
checks the *caller* of a developer debug provider rather than Gboard. The other is `Lmm;->run()`
case 8, scheduled from `LatinApp;->e()` on cold start in the main process — and its entire body is
the check followed by `return-void`. It throws `IllegalStateException` on failure and does nothing
on success, so a failing check skips no work, because there is none to skip. No Flexboard
subsystem references `Lrpv;` at all.

Patched without it, on 2026-08-18, the keyboard still opens: the throw lands on the background
executor in `LatinApp;->c` and the process survives.

It is kept because the cost of keeping is low and the cost of being wrong is not. An exception on
every cold start is worth silencing even when it is survivable, it is presumably reported to
Google's crash telemetry, and dropping it means betting that a background throw stays harmless on
every device and Android version rather than the one it was tried on. The derivation is also among
the more stable in the project.

Two corrections this turned up, both recorded here because the wrong version was written down and
believed for a while. The README and the patch both used to say re-signing made "the features
sitting behind it stop working" — no feature sits behind it. And `Lrox;->b:Z`, listed among the
signature fields and once called `sigcheck_flag`, is not a cached verdict: it is the global
test-environment flag, permanently false on a device, which the check reads only as the value to
return when a digest cannot be computed.

### The methodology note

The static reading of this was that dropping the patch would stop the keyboard starting. Every
individual link in that chain was verified and correct — the construction site, the switch key,
the guard, the absence of try ranges — and the conclusion was still wrong, because whether an
uncaught background throw kills the process was never established and could not be, since the
executor arrives through Dagger.

The lesson is not "check harder". It is that the unresolved question was *identified* and then
reported alongside a confident conclusion that depended on it. A conclusion is only as settled as
the weakest link it rests on, and one device test disposed of an argument no amount of
disassembly could have.
