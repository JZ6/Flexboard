package dev.jz6.flexboard.patches.features.vibration

import app.morphe.patcher.Fingerprint

/**
 * The vibration provider's mode selector and its suppression gate, both obfuscated.
 *
 * Read the pin honestly: [COMPATIBILITY_GBOARD] is metadata, not a gate. `Patcher` never reads
 * `compatiblePackages` — only the host UI does — so a user on a different build is not stopped,
 * and these two anchors are bare R8 letters. `Lpho;` is matched by class + name + return type, and
 * 133 classes in this dex declare an `n()Z`, 124 of them short obfuscated names of exactly the
 * kind R8 recycles between builds. A moved name here does not fail loudly; it silently matches
 * something else and the constant-return overwrite lands in an unrelated method.
 *
 * The defence is therefore in the patch, not in the pin: [VibrationPatch] asserts the *shape* of
 * each method before overwriting it, so a recycled letter fails the assertion instead of the
 * build. Anchor shape, not names.
 *
 * The one unobfuscated name in the chain is `VibrationDurationPreference`, which is how the mode
 * method was found: its `ap(I)V` calls `Lphk;->a()Lphm;` → `Lphm;->f(I)V`, and the real provider
 * `Lpho;` is the implementation that gets installed at IME start. `Lphn;` is the class the settings
 * fragment and the provider both ask for the mode. None of those three names survives R8, but
 * none of them needs to: the build pin is what holds them.
 */

/** The mode method: returns 1 (Gboard owns vibration), 2 (system owns it), or 3 (none). */
internal const val VIBRATION_MODE_CLASS = "Lphn;"

/** The real provider. `Lphl;` is the no-op stub restored on `onDestroy`. */
internal const val VIBRATION_PROVIDER_CLASS = "Lpho;"

/**
 * `Lphn;->b(Landroid/content/Context;)I` — the settings fragment (`Lqod;->b`) and the provider's
 * own availability check (`Lpho;->h()Z`) both call this. Its return decides which rows survive on
 * the preferences screen and which dispatch path the key-release effect takes.
 */
internal fun vibrationModeFingerprint() = Fingerprint(
    definingClass = VIBRATION_MODE_CLASS,
    name = "b",
    parameters = listOf("Landroid/content/Context;"),
    returnType = "I",
)

/**
 * `Lpho;->n()Z` — the suppression gate on the key-release vibrator path. Returns true to skip
 * `f(I)V` (the `Vibrator.vibrate` call), false to let it run. On modern Pixels (SDK ≥ 33) with the
 * `d:Z` flag set, it returns true and the slider value never reaches the vibrator.
 */
internal fun vibrationSuppressionFingerprint() = Fingerprint(
    definingClass = VIBRATION_PROVIDER_CLASS,
    name = "n",
    // Pinned like its sibling above. Without this the fingerprint matches `n(…)Z` at any arity,
    // which widens an already-large collision surface for no reason.
    parameters = emptyList(),
    returnType = "Z",
)
