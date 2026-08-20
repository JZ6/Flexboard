# [1.2.1-dev.5](https://github.com/JZ6/Flexboard/compare/v1.2.1-dev.4...v1.2.1-dev.5) (2026-08-20)

* **Gboard:** enable grammar check, smart replies, and keep suggestion strip on
* **Gboard:** add a custom Flexboard settings icon instead of borrowing Gboard's

# [1.2.1-dev.4](https://github.com/JZ6/Flexboard/compare/v1.2.1-dev.3...v1.2.1-dev.4) (2026-08-20)

* **Gboard:** allowlist Phenotype meta-data keys in manifest sweep
* **Gboard:** default Bigger Toolbar on, make Suggested Settings user-configurable

# [1.2.1-dev.3](https://github.com/JZ6/Flexboard/compare/v1.2.1-dev.2...v1.2.1-dev.3) (2026-08-20)

* **Gboard:** add internal basePatch as the foundation every public patch depends on
* **Gboard:** generalize flick symbols into a 'Suggested Settings' patch
* **Gboard:** disable swipe-length scaling and hide its slider

# [1.2.1-dev.2](https://github.com/JZ6/Flexboard/compare/v1.2.1-dev.1...v1.2.1-dev.2) (2026-08-20)

* **Gboard:** regenerate CHANGELOG with correct ranges and stripped prefixes
* **Gboard:** reorganize patch packages by user-facing feature
* **Gboard:** note the glyphs.py fix in the roadmap icon audit entry
* **Gboard:** glyphs.py tail check bleeding into the next path, skipping filled icons

# [1.2.1-dev.1](https://github.com/JZ6/Flexboard/compare/v1.2.0...v1.2.1-dev.1) (2026-08-20)

* **Gboard:** clean up CHANGELOG — remove bump commits, stable releases show all dev changes
* **Gboard:** release changelog only shows the bump commit, not the actual changes
* **Gboard:** gitignore docs/icons/ — exported SVGs for local viewing only

# [1.2.0](https://github.com/JZ6/Flexboard/compare/v1.1.1...v1.2.0) (2026-08-19)

* **Gboard:** drop orphaned preflight checks for store contains and write by id
* **Gboard:** one-pass methodsMatching helper in TextActionsPatch
* **Gboard:** delete the 4 inline "Resolved, not named" comments
* **Gboard:** finish the r() prologue dedup — extract resolveDispatchEntry
* **Gboard:** drop the dead sentinel gate and stale switch-era doc in scaleStepTable
* **Gboard:** route the signature-check register-count check through the helper too
* **Gboard:** deduplicate patch helpers and constants
* **Gboard:** note the swipe length may be inverted on the delete key
* **Gboard:** write Gboard's own preferences from Java too
* **Gboard:** start at 60% swipe length, 6 toolbar icons, 12 unfolded
* **Gboard:** six toolbar hotkeys that type a string you choose
* **Gboard:** record every Material icon Gboard bundles
* **Gboard:** add Copy and Paste buttons beside Select all

# [1.2.0-dev.5](https://github.com/JZ6/Flexboard/compare/v1.2.0-dev.4...v1.2.0-dev.5) (2026-08-19)

* **Gboard:** drop orphaned preflight checks for store contains and write by id
* **Gboard:** one-pass methodsMatching helper in TextActionsPatch
* **Gboard:** delete the 4 inline "Resolved, not named" comments
* **Gboard:** finish the r() prologue dedup — extract resolveDispatchEntry
* **Gboard:** drop the dead sentinel gate and stale switch-era doc in scaleStepTable

# [1.2.0-dev.4](https://github.com/JZ6/Flexboard/compare/v1.2.0-dev.3...v1.2.0-dev.4) (2026-08-19)

* **Gboard:** route the signature-check register-count check through the helper too
* **Gboard:** deduplicate patch helpers and constants

# [1.2.0-dev.3](https://github.com/JZ6/Flexboard/compare/v1.2.0-dev.2...v1.2.0-dev.3) (2026-08-19)

* **Gboard:** note the swipe length may be inverted on the delete key
* **Gboard:** write Gboard's own preferences from Java too

# [1.2.0-dev.2](https://github.com/JZ6/Flexboard/compare/v1.2.0-dev.1...v1.2.0-dev.2) (2026-08-19)

* **Gboard:** start at 60% swipe length, 6 toolbar icons, 12 unfolded

# [1.2.0-dev.1](https://github.com/JZ6/Flexboard/compare/v1.1.1...v1.2.0-dev.1) (2026-08-19)

* **Gboard:** six toolbar hotkeys that type a string you choose
* **Gboard:** record every Material icon Gboard bundles
* **Gboard:** add Copy and Paste buttons beside Select all

# [1.1.2-dev.1](https://github.com/JZ6/Flexboard/compare/v1.1.1-dev.2...v1.1.2-dev.1) (2026-08-19)

* **Gboard:** add Copy and Paste buttons beside Select all

# [1.1.1](https://github.com/JZ6/Flexboard/compare/v1.1.0...v1.1.1) (2026-08-19)

* **Gboard:** give a fold's two screens their own toolbar counts
* **Gboard:** tools: match stripped drawables against Material Icons by geometry
* **Gboard:** record that the toolbar slider collapses a fold's two counts into one
* **Gboard:** give Select all its own icon
* **Gboard:** raise the toolbar slider's maximum to 12

# [1.1.1-dev.2](https://github.com/JZ6/Flexboard/compare/v1.1.1-dev.1...v1.1.1-dev.2) (2026-08-18)

* **Gboard:** give a fold's two screens their own toolbar counts

# [1.1.1-dev.1](https://github.com/JZ6/Flexboard/compare/v1.1.0...v1.1.1-dev.1) (2026-08-18)

* **Gboard:** tools: match stripped drawables against Material Icons by geometry
* **Gboard:** record that the toolbar slider collapses a fold's two counts into one
* **Gboard:** give Select all its own icon
* **Gboard:** raise the toolbar slider's maximum to 12

# [1.1.0](https://github.com/JZ6/Flexboard/compare/v1.0.1...v1.1.0) (2026-08-18)

* **Gboard:** record that Bigger Toolbar works on a device
* **Gboard:** make Bigger Toolbar move the count, not the capacity
* **Gboard:** correct what the signature bypass actually gates
* **Gboard:** add a Select all button to the toolbar
* **Gboard:** withhold Bigger Toolbar, which does not work on device
* **Gboard:** default the swipe length to Gboard's own distance
* **Gboard:** sync the gradle wrapper with the template
* **Gboard:** make the toolbar's icon count adjustable
* **Gboard:** keep Gboard's own behaviour for swipes from the backspace key
* **Gboard:** update the roadmap
* **Gboard:** remove the master and undo switches

# [1.1.0-dev.3](https://github.com/JZ6/Flexboard/compare/v1.1.0-dev.2...v1.1.0-dev.3) (2026-08-18)

* **Gboard:** make Bigger Toolbar move the count, not the capacity
* **Gboard:** correct what the signature bypass actually gates
* **Gboard:** add a Select all button to the toolbar

# [1.1.0-dev.2](https://github.com/JZ6/Flexboard/compare/v1.1.0-dev.1...v1.1.0-dev.2) (2026-08-18)

* **Gboard:** withhold Bigger Toolbar, which does not work on device
* **Gboard:** default the swipe length to Gboard's own distance
* **Gboard:** sync the gradle wrapper with the template

# [1.1.0-dev.1](https://github.com/JZ6/Flexboard/compare/v1.0.1...v1.1.0-dev.1) (2026-08-18)

* **Gboard:** make the toolbar's icon count adjustable
* **Gboard:** keep Gboard's own behaviour for swipes from the backspace key
* **Gboard:** update the roadmap
* **Gboard:** remove the master and undo switches

# [1.0.1](https://github.com/JZ6/Flexboard/compare/v1.0.0...v1.0.1) (2026-08-18)

* **Gboard:** 1.0.1 release
* **Gboard:** track the swipe across the full keyboard height
* **Gboard:** derive the obfuscated names that have look-alike siblings
* **Gboard:** add welcome video

# [1.0.1-dev.2](https://github.com/JZ6/Flexboard/compare/v1.0.1-dev.1...v1.0.1-dev.2) (2026-08-17)

* **Gboard:** track the swipe across the full keyboard height

# [1.0.1-dev.1](https://github.com/JZ6/Flexboard/compare/v1.0.0...v1.0.1-dev.1) (2026-08-17)

* **Gboard:** derive the obfuscated names that have look-alike siblings
* **Gboard:** add welcome video

# [1.0.0](https://github.com/JZ6/Flexboard/compare/v0.0.1...v1.0.0) (2026-08-17)

* **Gboard:** update roadmap notes
* **Gboard:** make the settings screen look like Gboard's
* **Gboard:** correct the local build requirements
* **Gboard:** escape the dollars in the re-commit pattern
* **Gboard:** update roadmap notes
* **Gboard:** call the right re-commit method for undo on Gboard 18
* **Gboard:** target Gboard 18.0.3
* **Gboard:** resolve inherited fields by walking up, as the runtime does
* **Gboard:** wrap the new lines to the width the rest of the file uses
* **Gboard:** check a register really holds what the instruction using it needs
* **Gboard:** add tools/promote, so a stable release cannot ship a stale build

# [1.0.0-dev.1](https://github.com/JZ6/Flexboard/compare/v0.0.3-dev.3...v1.0.0-dev.1) (2026-08-17)

* **Gboard:** No changes recorded.

# [0.0.3-dev.3](https://github.com/JZ6/Flexboard/compare/v0.0.3-dev.2...v0.0.3-dev.3) (2026-08-16)

* **Gboard:** update roadmap notes
* **Gboard:** make the settings screen look like Gboard's
* **Gboard:** correct the local build requirements

# [0.0.3-dev.2](https://github.com/JZ6/Flexboard/compare/v0.0.3-dev.1...v0.0.3-dev.2) (2026-08-16)

* **Gboard:** escape the dollars in the re-commit pattern
* **Gboard:** update roadmap notes
* **Gboard:** call the right re-commit method for undo on Gboard 18

# [0.0.3-dev.1](https://github.com/JZ6/Flexboard/compare/v0.0.2-dev.2...v0.0.3-dev.1) (2026-08-16)

* **Gboard:** target Gboard 18.0.3

# [0.0.2-dev.2](https://github.com/JZ6/Flexboard/compare/v0.0.2-dev.1...v0.0.2-dev.2) (2026-08-16)

* **Gboard:** resolve inherited fields by walking up, as the runtime does

# [0.0.2-dev.1](https://github.com/JZ6/Flexboard/compare/v0.0.1...v0.0.2-dev.1) (2026-08-16)

* **Gboard:** wrap the new lines to the width the rest of the file uses
* **Gboard:** check a register really holds what the instruction using it needs
* **Gboard:** add tools/promote, so a stable release cannot ship a stale build

# [0.0.1](https://github.com/JZ6/Flexboard/compare/v0.0.0...v0.0.1) (2026-08-16)

* **Gboard:** fix the keyboard failing to start at all
* **Gboard:** split the README into what users need, and move the rest to docs
* **Gboard:** keep upstream's files upstream, and write down which are which
* **Gboard:** push the release commit and its tag atomically
* **Gboard:** shorten the default swipe to one word, and make undo switchable

# [0.0.1-dev.2](https://github.com/JZ6/Flexboard/compare/v0.0.1-dev.1...v0.0.1-dev.2) (2026-08-16)

* **Gboard:** fix the keyboard failing to start at all
* **Gboard:** split the README into what users need, and move the rest to docs

# [0.0.1-dev.1](https://github.com/JZ6/Flexboard/compare/v0.0.0...v0.0.1-dev.1) (2026-08-16)

* **Gboard:** keep upstream's files upstream, and write down which are which
* **Gboard:** push the release commit and its tag atomically
* **Gboard:** shorten the default swipe to one word, and make undo switchable

# 0.0.0 (2026-08-15)

* **Gboard:** publish a bundle Android can actually load
* **Gboard:** make the Morphe install link a button
* **Gboard:** order pre-releases the way Morphe does, and require -dev.N
* **Gboard:** drop the semantic-release remnants the restore brought back
* **Gboard:** swipe right to undo the last delete
* **Gboard:** grey out the Gboard glide rows Flexboard writes for itself
* **Gboard:** write the settings to the file Gboard actually reads
* **Gboard:** add a switch to turn swipe-to-delete on and off
* **Gboard:** update the roadmap notes
* **Gboard:** stop the Flexboard settings screen clipping its first row
* **Gboard:** stop opening a pull request to promote to main
* **Gboard:** launch the Flexboard settings by component, not by action
* **Gboard:** open the Flexboard settings from an Activity, not a nested screen
* **Gboard:** turn Gboard's flick-for-symbols on by default
* **Gboard:** add a max-words-per-swipe cap
* **Gboard:** add a Flexboard settings screen with swipe length and hold delay
* **Gboard:** stop the flick fix crashing Gboard with a VerifyError
* **Gboard:** make the swipe register on a flick instead of a held drag
* **Gboard:** force Gboard's scrub delete on and glide typing off
* **Gboard:** swipe anywhere to delete the previous word
* **Gboard:** Init flexboard
* **Gboard:** name the bundle Flexboard rather than the template placeholder
* **Gboard:** Initial commit

