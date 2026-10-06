package dev.jz6.flexboard.patches.features.vibration

import app.morphe.patcher.Fingerprint

/**
 * Gboard's vibration mode selector, obfuscated but checked by shape before overwriting.
 *
 * `COMPATIBILITY_GBOARD` is advisory metadata, not a patcher gate. The old, removed edit to
 * `Lpho;->n()Z` disabled vibration rather than clearing a suppression gate; do not restore it.
 * `Lphn;` remains a bare R8 letter, so a moved name might match another method.
 *
 * The defence is in `vibrationSliderPatch`: it asserts the mode method's shape before overwriting
 * it, so a recycled letter fails at patch time. Anchor shape, not names.
 *
 * The one unobfuscated name in the chain is `VibrationDurationPreference`, which is how the mode
 * method was found: its `ap(I)V` calls `Lphk;->a()Lphm;` → `Lphm;->f(I)V`, and the real provider
 * `Lpho;` is the implementation that gets installed at IME start. `Lphn;` is the class the settings
 * fragment and the provider both ask for the mode. None of those three names survives R8, but
 * this patch uses only the shape-checked `Lphn;->b` and the current stock binding.
 */

/** The mode method: returns 1 (Gboard owns vibration), 2 (system owns it), or 3 (none). */
internal const val VIBRATION_MODE_CLASS = "Lphn;"

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
