# Flexboard

Adding swipe gestures to Gboard.

Swipe left anywhere on Gboard to delete the previous word.

Swipe right to undo.

More swipes coming :)

https://github.com/user-attachments/assets/d5935fc0-8527-466e-9bdc-1f4c60a52617

This is a [Morphe](https://github.com/MorpheApp) patch bundle for Gboard
`18.0.3.954559732-release-arm64-v8a`

## Install

<p align="center">
  <a href="https://morphe.software/add-source?github=JZ6/Flexboard" title="Add Flexboard as a patch source in Morphe">
    <img src="docs/assets/add-to-morphe.svg" alt="Add Flexboard to Morphe" width="320"/>
  </a>
</p>

Or add the repository URL by hand as a patch source in Morphe:
`https://github.com/JZ6/Flexboard`

Patch Gboard from that source in Morphe and install the result. The patched build installs as a
separate app rather than replacing the Gboard you already have, so once it is on the device:

1. Enable the **Patched Gboard** in Android's on-screen keyboard settings.
2. Switch to it with the keyboard picker.

Both keyboards stay installed, so you can switch back whenever you like.

## Patches

<!-- PATCHES_START EXPANDED -->
> **[v2.0.0-dev.2](https://github.com/JZ6/Flexboard/releases/tag/v2.0.0-dev.2)**&nbsp;&nbsp;•&nbsp;&nbsp;`dev`&nbsp;&nbsp;•&nbsp;&nbsp;9 patches total
<details open>
<summary>📦 Gboard&nbsp;&nbsp;•&nbsp;&nbsp;9 patches</summary>
<br>

**🎯 Supported versions:**

| 18.0.3.954559732-release-arm64-v8a |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Bypass Gboard Signature](#bypass-gboard-signature) | Bypass Gboard's signature whitelist checks and force them to pass. |  |
| [Grammar Check Row](#grammar-check-row) | Force the grammar check settings row to appear. The Phenotype flag that governs it never syncs on a patched build, so its compiled-in default (off) sticks. |  |
| [Install as Gboard Clone](#install-as-gboard-clone) | Rename the package to dev.jz6.com.google.android.inputmethod.latin so the patched build installs alongside the official Gboard instead of replacing it. |  |
| [Suggested Settings](#suggested-settings) | Turns on flick keys for symbols, touch & hold keys for numbers, suggestion strip, grammar check and smart replies, and turns off block offensive words and word suggestions. Written once as defaults, so each can still be changed in Gboard's own settings. |  |
| [Swipe Right to Undo](#swipe-right-to-undo) | Swipe right after deleting to put the words back. Uses Gboard's own undo, which already records what a delete swipe removed. |  |
| [Swipe to Delete](#swipe-to-delete) | Swipe left anywhere on the keyboard to delete the previous word, and swipe right to restore it. Uses Gboard's own word-scrub engine, so it behaves exactly like swiping on the backspace key already does — only it can start anywhere. |  |
| [Toolbar Buttons](#toolbar-buttons) | Add Select all, Copy and Paste buttons to the toolbar above the keyboard, so each is one tap instead of opening Gboard's text editing panel first. Registered natively, so drag-to-reorder through the toolbar customize page persists. |  |
| [Toolbar Hotkeys](#toolbar-hotkeys) | Adds twelve configurable hotkey slots to Gboard's toolbar — each commits a text of your choice on tap. A slot appears when its text is set; when cleared it hides at the next toolbar rebuild (rotate, switch IME, or restart — there's no mid-session un-register). Text and icon edits apply on the next keyboard open. |  |
| [Toolbar Native Test](#toolbar-native-test) | Add a 'Test' button to the toolbar through Gboard's own access-point registry so drag reorder and persistence work natively. Writes 'test' at the cursor on tap. Architectural proof-of-concept; off by default. |  |

</details>

<!-- PATCHES_END -->

Each has its own section below, and each can be unticked in Morphe if you would rather it were never
installed.

## Swipe to delete

Flexboard does not add a gesture. Gboard already has one — swiping on the backspace key deletes the
previous word — and everything about it, including dragging back to restore, works across the whole
keyboard once started. The only thing keeping it to the backspace key is a single check on which key
your finger landed on. Flexboard removes that check for the delete gesture, and leaves the spacebar
cursor-drag alone.

So the feel, the thresholds and the restore behaviour are all Gboard's own.

It also changes two of Gboard's settings at startup, because the gesture cannot work otherwise:

| Setting | Set to | Why |
|---|---|---|
| **Glide delete** | on | The gesture is Gboard's; with this off it is never attached at all |
| **Glide typing** | off | A leftward drag across the letters is also a glide input, so the two cannot both be live |

Both are in Gboard's **Glide typing** screen, and because both are written on every start, both are
**greyed out** while the gesture is on — otherwise changing either would appear to work and quietly
revert at the next start. The switch that hands them back sits directly above them in that same
screen, so the way out is where the problem is. It is the same setting as the switch on Flexboard's
own screen, not a copy.

Removing Flexboard leaves glide typing off — tick it back on in Gboard's own settings.

## Settings

Gboard's settings gain a **Flexboard** entry that opens a screen of sliders. Three of them shape
the swipe-anywhere gesture; the backspace key keeps Gboard's own behaviour, see below.

| Setting | Default | What it does |
|---|---|---|
| **Swipe length** | 60% | How far to swipe per deleted word, as a percent of Gboard's own distance. Lower deletes more words for the same swipe; 100% is Gboard's own distance. |
| **Max words per swipe** | 1 | The most words one swipe can delete. At 1 a swipe deletes a single word however far it travels; 10 means no limit. Swiping back still restores. |
| **Hold delay** | 0 ms | How long the swipe must be held before it starts deleting. Gboard's own delete swipe uses 200 ms, which is what makes it feel like a press-and-drag rather than a flick. |

The screen also carries **Icons on the toolbar** and **Icons when unfolded**, which belong to the
Bigger Toolbar patch and are described [further down](#bigger-toolbar), and six **Hotkeys** fields
belonging to [Toolbar Buttons](#toolbar-buttons).

Every value is read out of Gboard's own preference store, so there is no separate settings app and
nothing to keep in sync. All three now start somewhere other than Gboard's own behaviour; 100%, 10
and 200 ms put them back, and why they do not start there is in
[`docs/design.md`](docs/design.md).

The starting values are written into the store the first time the patched app runs, rather than
being numbers inside the patch. So they behave as defaults for a fresh install, and a later update
can pick different ones without moving settings you have already got used to.

Changes are not instant: a new setting is picked up the next time the keyboard is opened. Hotkeys
are half an exception — *editing* a snippet takes effect immediately, because the text is read when
the button is tapped, but its name on the toolbar and whether the button exists at all are decided
when the bar is built.

The screen shows every section whether or not you ticked the patch it belongs to — it is one merged
class and cannot tell which patches you chose. A slider for a patch you did not apply moves and
stores and does nothing.

### The backspace key still behaves the way Gboard built it

The sliders above apply to swipes that start **anywhere on the letters**. A swipe that starts on the
**backspace key** — the one place Gboard's own word-delete has always worked — keeps Gboard's
distance per word and is not capped, so it still deletes as many words as you drag across.

That is deliberate. A one-word cap and a short swipe are right for a gesture you trigger by accident
sometimes; they are wrong for the deliberate press-and-drag on backspace that people already have
muscle memory for. Flexboard adds a gesture rather than replacing one.

It works because Gboard keeps the key a gesture started on for the gesture's whole life, so the
engine can still tell the two apart even though Flexboard widened the gate that used to distinguish
them. The derivation is in [`docs/motion-event-handlers.md`](docs/motion-event-handlers.md).

Hold delay is the exception: it is decided before a gesture activates, and is shared. At the default
of 0 ms neither swipe has a hold.

### There is no on/off switch, and that is deliberate

Flexboard used to carry a master switch, and a separate one for undo. Both are gone. **Untick the
patch in Morphe instead** — that is the off switch.

They were removed because of what they cost rather than what they did. Reading a preference from
patched bytecode means finding registers that are provably dead at the point the read is inserted,
and R8 re-runs register allocation on every Gboard release — so each switch was a fresh derivation
to redo, and a fresh chance to get one wrong, every single version bump. Between them the two
switches accounted for most of the work in the 17.7.7 → 18.0.3 port. The sliders stay because their
values genuinely vary by thumb and screen; an on/off switch duplicates something Morphe already
does properly.

**One consequence is user-visible: glide typing is off for as long as Swipe to Delete is applied.**
A leftward drag across the letters is also a glide input, so the two cannot both be live. Flexboard
forces glide typing off at every app start and greys out the two affected rows in Gboard's **Glide
typing** screen, with a note saying what is doing it. Getting glide typing back means re-patching
without Swipe to Delete.

## Swipe right to undo

Swiping right *during* a delete puts the words back — that is Gboard's own behaviour, and it stops
the moment you lift your finger. Swiping right **after** you have lifted now undoes the delete too.

Two limits worth knowing, both inherited rather than chosen:

- **It only works as the very next thing you do.** Gboard keeps one deleted phrase and clears it on
  almost any other input, so typing a character after the delete loses the undo.
- **One level.** Undo once and the slot is empty; a second right-swipe does nothing.

It is always on when the patch is applied. Swiping right after a delete did nothing at all in stock
Gboard, so nothing is being taken away by giving it a meaning — and Gboard fills the same undo slot
when you swipe on the backspace key, so it works there too.

## Toolbar buttons

Adds **Select all**, **Copy** and **Paste** buttons to the toolbar above the keyboard, and six
**hotkeys** that type a string you choose. One tap each, on whatever you are typing into.

### Select all, copy and paste

Gboard can already do all three, behind its **Text editing** toolbar button — open that panel, then
tap the one you want. These are the same actions without the panel.

They take the first three slots on the toolbar, which pushes whatever used to be last into the
overflow menu behind the chevron. Long-press the toolbar to reorder them like any other button, or
raise the icon count with [Bigger Toolbar](#bigger-toolbar) so nothing has to move.

The labels are Gboard's own, so they are already translated wherever Gboard is. The icons are
Material's — the select-all marquee, and the familiar copy and paste marks. Gboard ships all three
and draws none of them, because its text editing panel spells the actions out in words rather than
using icons; that is why Select all first shipped borrowing an unrelated icon. Flexboard still adds
no images of its own.

### Hotkeys

Six more buttons, each typing a string you set under **Hotkeys** in Flexboard's settings — an email
address, a signature, "brb", whatever you type often enough to resent typing.

**A slot you have not filled in makes no button.** Fresh out of the box there are no hotkeys at all;
fill one in and its icon appears on the toolbar, clear the field and the button goes away again.
That is the on/off switch, and it is per-button.

Each button is named by your own text, so they are easy to tell apart when you long-press to
reorder the toolbar. On the bar itself there is only room for the icon, and here the icons are
arbitrary — a star, a sparkle, scissors, a ticked box, a ring, a share mark. They have to be:
Gboard bundles 29 Material shapes and not one of them is a digit, and Flexboard ships no images of
its own. So the settings screen draws each slot's real icon beside the field that fills it, which
is the moment you actually need to know which is which.

Long text is fine. The whole of it gets typed; only the first line, cut short, becomes the name.

**Nine buttons is more than the bar holds.** With the three text actions plus a few hotkeys,
whatever used to sit at the end of your toolbar moves into the overflow menu behind the chevron.
Raise the icon count with [Bigger Toolbar](#bigger-toolbar) and they all fit.

## Flick keys for symbols

Gboard can already enter a key's hinted symbol when you pull down on it — **Flick keys to enter
symbols**, in its Preferences screen — and ships it off. Flexboard turns it on.

It is written **once**, only if you have never set it, so it behaves as a default rather than
something forced: turn it off in Gboard's settings and it stays off.

One quirk worth knowing. Gboard's own settings row for it depends on **Touch & hold keys for
numbers**, so while that is off the flick row shows as on but greyed out — the feature works, you
just cannot toggle it from there. Enabling "Touch & hold keys for numbers" un-greys it. Flexboard
deliberately does not change that setting for you, since nothing at runtime needs it.

## Bigger toolbar

The row of icons above the keyboard — Gboard calls it the access points bar — holds five, and
everything past that sits in the overflow menu behind the chevron. This makes that number a slider,
from 3 to 12, under **Toolbar** in Flexboard's settings.

Five is not a layout constant. Gboard works out how many icons the bar gets each time the list is
rebuilt, starting from a ceiling it computes from a server-side flag and then lowering it — for its
own count preference if you have one set, and to three if it has decided your screen is short on
room. Flexboard replaces the answer rather than the starting point, so the slider is the last word
on it.

**It starts at 6, and at 12 on the inner screen of a fold.** Those are written into Gboard's
preference store the first time the patched app runs, rather than being numbers baked into the
patch — which means an update can ship different starting values for new installs without moving a
toolbar you have already got used to. Move a slider and it is yours from then on.

A value outside 3–12 is ignored rather than forced into range, and the bar falls back to whatever
Gboard would have done.

**Icons get narrower, not smaller in number.** The bar divides its width by the number of items, so
at 10 they are about half the width they are at 5, and at 12 narrower still. Eight is as far as
Google's own layout has been built against; past that you are the first person testing it. Nothing
clips or crashes — the icons just get tight, and on a narrow phone 12 will be very tight indeed.

Drag-to-reorder and long-press-to-customise keep working at every setting; this only changes where
the line between the bar and the overflow menu falls.

**On a foldable, the two screens keep their own counts.** Gboard already works this way — the inner
screen is wider and fits more — so Flexboard's settings carry a second slider, **Icons when
unfolded**, that applies only while the phone is open. It starts at 12 against the main slider's 6,
and each owns its screen: changing one does not move the other. On anything that does not fold the
second slider does nothing.

This shipped once before, in `1.1.0-dev.1`, and did nothing — it moved the ceiling rather than the
count. If you tried it then, it is a different patch now.

## Install as Gboard clone

Renames the package so the patched build installs beside the official Gboard rather than replacing
it. Both keyboards stay in the picker, which is why the install steps above end with enabling and
choosing the new one.

Untick it and the patched build replaces the Gboard you already have.

## Bypass Gboard signature

Gboard hashes its own signing certificate and compares it against a list baked into the app. A
patched build is re-signed, so that check fails. Flexboard forces it to pass.

Nothing about this one is visible either way, and it turns out that nothing is behind it. The
check gates no feature: its only real caller does nothing except the check itself, and throws if
it fails. Patched **without** this one the keyboard still opens — the exception lands on a
background thread during startup and everything carries on. So it removes a startup crash rather
than restoring anything.

It is kept anyway, because an exception on every cold start is worth silencing even when it is
survivable, and because assuming it stays harmless on every device is a worse bet than simply
patching it out.

## Development

Building, testing and releasing: [`docs/development.md`](docs/development.md), which also indexes
the [design notes](docs/design.md), the [roadmap](docs/roadmap.md) and the reverse-engineering notes
behind all of the above.

## Licence and attribution

GPL-3.0. See [`LICENSE`](LICENSE).

Built from the [Morphe patches template](https://github.com/morpheapp/morphe-patches-template).
[`NOTICE`](NOTICE) carries Morphe's naming terms.

Gboard is a trademark of Google LLC. This project is not affiliated with or endorsed by Google.
