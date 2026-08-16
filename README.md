# Flexboard

Swipe anywhere on Gboard to delete the previous word.

Gboard's only word-delete is a swipe on the backspace key, which means moving your thumb to the
corner and back for every correction. Flexboard puts that gesture wherever your thumb already is.

This is a [Morphe](https://github.com/MorpheApp) patch bundle for Gboard
`17.7.7.932364120-release-arm64-v8a`

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
> **[v0.0.1-dev.1](https://github.com/JZ6/Flexboard/releases/tag/v0.0.1-dev.1)**&nbsp;&nbsp;•&nbsp;&nbsp;`dev`&nbsp;&nbsp;•&nbsp;&nbsp;5 patches total
<details open>
<summary>📦 Gboard&nbsp;&nbsp;•&nbsp;&nbsp;5 patches</summary>
<br>

**🎯 Supported versions:**

| 17.7.7.932364120-release-arm64-v8a |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Bypass Gboard Signature](#bypass-gboard-signature) | Bypass Gboard's signature whitelist checks and force them to pass. |  |
| [Flick Keys for Symbols](#flick-keys-for-symbols) | Turn on Gboard's "Flick keys to enter symbols" — pull down on a key to enter the symbol hinted in its corner. Written once as a default, so it can still be turned off in Gboard's own settings. |  |
| [Install as Gboard Clone](#install-as-gboard-clone) | Rename the package to dev.jz6.com.google.android.inputmethod.latin so the patched build installs alongside the official Gboard instead of replacing it. |  |
| [Swipe Right to Undo](#swipe-right-to-undo) | Swipe right after deleting to put the words back. Uses Gboard's own undo, which already records what a delete swipe removed. |  |
| [Swipe to Delete](#swipe-to-delete) | Swipe left anywhere on the keyboard to delete the previous word, and swipe right to restore it. Uses Gboard's own word-scrub engine, so it behaves exactly like swiping on the backspace key already does — only it can start anywhere. |  |

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

Gboard's settings gain a **Flexboard** entry that opens a screen with two switches and three
sliders:

| Setting | Default | What it does |
|---|---|---|
| **Swipe anywhere** | on | The master switch. Off puts Gboard back as it shipped — see below. Also appears in Gboard's own **Glide typing** screen, as the same setting rather than a copy. |
| **Swipe length** | 36% | How far to swipe per deleted word, as a percent of Gboard's own distance. Lower deletes more words for the same swipe. |
| **Max words per swipe** | 1 | The most words one swipe can delete. At 1 a swipe deletes a single word however far it travels; 10 means no limit. Swiping back still restores. |
| **Hold delay** | 0 ms | How long the swipe must be held before it starts deleting. Gboard's own delete swipe uses 200 ms, which is what makes it feel like a press-and-drag rather than a flick. |
| **Swipe right to undo** | on | Whether a rightward swipe after a delete puts the words back. Off leaves it doing nothing, as in stock Gboard. Independent of the master switch — see below. |

All five are read out of Gboard's own preference store, so there is no separate settings app and
nothing to keep in sync. Setting the three sliders to 100%, 10 and 200 ms puts each of them back to
Gboard's own value; why they do not start there is in [`docs/design.md`](docs/design.md).

**Turning the switch off does not turn the delete swipe off** — it hands it back to Gboard. The
swipe works on the backspace key again and nowhere else, at Gboard's own distance and its 200 ms
hold, and the three sliders grey out. That is the difference between the switch and unticking the
patch in Morphe: the switch changes behaviour, unticking it means the code is never installed.

Two things it deliberately does not do. **Glide typing stays off** — Flexboard turned it off and
does not turn it back on, so tick it back on in Gboard's settings if you want it; the switch does
stop Flexboard rewriting it, so it will stay on once you do. And changes are not instant: the
gesture picks up the new setting the next time the keyboard is opened, and the preference writes
stop at the next time Gboard's process starts.

## Swipe right to undo

Swiping right *during* a delete puts the words back — that is Gboard's own behaviour, and it stops
the moment you lift your finger. Swiping right **after** you have lifted now undoes the delete too.

Two limits worth knowing, both inherited rather than chosen:

- **It only works as the very next thing you do.** Gboard keeps one deleted phrase and clears it on
  almost any other input, so typing a character after the delete loses the undo.
- **One level.** Undo once and the slot is empty; a second right-swipe does nothing.

Its switch is deliberately **not** greyed out by the master switch: Gboard fills the same undo slot
when you swipe on the backspace key, so undo keeps working even with swipe-anywhere off.

## Flick keys for symbols

Gboard can already enter a key's hinted symbol when you pull down on it — **Flick keys to enter
symbols**, in its Preferences screen — and ships it off. Flexboard turns it on.

It is written **once**, only if you have never set it, so it behaves as a default rather than
something forced: turn it off in Gboard's settings and it stays off.

One quirk worth knowing. Gboard's own settings row for it depends on **Touch & hold keys for
numbers**, so while that is off the flick row shows as on but greyed out — the feature works, you
just cannot toggle it from there. Enabling "Touch & hold keys for numbers" un-greys it. Flexboard
deliberately does not change that setting for you, since nothing at runtime needs it.

## Install as Gboard clone

Renames the package so the patched build installs beside the official Gboard rather than replacing
it. Both keyboards stay in the picker, which is why the install steps above end with enabling and
choosing the new one.

Untick it and the patched build replaces the Gboard you already have.

## Bypass Gboard signature

Gboard checks its own signing certificate against a list baked into the app. A patched build is
re-signed, so that check fails and the features sitting behind it stop working.

Nothing about this one is visible when it works — it exists so that re-signing does not silently
switch parts of Gboard off.

## Development

Building, testing and releasing: [`docs/development.md`](docs/development.md), which also indexes
the [design notes](docs/design.md), the [roadmap](docs/roadmap.md) and the reverse-engineering notes
behind all of the above.

## Licence and attribution

GPL-3.0. See [`LICENSE`](LICENSE).

Built from the [Morphe patches template](https://github.com/morpheapp/morphe-patches-template).
[`NOTICE`](NOTICE) carries Morphe's naming terms.

Gboard is a trademark of Google LLC. This project is not affiliated with or endorsed by Google.
