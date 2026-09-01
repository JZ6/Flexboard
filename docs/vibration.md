# Vibration, traced: why the slider is missing on some devices

The row exists on every device — Gboard's own preference screen (`res/0CM.xml`):

```xml
<SwitchPreferenceCompat          key="enable_vibrate_on_keypress"/>
<VibrationDurationPreference     key="vibration_duration"
                                 android:dependency="enable_vibrate_on_keypress"/>
<GearPreference                  key="system_haptic_settings" persistent="false"/>
```

What is written where is not what decides whether the row is even shown.

## The settings fragment decides which rows survive

`Lqod;->b(Landroid/content/Context;Lqno;)V` runs when the preferences screen is built. It loads
the three resource ids — `enable_vibrate_on_keypress`, `vibration_duration`,
`system_haptic_settings` — gets the Vibrator, resolves the system haptic-settings Intent, then
calls `Lphn;->b(Context)I` for the mode:

| mode | what the fragment does |
|---|---|
| **1** | **removes the gear row**, keeps the toggle + slider, renames the toggle to "Keyboard vibration" — Gboard owns vibration |
| **2** | **removes the toggle + slider**, keeps the gear row, attaches the system-settings Intent — system owns vibration |
| **3** | removes all three — no haptics |
| no Vibrator | removes all three |

On a Pixel, mode ≠ 1: the toggle and slider are stripped, and the "Keyboard vibration" gear
links to the system haptic settings page — exactly the behaviour observed. On the Fold, mode = 1:
the slider and toggle stay.

## Where the mode comes from

`Lphn;->b(Context)I` has three gates in order:

1. **A cached Intent resolution** (`Lphn;->a:Lmvi;` with a Context-capturing lambda) — asks the
   system whether a haptic-settings activity exists. **If the Intent is null (unresolvable),
   return 1 immediately** — Gboard owns vibration because the system doesn't expose the page.
   This is the Fold's path: Samsung's settings don't answer the intent Gboard probes for.
2. A Phenotype flag read, `Lqvi;->k()` — server-driven, per-device rollout.
3. SDK ≥ 37: another flag, `Lqvi;->h()` (a long; -1 falls through). SDK < 37: `Lqvi;->g()`.

The flags come from Dagger providers (`Labjf;` on `Lqvi;` fields `s/t/w`) backed by Phenotype —
which is why a Pixel and a Samsung on the same Gboard build get different answers. On stock
Android the Intent resolves (the system haptic page exists), so Gboard defers to the system and
strips its own slider.

## The vibrator path, and a second gate

Even with the slider shown (mode 1), the key-release dispatch in `Lpho;->d(View;I)V` has a
suppression check:

```
n()Z; if true → skip (no vibration)
…
Lpho;->n:I  (the stored duration, mirrored from the pref and capped at 100 ms)
if n:I > 0 → f(n:I)  → Vibrator.vibrate(effect)
```

`n()Z` is a press-delay suppression gate:

| condition | `n()` returns |
|---|---|
| `Lpho;->d:Z` false | false (don't suppress → vibrator runs) |
| `d:Z` true, SDK ≥ 33 | **true** (suppress → vibrator skipped) |
| `d:Z` true, SDK < 33, `g:Z` true | true |
| `d:Z` true, SDK < 33, `g:Z` false, `l()` true | true |
| else | false |

`d:Z` is set from a preference/flag observer (`Lfol;->fV`), so it too is server-driven. On a
modern Pixel (SDK ≥ 33) with `d:Z` true, `n()` returns true and the vibrator path is skipped —
meaning even forcing the slider visible would leave it inert unless this second gate is also
cleared.

## `f(I)V` — the actual vibration

Gets the `Vibrator`, then:

- `k(Vibrator)` — device supports amplitude control (SDK ≥ 30, `hasAmplitudeControl`, gated by a
  min-SDK flag `Lphi;->b`):
  - **yes** → `VibrationEffect.Composition.addEffect(PRIMITIVE_CLICK, scale = duration/128)` —
    the slider's milliseconds map onto a 0..1 strength scale (capped at 100 ms → 0.78).
  - **no** → `VibrationEffect.createOneShot(duration, amplitude = -1)`.
- Vibrate with `VibrationAttributes` on SDK ≥ 33, legacy call below.

The press path `e(View;I)V` goes through `View.performHapticFeedback` instead — system-controlled
regardless of mode; only the release path consumes the slider value.

## Plan: "vibration everywhere" — two patches

Two binary patches, both "replace the method body with a constant return". Together they make
the slider appear and work on every device, regardless of the Phenotype cohort or the suppression
flag.

### Patch 1 — show the slider: force `Lphn;->b(Context)I` → return 1

Makes the settings fragment take the mode-1 branch on every device: toggle + slider stay, gear
row goes. The key-release dispatch also sees mode 1.

- Fingerprint by shape: the method is reached from `Lqod;->b` (the fragment setup) and from
  `Lpho;->h()Z` / `Lpho;->e()` — anchored on the `(Landroid/content/Context;)I` static call they
  make. Nothing else is named.
- Edit, branchless: replace the first instructions with `const/4 v0, 0x1` / `return v0`. Register
  count (7) asserted first. Unreachable tail is verifier-safe.

### Patch 2 — take the vibrator path: force `Lpho;->n()Z` → return false

Clears the suppression gate so `d()` reaches `f(duration)` on key release. Without this, mode 1
on a Pixel (SDK ≥ 33, `d:Z` true) still skips the vibrator.

- Fingerprint by shape: `n()Z` is the private instance method on `Lpho;` called from `d()` and
  `e()`. Located relative to `Lpho;->h()Z` (which calls `Lphn;->b` — the patch-1 target).
- Edit, branchless: `const/4 v0, 0x0` / `return v0`. Register count (4) asserted.

### What the user gets

- The slider and "Vibrate on keypress" toggle appear in Gboard's settings on every device.
- The gear row ("Keyboard vibration → system settings") is gone — Gboard owns vibration.
- Dragging the slider changes the key-release vibration strength 1:1 (capped at 100 ms by
  Gboard's own `Math.min(value, 100)` in `i()`).
- The press tick (system `performHapticFeedback`) stays as Gboard already routes it — unchanged.

### Considered and rejected

- **Forcing only `Lphn->b()` (patch 1 alone).** Slider appears but the vibrator path is still
  suppressed by `n()Z` on modern Pixels — inert slider, the exact complaint.
- **Forcing only `n()Z` (patch 2 alone).** Vibrator path runs but the slider row is stripped from
  the UI — nothing to drag.
- **Forcing `h()Z` true.** Lights up availability without changing which path produces vibration.
- **Overriding the Phenotype flags.** Server-driven and versioned per rollout; chasing them is
  chasing Google's experiments. The mode method is the convergence point.
- **Patching `d()` to skip the `n()` check.** More invasive — changes control flow in the
  dispatch method, fragile per build. The constant-return on `n()Z` achieves the same with no
  control-flow edit.

### Preflight pins

- `Lphn;->b(Landroid/content/Context;)I` exists, returns `I`, register count 7.
- `Lpho;->n()Z` exists, returns `Z`, register count 4.
- `Lqod;->b` still references all three vibration resource ids (the fragment setup).
- `Lpho;->d` still calls `n()Z` and `f(I)V` in the release branch.

### Device test

- **Pixel 6** (the fix target): slider previously absent → now visible; dragging it changes the
  felt key-release vibration; vibration survives an IME restart; "Vibrate on keypress" off →
  nothing.
- **Fold 8** (already working): unchanged — mode was already 1, `n()` already false.
