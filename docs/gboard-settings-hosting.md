# Hosting a native settings screen inside Gboard

> **Written against Gboard `18.0.3.954559732`.** Obfuscated names below were re-derived on that
> build and are what `tools/apk/preflight.py` pins (the `settings:` section). Companion to
> [`gboard-settings-ui.md`](gboard-settings-ui.md), whose narrative covers the Activity-based
> screen this replaced.

Flexboard's settings screen is a plain Gboard settings screen: same host, same rows, same store.
This is how the pieces fit, from a row tap to a persisted slider value.

## Navigating: there is no router

The root settings screen (`res/xml/settings.xml`, binary `res/B_o.xml`) is a
`PreferenceScreen` whose rows are `HeaderPreference`s carrying an `android:fragment` attribute:

```
fragment = com.google.android.apps.inputmethod.latin.preference.PreferencesSettingsFragment
```

(In the binary the attribute lands in the `res/android` namespace; that is what aapt produced for
Gboard and what this project's own edits match.)

A tap runs the androidx click path — Gboard ships a renamed port of it:

1. `Preference.performClick()` → `onClick()`, then the row listener, then the tree listener:
   `Lcdr;->aA(Landroidx/preference/Preference;)Z` (the `PreferenceFragmentCompat`.
   `onPreferenceTreeClick` port; tree/click plumbing is `Lcdw;`/`Lcdi;`).
2. If `Preference`'s fragment field is set, the host (`Lqip;`, parent of
   `com.google.android.apps.inputmethod.latin.preference.SettingsActivity`) records the row key
   and calls the androidx `Fragment.instantiate` port, `Lad;->C(Context, String, Bundle)` — a
   `Class.forName` lookup plus a public no-arg constructor — then a fragment transaction (`Lbf;`)
   with back-stack. If the attribute is absent it falls through to the `<intent>` child, which is
   what the old Activity-based screen used.

No registry, map or allow-set sits in front of in-app navigation. The only fragment registry in
the app is a Dagger `Map<String, Provider<Fragment>>` (`Lch;->a()`, 23 entries keyed on
`settings_header_*` strings) used by **external deep links** (the system "IME settings" button
etc., extras `ENTER_PREF_HEADER`, `:settings:fragment_args_key` — a `>`-joined key path,
`:android:show_fragment_args`). Unknown keys return null and fall back to the root screen;
nothing crashes. None of it is needed for a row-launched screen.

## The fragment contract

`Class.forName` + transaction impose the whole contract:

- public class, public no-arg constructor;
- extends Gboard's fragment chain — `CommonPreferenceFragment` → `Ldoe;` → `Lcdr;` (the
  `PreferenceFragmentCompat` port) → `Lad;` (the `Fragment` port). Verified concrete end to end,
  so a subclass providing nothing but an `aB()` override links and verifies;
- the screen is chosen by overriding `aB()I` → a `res/xml` resource id. Base returns 0 = blank
  screen. The concrete subclasses verified against: `GesturePreferenceSettingsFragment.aB()` →
  `xml/setting_gesture`, `PreferencesSettingsFragment.aB()` → `xml/setting_preferences`, and
  `SettingsActivity` itself embeds `xml/settings` / `xml/settings_legacy`.

Because patch-added resources have no id until aapt2 relinks, `aB()` resolves by **name**:
`getResources().getIdentifier("flexboard_settings", "xml", pkg)` — the same trick the settings
icon already used. The Context needed for it is the one piece of framework the subclass cannot
inherit by name (`getContext()` is an obfuscated member of `Lad;`), so the extension fragment
asks the published IME service first and falls back to the framework's
`ActivityThread.currentApplication()` by reflection; failing both it returns 0 — a blank screen,
deliberately not a crash, because the host going down with a row tap is worse.

The stub trick: the extension's fragment subclasses a compile-only stand-in
(`stubs/…/CommonPreferenceFragment.java`) declaring exactly the public no-arg constructor and
`public int aB()`. It lives outside `extensions/` because the Morphe settings plugin treats
every directory under there as an extension module. It is consumed `compileOnly`, so it is never
dexed; the superclass reference resolves against the real class once the extension DEX is merged
into the APK.

## Storage: the store installs itself

`Lqof;` registers a `FragmentLifecycleCallbacks` on every activity. On fragment creation it
checks `instanceof Lcdr` and installs a `PreferenceDataStore` (bridging into `Lqhy;`, Gboard's
own preference backend, device-protected storage) onto the fragment's manager. Anything our
fragment's rows persist therefore lands in the same store the swipe patches read mid-gesture —
the fix for the dev.3–dev.6 saga comes free, because there is nothing left to mirror. The old
pitfalls (credential-encrypted vs device-protected contexts) are documented in
`gboard-settings-ui.md` and only apply to code writing the store **without** this hook, which is
why `Preferences`/`Defaults` in the extension still mirror the context dance.

## The slider: `InlineSliderPreference`

`com.google.android.libraries.inputmethod.preferencewidgets.InlineSliderPreference` (the widget
on the Morse-keyboard screen) reads its configuration by **literal attribute name** off a null
namespace, so no attribute resources need to exist:

| attribute | default | notes |
|---|---|---|
| `slider_min_value` | 0 | `Lrqi;->d`: `getAttributeResourceValue` — reference → int resource; otherwise literal int |
| `slider_max_value` | 100 | same reader |
| `slider_scale` | 1.0 | stored float `N`; persistence writes `Integer.toString(round(v*N))` when `N` is whole |
| `slider_display_scale` | 0.0 | display only |
| `slider_unit` | — | raw string suffix on the value bubble (`ms`) |
| `slider_text_left` / `slider_text_right` | — | end labels (`1` / `No limit`) |

Persistence and restore are `Preference.ae(String)` / `Preference.w(String)` through the
datastore. With the default scale of 1 the stored form is a base-10 integer in a string; the
readers on the patch side therefore use the store's **parsing** string-keyed getInt, resolved in
`Fingerprints.kt` by its call to `Integer.parseInt` — the typed sibling would throw
`ClassCastException` on the string, and this one would throw on a leftover int, which is why the
two keys are new (`flexboard_swipe_*`) instead of migrated.

`android:defaultValue` is the ordinary androidx mechanism (written under the `res/android`
namespace, as Gboard's own binaries carry it) and persists once, on first bind.

## Failure checklist

- Row tap crashes with `Fragment$InstantiationException` → the class name on the row does not
  match the extension class, or the constructor/visibility contract broke.
- Screen opens blank → `aB()` returned 0 (no Context, or the resource name in the XML and the
  fragment's constant disagree) — never an exception.
- Slider moves but the engine does not change → key mismatch between the XML and
  `ScrubTuningPatch.kt` (the constants checker exists for exactly this), or the reader resolved
  the typed getInt instead of the parsing one.
- Rows render unstyled → the row XML lost the `HeaderPreference` class name; do not substitute
  the androidx default.
