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

Step 1 is the only real work. `tools/apk/` has no ARSC reader — adding one is the single highest
-value addition to that toolkit, since resolving ids by hand has now been needed three times.

## Related

Gesture handlers are attached declaratively and gated on a preference key, so removing a handler
entry is another way to disable glide typing without touching the setting at all. See
[`motion-event-handlers.md`](motion-event-handlers.md).
