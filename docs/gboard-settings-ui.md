# Gboard's settings screens

How Flexboard adds rows to Gboard's settings screens, and how it greys out Gboard's own glide
typing checkbox while the swipe gesture is enabled — preventing the conflict rather than reacting
to it. This is now implemented; the sections below are in the order they were worked out, so the
dead ends are kept alongside what replaced them rather than deleted.

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

## Nested `<PreferenceScreen>` does not navigate — confirmed on device

`v0.1.0-dev.3` shipped the Flexboard settings as a nested `<PreferenceScreen>` appended to
`res/xml/settings.xml`. **The row rendered correctly and did nothing when tapped.**

That confirms the suspicion recorded above. androidx routes a nested screen through
`PreferenceFragmentCompat.onNavigateToScreen`, which delegates to an
`OnPreferenceStartScreenCallback` on the callback fragment or the activity and has **no fallback**:

```java
if (getCallbackFragment() instanceof OnPreferenceStartScreenCallback) { … }
if (!handled && getActivity() instanceof OnPreferenceStartScreenCallback) { … }
// no else — the tap is swallowed
```

Neither `CommonPreferenceFragment` nor `SettingsActivity` implements it, so the tap goes nowhere.

The useful half of the result: everything *except* navigation worked. `res/xml/settings.xml` is
editable by a resource patch, a patch-authored `PreferenceScreen` inflates, placement inside the
last `PreferenceCategory` puts the row among Gboard's own, and literal `android:key`/`android:title`
values compile fine without new string resources.

### What replaced it

An `<intent>` row launching an Activity carried in the extension DEX — the route v0.3 used.

Giving the screen its own *fragment* is not an easier alternative: Gboard's fragments choose their
XML by overriding `CommonPreferenceFragment.aB()I`, so ours would have to subclass a Gboard type,
which an extension cannot do without stubbing that type and keeping the stub out of the merge.

Two details worth keeping:

- **The row must name a component, not an action.** See the section below — an action was tried
  first, and it does not work.
- **The Activity has no resources of its own.** An extension merges DEX, not resources, so every
  view is built in code with framework widgets. The theme is set on the manifest entry
  (`@android:style/Theme.DeviceDefault.Settings`) so the screen still follows system dark mode
  without hardcoding a palette.

## An implicit intent cannot reach the Activity — confirmed on device

`v0.1.0-dev.4` gave the Activity an intent filter on a unique action and pointed the row at that
action, so that the row would not have to name a package the rename patch might change afterwards.
**Tapping it reported "no application found to handle the action."**

Gboard declares `targetSdkVersion="37"`:

```
uses-sdk {'minSdkVersion': '26', 'targetSdkVersion': '37'}
```

Since Android 14, an app targeting 34 or higher may only deliver an implicit intent to an
**exported** component, *including its own*. The Activity is `exported="false"`, so its filter was
excluded from resolution, `startActivity` found no candidate, and the tap raised
`ActivityNotFoundException`. Nothing about the Activity or the extension was wrong — the release
built, the extension DEX merged, the manifest entry recompiled, and the row rendered.

Note the manifest entry is provably present even though nothing on device can show it: the row and
the `<activity>` are written by the same `finalize` block, the manifest first, and the row appeared.

### What works instead

An explicit component, which is what v0.3 shipped and why v0.3 worked:

```xml
<intent android:action="android.intent.action.MAIN"
        android:targetPackage="…"
        android:targetClass="dev.jz6.flexboard.extension.settings.FlexboardSettingsActivity" />
```

`PreferenceInflater` hands an `<intent>` child to `Intent.parseIntent`, which calls `setComponent`
only when it has **both** `targetPackage` and `targetClass` — `targetClass` alone leaves the intent
implicit and fails the same way. A same-app explicit intent reaches a non-exported component on
every Android version, so `exported` stays `false` and the intent filter is gone.

### Naming the package without depending on patch order

Naming the package brings back the problem the action was meant to dodge: the rename patch and the
settings patch both write in `finalize`, in no guaranteed order. The fix is the one v0.3 used —
make the result the same either way rather than trying to force an order.

- The settings patch writes whatever the manifest's `package` says when it runs, and asserts it is
  one of the two known values.
- The rename patch rewrites `targetPackage` on any `<intent>` naming the Flexboard Activity, and
  asserts nothing about finding one.

Settings first: the rename corrects it. Rename first: the settings patch already reads the renamed
manifest, and the rename patch's sweep found nothing to do. Rename unticked: the row keeps the
original package, which is then the right one.

## Disabling one of Gboard's own rows

`android:dependency` works in Gboard's screens — `setting_gesture.xml` already uses it, so the
mechanism is live in this build rather than merely present in androidx:

```
PreferenceScreen                                   key=@0x7f140ac2  'Glide typing'
  SwitchPreferenceCompat  key=@0x7f14097b  'enable_gesture_input'
  SwitchPreferenceCompat  key=@0x7f1409ca  'pref_gesture_preview_trail'  dependency=@0x7f14097b
  SwitchPreferenceCompat  key=@0x7f140995  'enable_scrub_delete'
  SwitchPreferenceCompat  key=@0x7f140996  'enable_scrub_move'
```

androidx disables a preference whose dependency is **unchecked**, which is usually the wrong way
round for a patch: what you have added is normally the thing that should *disable* the stock row
when it is on. `TwoStatePreference` inverts on request:

```java
public boolean shouldDisableDependents() {
    return (mDisableDependentsState ? mChecked : !mChecked) || super.shouldDisableDependents();
}
```

so `android:disableDependentsState="true"` on the controlling switch is the whole trick.
`dependency`, `disableDependentsState` and `defaultValue` are all **framework** attributes, so none
of them depends on Gboard's own resource table surviving R8.

Two things to know before using it:

- **The dependency key must exist in the same hierarchy.** `Preference.registerDependency` throws
  `IllegalStateException` when `findPreferenceInHierarchy` comes back null, which takes out the
  whole screen — not just the row. Write both edits in one `finalize` on one document.
- **It is live.** `setChecked` calls `notifyDependencyChange`, so the stock row greys and un-greys
  on the tap without leaving the screen.

### Naming a row whose key is obfuscated

Every preference key in these files is a string resource named `0_resource_name_obfuscated` —
`enable_gesture_input`, `pref_gesture_preview_trail`, `enable_scrub_delete` and `enable_scrub_move`
all share that one name — so a row cannot be matched on the face of its decoded `@string/…`
reference.

Match it by what points at it instead. The trail row is the file's only `android:dependency` and it
depends on glide typing, so the glide row is the one whose key that dependency names; both sides are
the same resource id, so whatever the decoder emits for the collapsed name it emits the same string
for both.

This rests on the decoder giving colliding names distinct decoded forms, which it must:
`res/xml/settings.xml` is decoded and recompiled by every build of this bundle and all sixteen of
its rows carry `@string/0_resource_name_obfuscated` keys. Were those collapsed to a single name,
recompiling would repoint them all at one resource and Gboard's settings would have been broken in
every release so far. Assert the match is unique anyway, so that if it ever stops holding the patch
fails at apply time instead of targeting the wrong row.

### When there is no anchor at all

**Glide delete** and **Glide cursor control** are indistinguishable in the decoded XML — same tag,
same attribute set, and key, title and summary all collapsed to the same name. Nothing points at
either. The only thing separating them is document order.

Where that happens, elimination is the best available: four rows, one is glide typing, one depends
on it, and of the two left Glide delete comes first. Assert the counts so a *shape* change fails
loudly, and accept that a *reorder* would not be caught.

What makes that acceptable here is the pin. `COMPATIBILITY_GBOARD` fixes the bundle to one build by
version and signature, so a reorder cannot arrive without failing that gate first — and it is a
milder bet than the hardcoded resource ids elsewhere in the project, which would silently write the
wrong *preference* rather than grey the wrong *row*. Prefer a positional anchor whose worst case is
cosmetic, and say in the code that it is positional.

### Writing where Gboard reads — the file name is not enough

`Lpnp;-><init>(Context, String)` is called with a null name and falls through to
`PreferenceManager.getDefaultSharedPreferences`, i.e. `<packageName>_preferences` in `MODE_PRIVATE`.

That much was known from the start, and it was not enough. **The store reads device-protected
storage**, which the first version of the Activity missed entirely — so it wrote the right file name
in the wrong directory and every slider silently did nothing, all the way from `v0.1.0-dev.3` to
`v0.1.0-dev.6`. The tell was a word cap set to 1 that still deleted several words.

The path runs `Lpnp;-><init>` → `Lpns;-><init>`, and the relevant part is offsets 115–141:

```
115: iget-object v5, v6, Lpns;->a:Landroid/content/Context;   # getApplicationContext()
119: invoke-static {v5}, …ApiModelOutline1;->m(Context)Z      # isDeviceProtectedStorage()
123: if-eqz v2, -> 127
125: move-object v2, v5                                       # already device-protected
127: invoke-static {v5}, …ApiModelOutline1;->m(Context)Context # createDeviceProtectedStorageContext()
141: invoke-static {v5}, PreferenceManager;->getDefaultSharedPreferences(…)
```

The two `m(Context)` calls are D8 API-model outlines; disassembling them is what names them. A
device-protected context stores under `/data/user_de/<user>/<pkg>/shared_prefs/`, an ordinary one
under `/data/user/<user>/<pkg>/shared_prefs/`. Same file name, two unrelated files.

Gboard does this because a keyboard has to work at the lock screen, before the device is unlocked
and credential-encrypted storage is available. Any patch or extension writing preferences Gboard
reads has to resolve the same context — `FlexboardSettingsActivity.preferenceContext()` mirrors
those three lines rather than paraphrasing them.

Nothing on the bytecode side was ever affected: it reads through `Lpnp;` itself, so it always got
the right file. Only code outside the store had to know.
