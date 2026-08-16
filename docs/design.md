# Design notes

Why Flexboard behaves the way it does. The [README](../README.md) says what it does; this is the
reasoning behind the choices a user would otherwise have to guess at.

## Why the three sliders do not default to Gboard's values

**Swipe length, 36% rather than 100%.** Gboard's stock distance assumes a thumb travelling from the
backspace key and back — the whole journey this patch exists to remove. A gesture that starts under
your thumb wants a shorter one.

**Max words per swipe, 1 rather than no limit.** One word per swipe makes each deletion deliberate
rather than a run that then has to be swiped back.

**Hold delay, 0 ms rather than 200 ms.** Gboard waits before its delete swipe engages, which is what
makes it feel like a press-and-drag rather than a flick. Zero is not an improvement on that so much
as continuity: it is what Flexboard did before the delay was adjustable at all, so existing installs
keep the feel they had.

All three are sliders precisely because that is a preference and not a fact — 100%, 10 and 200 ms
put every one of them back to Gboard's own.

Neither could be changed by editing one number, because each of those two numbers was doing two jobs
— the default *and* a control-flow sentinel, at which the scaling or the clamp is skipped. Setting
the default to the sentinel value would have made the chosen setting the one setting that does
nothing. They are now four constants rather than two; `ScrubTuningPatch.kt` documents each.

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

Its switch is deliberately outside the master switch's group. Gboard fills the same undo slot when
you swipe on the backspace key, so undo stays meaningful even with swipe-anywhere off.

## Why flick keys has no runtime switch

It writes Gboard's own preference exactly once, only if it has never been set, so it behaves as a
default rather than something forced. A runtime switch would therefore do nothing after the first
run — and a control that silently stops working is worse than no control. Unticking the patch in
Morphe is the honest way to turn it off.

The same reasoning does not apply to the glide settings, which are rewritten on every start
precisely because Gboard must not be left able to break the gesture; they follow the master switch
instead.

## Why the glide rows are greyed out rather than left alone

Glide delete is written on, glide typing off, at every start. A user who changed either would see
the change appear to take and then quietly revert — the worst of the three options. Greying them out
while the gesture is on states the constraint instead of hiding it, and the switch that lifts it
sits directly above them in that same Gboard screen, so the way out is where the problem is.
[`gboard-settings-ui.md`](gboard-settings-ui.md) covers how the rows are reached and disabled.
