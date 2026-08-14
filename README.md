# Flexboard

Swipe anywhere on Gboard to delete the previous word.

Gboard's only word-delete is a swipe on the backspace key, which means moving your thumb to the
corner and back for every correction. Flexboard puts that gesture wherever your thumb already is.

It is a [Morphe](https://github.com/MorpheApp) patch bundle for Gboard
`17.7.7.932364120-release-arm64-v8a`, and only that build.

> **Status: early rebuild.** This repository was restarted on the Morphe patches template. The
> patch listed below is the template's example and does nothing useful yet. The previous working
> implementation is preserved at [JZ6/Flexboard0](https://github.com/JZ6/Flexboard0).

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

## Roadmap

can we prevent glide tying being turned on if swipe is on and vice versa

add select all copy paste hotkeys

Configurable Swipe distance

Make the configuration more user intuitive, like short medium long instead of ms or px

Hot keys as new tool bar objects

increased tool bar size fit more buttons

fork from morphe patch templates

## Development

Building, testing and releasing: [`docs/development.md`](docs/development.md), which also indexes
the reverse-engineering notes — the obfuscated names this depends on, how the glide typing setting
was found, and the Gboard internals the rebuild is aiming at.

## Licence and attribution

GPL-3.0. See [`LICENSE`](LICENSE).

Built from the [Morphe patches template](https://github.com/morpheapp/morphe-patches-template).
[`NOTICE`](NOTICE) carries Morphe's naming terms.

Gboard is a trademark of Google LLC. This project is not affiliated with or endorsed by Google.
