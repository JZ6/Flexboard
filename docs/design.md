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

**It is the cheapest insertion in the project.** `AccessPointsBar` keeps its real name through R8,
because a layout addresses it as a string. The anchor is a *string literal* —
`config_max_access_points` in the class's `<clinit>` — and R8 renames classes, methods and fields but
never string contents. The `Context` is already a constructor parameter, so no field has to be
resolved and nothing has to be shown assignable. Two scratch registers are needed, against three for
each of the switches that were removed.

The obfuscated field it targets, `->m:I`, is never written down. The patch inserts *before* Gboard's
own `iput` and leaves that instruction to do the write, so a letter that moves onto a different
member cannot be silently patched instead — the failure mode that shipped in `0.0.2-dev.1`.

Two decisions worth naming:

**The fallback is Gboard's own computed value, not a constant.** The preference is read with
whatever the flag path just produced as its default, so an untouched slider leaves the ceiling
exactly where Gboard put it — the patch is a no-op until you move something. An out-of-range value
falls back the same way rather than being clamped into range, because a corrupt or hand-edited
preference should read as "unset" and not as a number nobody chose.

**Flexboard uses its own key rather than Gboard's.** Gboard has `access_points_count_on_bar`, and
riding it would have removed the insertion entirely — the count could then be raised with a single
hardcoded `const`. It was rejected because that key can only *lower* the count below the ceiling,
because Gboard's own "reduce your toolbar icons" flow (`Lmjr;->b`) writes to it and would silently
overwrite the user's choice, and because a value written there outlives the patch.

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
