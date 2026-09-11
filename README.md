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
> **[v2.3.1-dev.5](https://github.com/JZ6/Flexboard/releases/tag/v2.3.1-dev.5)**&nbsp;&nbsp;•&nbsp;&nbsp;`dev`&nbsp;&nbsp;•&nbsp;&nbsp;15 patches total
<details open>
<summary>📦 Gboard&nbsp;&nbsp;•&nbsp;&nbsp;15 patches</summary>
<br>

**🎯 Supported versions:**

| 18.0.3.954559732-release-arm64-v8a |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Bigger Toolbar](#bigger-toolbar) | Raises how many icons Gboard's toolbar can hold — five on a stock build — to 12, so Flexboard's hotkeys and text action buttons fit alongside Gboard's own. How many actually show stays yours, set by dragging them in Gboard's toolbar settings. Force-stop Gboard afterwards: a cached keyboard view can go on showing the old capacity. |  |
| [Bypass Gboard Signature](#bypass-gboard-signature) | Bypass Gboard's signature whitelist checks and force them to pass. |  |
| Enable Rambler | Exposes Google Rambler — Gboard's agentic dictation, which rewrites what you say into composed text — as a choice in Voice settings. It is not switched on for you: the feature uses a Google server, has its own quota and asks for consent, so picking it is left to you. Off by default and not yet confirmed working on a device. |  |
| [Hidden Features](#hidden-features) | Turns on two finished Gboard features that a resigned build can never receive: grammar check, and a close control on the chips Gboard offers unprompted. Their flags are delivered per app signature, so resigning the APK means they never arrive and stay off. Both are confirmed working on a device; five other flags were tried and dropped. |  |
| Hidden Features (unconfirmed) | Turns on four finished Gboard features that a resigned build can never receive: Emoji Kitchen browse, the custom sticker tab, offline translation, and search in Gboard's settings. Off by default because none of the four has been seen working on a device — the flags flip, but each also depends on data Google only sends to an unpatched install. Safe to try: none of them crashes. |  |
| [Install as Gboard Clone](#install-as-gboard-clone) | Rename the package to dev.jz6.com.google.android.inputmethod.latin so the patched build installs alongside the official Gboard instead of replacing it. |  |
| Modern keypress haptics | Uses Android's haptic primitives for keypresses — the crisp tick the rest of the system uses — instead of a plain buzz. Gboard has the code and disables it with an impossible minimum Android version; this removes that. Devices whose vibrator cannot do primitives are unaffected, because Gboard's own hardware check still runs. Note that it changes what the vibration strength slider means, from milliseconds to intensity. Off by default and not yet confirmed on a device. |  |
| Suggested Settings | Turns on flick keys for symbols, touch & hold keys for numbers, suggestion strip, grammar check and smart replies, and turns off block offensive words and word suggestions. Written once as defaults, so each can still be changed in Gboard's own settings. Grammar check is the switch, not the feature: the row only exists on a resigned build if Hidden Features is applied too. |  |
| [Swipe Left to Delete](#swipe-left-to-delete) | Swipe left anywhere on the keyboard to delete the previous word, and swipe right to restore it. Uses Gboard's own word-scrub engine, so it behaves exactly like swiping on the backspace key already does — only it can start anywhere. |  |
| [Swipe Right to Undo](#swipe-right-to-undo) | Swipe right after deleting to put the words back — the swipe starts on the Delete key, or anywhere when Swipe Left to Delete is also applied. Uses Gboard's own undo, which already records what a delete swipe removed. |  |
| Swipe up diagnostic (temporary) | Diagnostic build only. Swipe up on a key to delete a character, which proves whether the swipe gesture is being detected at all. Do not enable this at the same time as "Swipe up to undo autocorrect" — they attach to the same place and you would get both effects. Off by default, and this patch will be removed once it has answered its question. |  |
| [Swipe up to undo autocorrect](#swipe-up-to-undo-autocorrect) | Swipe up on the keyboard to put back the word an autocorrect replaced. Gboard has the same undo on backspace, behind a setting; this adds a gesture for it and works whether or not that setting is on. A swipe with nothing to undo does nothing. Off by default until it has been confirmed on a device. |  |
| [Text Action Buttons](#text-action-buttons) | Add Select all, Copy and Paste buttons to the toolbar above the keyboard, so each is one tap instead of opening Gboard's text editing panel first. Registered natively, so drag-to-reorder through the toolbar customize page persists. These three share the toolbar with Gboard's own icons and with Toolbar Hotkeys. Bigger Toolbar, which is applied unless you deselect it, raises the ceiling from five to twelve; without it, five is all the bar can hold. |  |
| [Toolbar Hotkeys](#toolbar-hotkeys) | Adds eight configurable hotkey slots to Gboard's toolbar — each commits a text of your choice on tap. A slot appears when its text is set; when cleared it hides at the next toolbar rebuild (rotate, switch IME, or restart — there's no mid-session un-register). Text and icon edits apply on the next keyboard open. The slots share the toolbar with Gboard's own icons: Bigger Toolbar, which is applied unless you deselect it, makes room for twelve, but on a stock ceiling of five not all eight fit. |  |
| Vibration Slider Everywhere | Forces Gboard to show its own vibration strength slider on every device, rather than deferring to the system haptic settings page, so the strength is adjustable instead of being fixed by whichever rollout the device landed in. |  |

</details>

<!-- PATCHES_END -->

Each has its own section below, and each can be unticked in Morphe if you would rather it were never
installed.

## Swipe left to delete

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
| **Max words per swipe** | 1 | The most words one swipe can delete. At 1 a swipe deletes a single word however far it travels; 10 means no limit. Swiping back still restores. |

Swipe length and hold delay are fixed rather than adjustable. The swipe travels Gboard's own
distance per word, and deleting starts immediately instead of after Gboard's 200 ms press-and-hold.
Both were sliders once; [`docs/design.md`](docs/design.md) has why they are not now.

The screen also carries eight **Hotkeys** fields belonging to
[Toolbar Hotkeys](#toolbar-hotkeys).

Every value is read out of Gboard's own preference store, so there is no separate settings app and
nothing to keep in sync.

The starting values are written into the store the first time the patched app runs, rather than
being numbers inside the patch. So they behave as defaults for a fresh install, and a later update
can pick different ones without moving settings you have already got used to.

Changes are not instant: a new setting is picked up the next time the keyboard is opened. Hotkeys
are half an exception — *editing* a snippet takes effect immediately, because the text is read when
the button is tapped, but its name on the toolbar and whether the button exists at all are decided
when the bar is built.

Each section appears only if you ticked the patch it belongs to. The patches register the sections
they own while they run, and the settings screen keeps just those — so there are no rows for
features that are not in your build.

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

**One consequence is user-visible: glide typing is off for as long as Swipe Left to Delete is applied.**
A leftward drag across the letters is also a glide input, so the two cannot both be live. Flexboard
forces glide typing off at every app start and greys out the two affected rows in Gboard's **Glide
typing** screen, with a note saying what is doing it. Getting glide typing back means re-patching
without Swipe Left to Delete.

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

## Swipe up to undo autocorrect

Swipe up on the keyboard to put back the word an autocorrect just replaced.

Gboard can already do this, but only on backspace, and only if you have found **Undo autocorrect
with backspace** in the settings. This adds a gesture for the same thing, and it works whether or
not that setting is on — it does not go through Gboard's arming, and nothing downstream checks.

A swipe with nothing to undo does nothing at all. The gesture is only read on keys that do not
already define an upward flick of their own, so flick-for-symbols keys keep their behaviour, and
the swipe has to be reasonably vertical — twice as much up as sideways — which is what keeps it
apart from the sideways scrub that **Swipe left to delete** uses.

**Off by default.** It has not yet been confirmed on a device, and this project has shipped one
default-on patch that had to be bisected across four releases afterwards. Turn it on in the patch
selector if you want to try it.

## Text action buttons

Adds **Select all**, **Copy** and **Paste** buttons to the toolbar above the keyboard. One tap
each, on whatever you are typing into.

Gboard can already do all three, behind its **Text editing** toolbar button — open that panel, then
tap the one you want. These are the same actions without the panel.

They take the first three slots on the toolbar, which pushes whatever used to be last into the
overflow menu behind the chevron. Long-press the toolbar to reorder them like any other button.

The labels are Gboard's own, so they are already translated wherever Gboard is. The icons are
Material's — the select-all marquee, and the familiar copy and paste marks. Gboard ships all three
and draws none of them, because its text editing panel spells the actions out in words rather than
using icons; that is why Select all first shipped borrowing an unrelated icon. Flexboard still adds
no images of its own.

## Toolbar hotkeys

Eight more toolbar buttons, each typing a string you set under **Hotkeys** in Flexboard's settings —
an email address, a signature, "brb", whatever you type often enough to resent typing.

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

**Eleven buttons is more than the bar holds** — unless *Bigger toolbar* is applied, which is what it
is for. Without it, the three text actions plus a few hotkeys push whatever used to sit at the end
of your toolbar into the overflow menu behind the chevron. Long-press the toolbar to reorder, and
drop what you do not need.

## Bigger toolbar

Gboard's toolbar holds five icons. Flexboard adds eleven of its own — eight hotkeys and three text
actions — and those eleven compete with the emoji, clipboard and settings buttons already there. So
the bar is the limit on everything above, and this patch raises it to twelve.

**It does not decide how many you see.** Choosing what sits on the bar is Gboard's own job, done by
long-pressing the toolbar and dragging icons in or out, and this patch does not touch it. All it
changes is the ceiling that choice runs into. Take a button off and it stays off; the count is
yours.

That distinction is the whole patch, and it is the part two earlier attempts got wrong. Both tried
to set the number of icons themselves, and both ended up fighting the toolbar customise screen —
one of them putting buttons back after they had been removed. Gboard treats *"take this off the
bar"* as lowering the count, so anything that forces the count up undoes the removal. Raising only
the ceiling cannot do that.

Two things worth knowing. Twelve icons on a phone are narrow — the bar divides the width it has
rather than scrolling — so twelve is the room available, not a recommendation. And if you had
already trimmed your toolbar before applying this, you will still see your old number until you
drag more icons onto the bar, because that number is your setting and the patch leaves it alone.

## Hidden features

Gboard ships a lot of finished features switched off behind Google's own feature flags, waiting on
a server-side rollout. Those flags are delivered per app **signature** — and a patched build is
resigned, so the delivery never arrives and every flag stays at whatever it was compiled with. On
this build that is 666 of them.

Most deserve to stay off; they are experiments and half-built code. Seven do not:

| | |
|---|---|
| **Grammar check** | the settings row, and the checking behind it |
| **Proofread** | on-device proofreading |
| **Emoji Kitchen browse** | the browse surface for sticker mashups |
| **Custom sticker tab** | your own stickers, as a tab |
| **Offline translate** | translation without a round trip to a server |
| **Dismissable chips** | a close control on the chips Gboard offers unprompted |
| **Settings search** | search inside Gboard's own settings |

Each is something Google ships to ordinary Gboard users today, so the code behind the flag is
finished — this is restoring what resigning took away, not switching on an experiment.

Some are locale- or account-dependent and may do nothing on your device, which is Google's doing
rather than the patch's. If a feature does not appear, it was not enabled for you upstream either.

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
