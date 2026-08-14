# Flexboard

Swipe anywhere on Gboard to delete the previous word.

Gboard's only word-delete is a swipe on the backspace key, which means moving your thumb to the
corner and back for every correction. Flexboard puts that gesture wherever your thumb already is.

It is a [Morphe](https://github.com/MorpheApp) patch bundle for Gboard
`17.7.7.932364120-release-arm64-v8a`, and only that build.

> **Status: pre-release.** This is a rebuild on the Morphe patches template, and releases are
> currently published to the **pre-release** channel only — enable pre-releases on the patch
> source in Morphe to see them. The earlier implementation is preserved at
> [JZ6/Flexboard0](https://github.com/JZ6/Flexboard0).

## Install

Click here to add Flexboard to Morphe: https://morphe.software/add-source?github=JZ6/Flexboard

Or manually add this repository URL as a patch source in Morphe: https://github.com/JZ6/Flexboard

Patch Gboard from that source in Morphe and install the result. The patched build installs as a
separate app rather than replacing the Gboard you already have, so once it is on the device:

1. Enable the **Patched Gboard** in Android's on-screen keyboard settings.
2. Switch to it with the keyboard picker.

Both keyboards stay installed, so you can switch back whenever you like.

## Patches

<!-- PATCHES_START EXPANDED -->

<!-- Do not modify this section by hand. The patch list is generated when release.yml creates a
     new release.

     If you wish for the patches list to be collapsed, then remove the word 'EXPANDED' from the
     comment tag above.

     Anything between the PATCHES_START and PATCHES_END markers is overwritten on every release,
     so keep hand-written prose outside them. -->

#### A list of your patches will automatically be shown here after your first patches release is created.

<!-- PATCHES_END -->

## How it works, and what it changes

Flexboard does not add a gesture. Gboard already has one — swiping on the backspace key deletes
the previous word — and everything about it, including dragging back to restore, works across the
whole keyboard once started. The only thing keeping it to the backspace key is a single check on
which key your finger landed on. Flexboard removes that check for the delete gesture, and leaves
the spacebar cursor-drag alone.

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

### Settings

Gboard's settings gain a **Flexboard** entry that opens a screen with a switch and three sliders:

| Setting | Default | What it does |
|---|---|---|
| **Swipe anywhere** | on | The master switch. Off puts Gboard back as it shipped — see below. Also appears in Gboard's own **Glide typing** screen, as the same setting rather than a copy. |
| **Swipe length** | 100% | How far to swipe per deleted word, as a percent of Gboard's own distance. Lower deletes more words for the same swipe. |
| **Max words per swipe** | 10 | The most words one swipe can delete. Set it to **1** to delete a single word however far you swipe; 10 means no limit. Swiping back still restores. |
| **Hold delay** | 0 ms | How long the swipe must be held before it starts deleting. Gboard's own delete swipe uses 200 ms, which is what makes it feel like a press-and-drag rather than a flick. |

All four are read out of Gboard's own preference store, so there is no separate settings app and
nothing to keep in sync. The defaults reproduce the behaviour Flexboard shipped before they existed.

**Turning the switch off does not turn the delete swipe off** — it hands it back to Gboard. The
swipe works on the backspace key again and nowhere else, at Gboard's own distance and its 200 ms
hold, and the three sliders grey out. That is the difference between the switch and unticking the
patch in Morphe: the switch changes behaviour, unticking it means the code is never installed.

Two things it deliberately does not do. **Glide typing stays off** — Flexboard turned it off and
does not turn it back on, so tick it back on in Gboard's settings if you want it; the switch does
stop Flexboard rewriting it, so it will stay on once you do. And changes are not instant: the
gesture picks up the new setting the next time the keyboard is opened, and the preference writes
stop at the next time Gboard's process starts.

### Flick keys for symbols

Gboard can already enter a key's hinted symbol when you pull down on it — **Flick keys to enter
symbols**, in its Preferences screen — and ships it off. Flexboard turns it on.

It is written **once**, only if you have never set it, so it behaves as a default rather than
something forced: turn it off in Gboard's settings and it stays off.

One quirk worth knowing. Gboard's own settings row for it depends on **Touch & hold keys for
numbers**, so while that is off the flick row shows as on but greyed out — the feature works, you
just cannot toggle it from there. Enabling "Touch & hold keys for numbers" un-greys it. Flexboard
deliberately does not change that setting for you, since nothing at runtime needs it.

It is a separate patch, so it can be unticked in Morphe if you do not want it.

## Roadmap

single word swipe delete

gesture down on a to select all

add toggle to turn on swipe to delete or off

flick up to undo autocorrect 

auto turn delete swipe on, and then grey it out

add select all copy paste hotkeys

Configurable Swipe distance

Make the configuration more user intuitive, like short medium long instead of ms or px

Hot keys as new tool bar objects

increased tool bar size fit more buttons


## Development

Building, testing and releasing: [`docs/development.md`](docs/development.md), which also indexes
the reverse-engineering notes — the obfuscated names this depends on, how the glide typing setting
was found, and the Gboard internals the rebuild is aiming at.

## Licence and attribution

GPL-3.0. See [`LICENSE`](LICENSE).

Built from the [Morphe patches template](https://github.com/morpheapp/morphe-patches-template).
[`NOTICE`](NOTICE) carries Morphe's naming terms.

Gboard is a trademark of Google LLC. This project is not affiliated with or endorsed by Google.
