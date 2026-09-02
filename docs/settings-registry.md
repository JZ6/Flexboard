# Conditional settings rows: the patch-time selection registry

## The problem

`flexboard_settings.xml` is one static file with every row: if a patch isn't ticked in Morphe,
its rows still appear — a dead "Words per swipe" slider for someone who didn't want Swipe to
Delete. Each patch's settings should appear only when the patch was selected.

## Why the obvious answers don't work

- **Per-patch `resourcePatch` that adds its rows** — resource patches can't modify bytecode, but
  they CAN modify resources. The problem: `settingsScreenPatch` writes the whole file in its
  `finalize`, and cleanup patches would run AFTER it. Morphe iterates finalizes in **reverse
  dependency order** (dependents first). `settingsScreenPatch` is a dependency of everything
  (via `basePatch`), so its finalize runs LAST. A cleanup patch that tried to remove rows would
  run BEFORE the file exists. **Dead on arrival.**

- **Runtime grey-out** — leave the rows in the APK, hide them in the fragment. Requires a
  per-feature runtime probe (a resource marker or a store key) and a fragment edit. More
  fragile: markers survive re-patching (a patch can be un-ticked and re-patched but the marker
  from the previous run persists), the fragment's `remove` on `PreferenceGroup` isn't pinned,
  and rows that fail mid-load don't get cleaned up.

## The answer: a patch-time selection registry

All `execute` blocks complete before any `finalize` begins — verified from the Morphe framework
bytecode (`Patcher.invoke` iterates patches through two sequential phases). Registration during
`execute` is therefore always visible to `settingsScreenPatch.finalize`, which always runs last
(reverse-finalize ordering makes it the fresh-out state; the execute-before-finalize contract
makes it correct).

### Shape

```
new shared/SettingsSections.kt
  enum SettingsSection { SWIPE_TO_DELETE, HOTKEYS }
  selectedSettingsSections: mutable set

feature patches register in execute:
  swipeToDeletePatch.execute { selectedSettingsSections += SWIPE_TO_DELETE; ... }
  toolbarHotkeysPatch.execute { selectedSettingsSections += HOTKEYS; ... }

settingsScreenPatch:
  execute { selectedSettingsSections.clear() }          // fresh per run
  finalize { filter XML by registry before writing }
```

The patcher JVM is one classloader per run — a top-level `internal` mutable set is a valid
side channel. A patch that isn't ticked never executes → never registers → the set contains
exactly the selected features.

### The XML

Sentinel comments around each category:

```xml
<PreferenceScreen ...>

    <!-- @SECTION_SWIPE_TO_DELETE@ -->
    <PreferenceCategory android:title="Swipe to delete">
        ... slider ...
    </PreferenceCategory>
    <!-- @END_SECTION_SWIPE_TO_DELETE@ -->

    <!-- @SECTION_HOTKEYS@ -->
    <PreferenceCategory android:title="Hotkeys">
        ... rows ...
    </PreferenceCategory>
    <!-- @END_SECTION_HOTKEYS@ -->

    <PreferenceCategory android:title="About">     <!-- unconditional -->
        ... version ...
    </PreferenceCategory>

</PreferenceScreen>
```

`writePatchResource` already does placeholder substitution; a section filter that drops blocks
between `@SECTION_X@` and `@END_SECTION_X@` when `X` isn't in the registry is a regex pass
before the parse check. The checkers parse the template and are untouched.

## What changes

| file | change |
|---|---|
| `shared/SettingsSections.kt` | **new** — enum + mutable set |
| `shared/SettingsScreenPatch.kt` | clear in `execute`, filter in `finalize`, gate the icon-drawable loop on HOTKEYS |
| `swipetodelete/ScrubDeleteAnywherePatch.kt` | one line: register SWIPE_TO_DELETE in `execute` |
| `toolbar/ToolbarHotkeysPatch.kt` | one line: register HOTKEYS in `execute` |
| vibration patches (once they want settings rows) | same one line |
| `resources/xml/flexboard_settings.xml` | sentinel comments around Swipe and Hotkeys categories |
| `.github/scripts/check_shared_constants.py` | untouched — checks the template, not the output |

No extension code changes. No bytecode changes beyond the patch registrations. No new
fingerprints. No Morphe API surface beyond what already exists.

## What it costs

One shared mutable set (like `selectedPatches` in Morphe's own patcher, just per-feature).
One regex filter pass in `finalize`. One `execute` hook in `settingsScreenPatch` for clearing.
One line per feature.

Total: ~50 lines of new code, no reordering of the existing pipeline.
