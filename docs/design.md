# Design notes

Why Flexboard behaves the way it does. The [README](../README.md) says what it does; this is the
reasoning behind the choices a user would otherwise have to guess at.

## What the three swipe sliders default to, and why

**Swipe length, 100% — Gboard's own.** This shipped at 36% up to `1.1.0-dev.1`, reasoning that
Gboard's distance assumes a thumb travelling from the backspace key and back, which is the whole
journey this patch exists to remove, so a gesture starting under your thumb wants a shorter one.
The reasoning is sound and the number was too aggressive: at 36% an ordinary swipe crosses three or
four thresholds, and the word cap is then doing the work of hiding it. Shipping Gboard's own
distance makes the gesture behave exactly like the one people already know, and leaves shortening it
to anyone who wants that.

**Max words per swipe, 1 rather than no limit.** One word per swipe makes each deletion deliberate
rather than a run that then has to be swiped back.

**Hold delay, 0 ms rather than 200 ms.** Gboard waits before its delete swipe engages, which is what
makes it feel like a press-and-drag rather than a flick. Zero is not an improvement on that so much
as continuity: it is what Flexboard did before the delay was adjustable at all, so existing installs
keep the feel they had.

All three are sliders precisely because that is a preference and not a fact. Only two now differ
from Gboard's own, and 10 and 200 ms put those back.

**Neither of those two defaults could be moved by editing one number**, because each number was
doing two jobs — the default *and* a control-flow sentinel, at which the scaling or the clamp is
skipped. Setting a default to its sentinel value makes the chosen setting the one setting that does
nothing. They are four constants rather than two for that reason, and `ScrubTuningPatch.kt`
documents each.

Swipe length is the worked example, in both directions. Moving it off 100 is what forced the split
in the first place; moving it back to 100 has made `STEP_SCALE_DEFAULT` and `STEP_SCALE_IDENTITY`
hold the same number again, which looks redundant and is not. Collapsing them would silently
re-arm the trap the next time the default moves.

## Why the toolbar count is a slider when hold delay nearly was not

Every preference this project reads costs the same thing — an insertion, and registers proved dead
against each Gboard build — so the bar a new config has to clear is high. See below for the three
that failed to clear it. The toolbar count clears it on both counts a config can:

**There is no value that is right everywhere.** How many icons fit depends on how wide the screen
is, because the bar divides its width by the number of items
(`AccessPointsBar->K(II)I` gives each `min((width + 2·padding)/(n + 1), width/n)`). Ten is
comfortable on a tablet and cramped on a small phone. That is not true of the hold delay, where one
number was right and got hardcoded.

**Both insertions are cheap.** The one that does the work is entered with three dead locals and
finds the preference store already sitting in a field on the receiver, so it needs no `Context` and
no liveness argument at all. The second is the cheapest in the project on the older measure:
`AccessPointsBar` keeps its real name through R8 because a layout addresses it as a string, the
anchor is a *string literal* (`config_max_access_points` in the class's `<clinit>`, and R8 renames
classes, methods and fields but never string contents), the `Context` is already a constructor
parameter, and two scratch registers suffice against three for each of the switches that were
removed.

Neither obfuscated member is written down. `->m:I` is never named — the patch inserts *before*
Gboard's own `iput` and leaves that instruction to do the write, so a letter that moves onto a
different member cannot be silently patched instead, the failure mode that shipped in `0.0.2-dev.1`.
The preference store is read back out of the very method being patched, as the one field it touches
of that type.

Three decisions worth naming:

**The fallback is Gboard's own behaviour, not a constant.** Neither insertion substitutes a number
when the slider is untouched. The capacity one reads the preference with whatever the flag path just
computed as its default; the count one runs *before* Gboard has computed anything and simply falls
through into the stock body. So an unset value is not a value chosen to resemble Gboard's — it is
Gboard's own code running with nothing done to it. An out-of-range value falls back the same way
rather than being clamped into range, because a corrupt or hand-edited preference should read as
"unset" and not as a number nobody chose.

**Flexboard uses its own key rather than Gboard's.** Gboard has `access_points_count_on_bar`, and
riding it would have removed an insertion — but that key can only *lower* the count, Gboard's own
"reduce your toolbar icons" flow (`Lmjr;->b`) writes to it and would silently overwrite the user's
choice, and a value written there outlives the patch.

**The slider outranks both of Gboard's own limits.** Overriding at the top of the count method skips
`access_points_count_on_bar` and the reduced mode that forces three icons. Both exist to lower the
count, and a user who has just moved this slider has said what they want more recently and more
explicitly than either.

### Two numbers, and the one that is not the count

The first build of this patched the wrong one, so the distinction is worth stating plainly.

The bar holds a **capacity**, `AccessPointsBar->m:I`, computed once in its constructor from a
Phenotype flag clamped to `[3, 8]`. It reads like the count and is not: the bar renders whatever
list it is handed — `m(List)` sets its child count from `list.size()` with no clamp anywhere — and
the list is cut to length elsewhere, by a method Gboard's own logging calls `definedCountOnBar`. The
capacity is only that method's *argument*, and it then goes through two gates that can each discard
it: `min(access_points_count_on_bar, capacity)` whenever Gboard's preference is set, and a reduced
mode returning a flat three.

So raising the capacity raised nothing. It still gets raised, because eight other reads of `m:I` all
ask *is the bar full?* — and one of them evicts the last child before inserting when the answer is
yes. Both numbers move together; only one of them is the feature.

### The methodology note

`1.1.0-dev.1` was diagnosed from the expand chevron failing to appear alongside the count not
moving: `T()` swaps the chevron in whenever the child count differs from the capacity, so a raised
capacity should have produced one even with no extra icons to show. Its absence was read as proof
that the write never landed.

**The inference was void.** `T()` opens with an early return on a flag set only by the
temporary-access-point flows, so in ordinary use it does nothing at all and could not have produced
a chevron either way. The chevron seen in normal operation is an ordinary access point in the list,
marked `expand_label_on_top_bar`. A second suspect recorded at the time — that the constructor might
not re-run because Android caches keyboard views — had no evidence behind it either, and was carried
forward for a day as though it did.

What is uncomfortable is that the real cause needed no new disassembly. `gboard-bindings.md` already
said, in the row above the one being patched, that the preference `Lmjv;->a` applies *can only lower
the count*. The fact was written down, in the right file, and simply not carried through to the
conclusion. The bypass patch's note in this file says a conclusion is only as settled as its weakest
link; this is the other half of it — a conclusion can also be wrong because a link nobody thought to
question was already documented as broken.

## Why the select-all button carries a Runnable rather than a keycode

Every other button on Gboard's toolbar works by emitting a keycode, so the obvious build is a button
emitting `TEXT_EDITING_SELECT_ALL`. That button would render, press, highlight, and do nothing.

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
an instruction, and the code calls `performContextMenuAction(selectAll)` on the input connection,
which is what Gboard's own panel does.

**Nothing is published or registered**, which is the part worth recording because a lot of work went
into the route that is not used. Gboard's access-point providers build notification objects and
store them in fields, and what later publishes them was never established — that question blocked
the feature for a while. It turned out to be avoidable: the method that splits the ordered list into
"on the bar" and "in the overflow" takes that list **as a parameter** and only reads it. Substituting
a longer list at entry adds a button, and the whole notification machinery is bypassed.

**The builder's setters are derived, not named.** Five of them share the signature `(I)V`. That is
exactly the shape behind this project's worst bug — `AbstractIme->s` was the undo re-commit on
17.7.7, and on 18 a *different* method inherited `s` with the same signature. So the setters are
read out of Gboard's own text-editing seed method, identified by the value each one is handed: the
one given a drawable id is the icon, the two given string ids are the label and the content
description. `preflight.py` asserts that `(I)V` is still ambiguous there — if it ever stops being,
this machinery is over-built and can go.

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
