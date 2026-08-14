# Gboard's settings screens

Notes toward greying out Gboard's glide typing checkbox while the swipe gesture is enabled —
preventing the conflict rather than reacting to it. Not implemented. This records what is known
so the next attempt starts from the dead end rather than rediscovering it.

## Why bother

Flexboard writes the glide typing setting off while the gesture is on, and writes it back when
the gesture is turned off. That works, but it leaves one bad interaction: if the user ticks glide
typing back on in Gboard's settings, Flexboard either fights them — silently unticking a box they
just ticked, which reads as a bug even when it is working — or lets the conflict happen.

A greyed-out row with a summary saying *why* avoids the choice entirely.

## The row is a stock AndroidX preference

`res/J_u.xml` is a preference screen whose string pool is only nine entries:

```
persistent  title  key  summary  dependency
PreferenceScreen
SwitchPreferenceCompat
android
http://schemas.android.com/apk/res/android
```

So the glide typing row is a plain `SwitchPreferenceCompat`. `setEnabled(false)` greys it out with
no custom work, and the `dependency` attribute is already in use on that screen.

Twelve XML resources under `res/` reference `0x7f14097b`, the glide preference id — configuration
variants of the same logical screen. **None contain the literal key text**, because the key is a
`@string` reference rather than an inline string, which is why searching resources for
`enable_gesture_input` finds nothing.

## Two routes

**Resource patch, via `android:dependency`.** AndroidX disables a preference when the preference
it depends on is off. Declarative, no bytecode. But the dependency must be another preference in
the *same* `SharedPreferences`, and Flexboard's enabled flag lives in its own file — so it would
have to be mirrored into Gboard's store with a companion preference declared on that screen, and
kept in step across all twelve variants. The result is a greyed row with no explanation.

**Bytecode patch, `setEnabled(false)`.** One hook, evaluated at runtime against the live gesture
state, and it can set the summary too:

> Glide typing — *Off while Flexboard's swipe gesture is on.*

That is the better outcome. A greyed control with no reason is a support question; a greyed
control that explains itself is a feature.

## The dead end

The inflating fragment has not been found.

```
addPreferencesFromResource     0 call sites
setPreferencesFromResource     0 call sites
```

Not because Gboard does something exotic — because AndroidX is minified into the app, so those
method names are obfuscated along with everything else. Searching for classes named `*Preference*`
or `*Settings*` is equally useless: `Lpnj;` **is** `PreferenceKeyCache`, and only its log strings
say so.

## The next step, which is known-shaped

1. Resolve the resource id of `res/J_u.xml` from the ARSC — it is type `xml`, so walk the
   `ResTable_type` chunks for that type and find the entry whose value points at that path. The
   method is the same one used for the string id in
   [`glide-detection.md`](glide-detection.md), which is currently the only place ARSC parsing is
   written down.
2. Search the dex for that constant. Whatever loads it is the fragment, or close enough to it.
3. From there the hook is ordinary: find the preference by key and call `setEnabled` plus
   `setSummary` on it.

Step 1 is no longer work: [`../tools/apk/arsc.py`](../tools/apk/README.md) resolves ids both ways
now, and the screens it resolves are listed below.

## What is now known about adding rows

Flexboard's own settings screen is built by
`patches/.../features/scrubsettings/SettingsScreenPatch.kt`, and establishing it settled several
questions this document had open.

**The settings screens are addressable by name.** 33 of Gboard's 33,287 resource entries survive
`--collapse-resource-names`, and every settings screen is among them:

| Id | Name | Packed path |
|---|---|---|
| `0x7f170e7e` | `xml/settings` | `res/B_o.xml` |
| `0x7f170e7f` | `xml/settings_legacy` | `res/IeH.xml` |
| `0x7f170e70` | `xml/setting_gesture` | `res/J_u.xml` |

plus `setting_about`, `setting_correction`, `setting_privacy` and the rest of the `setting_*` family.
So a resource patch can edit `res/xml/settings.xml` directly, without ever finding the fragment that
inflates it — which is what makes the dead end above survivable.

`xml/settings` is an index: `PreferenceCategory` groups of `HeaderPreference` rows, each carrying a
`fragment=` attribute naming a `CommonPreferenceFragment` subclass. Those subclasses pick their
screen by overriding `aB()I`, so a *new* screen with its own fragment would mean shipping a class
that extends a Gboard type — the stub-class problem that pushed v0.3 into an extension.

**The androidx preference widgets survive minification with real names.** All 16 of them, including
the ones Gboard never uses in XML:

```
CheckBoxPreference  DialogPreference  DropDownPreference  EditTextPreference
ListPreference  MultiSelectListPreference  Preference  PreferenceCategory
PreferenceGroup  PreferenceScreen  SeekBarPreference  SwitchPreference
SwitchPreferenceCompat  TwoStatePreference
```

This is worth checking again on a future Gboard, and it is not a safe assumption in general: R8 keeps
these because AGP generates keep rules from XML-referenced class names, and Gboard's own screens only
ever name `PreferenceScreen` and `SwitchPreferenceCompat`. That `SeekBarPreference` survived anyway
is what makes a slider row possible; if a later build strips it, `ListPreference` and
`SwitchPreferenceCompat` are the ones Gboard's own XML guarantees.

**Sub-screen navigation is doubtful.** androidx opens a nested `<PreferenceScreen>` only when the
host implements `OnPreferenceStartScreenCallback`. `CommonPreferenceFragment` declares no interfaces,
`SettingsActivity` declares none, and the only `PreferenceScreen`-taking method left on the
obfuscated base `Ldgh;` is `az(Landroidx/preference/PreferenceScreen;)V` — `setPreferenceScreen`, not
a navigation path. Flexboard ships a nested screen anyway, because falsifying it costs one build and
the fallback is the expensive one: an `<intent>` row launching an Activity carried in an extension
DEX, which is exactly what v0.3 did.

**Keys can be literal strings.** Gboard's own rows use `@string` references, but nothing requires it,
and a patch-added preference has no choice: a new string resource has no id until aapt2 recompiles,
which is long after any bytecode patch that wants to read the value. `Lpnp;` has string-keyed getters
for exactly this — see [`motion-event-handlers.md`](motion-event-handlers.md).

## Related

Gesture handlers are attached declaratively and gated on a preference key, so removing a handler
entry is another way to disable glide typing without touching the setting at all. See
[`motion-event-handlers.md`](motion-event-handlers.md).
