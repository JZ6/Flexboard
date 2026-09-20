package dev.jz6.flexboard.patches.features.inlinesuggestions

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.forceFlagsOn

/**
 * The rollout flag that gates inline autofill suggestions on the keyboard.
 *
 * Reported as "inline autofill does not work in Flexboard, but does in stock Gboard on the same
 * device, with the suggestion strip on, installed as a clone".
 *
 * ### Why a clone loses it
 *
 * A Phenotype default compiled into the APK is a *fallback*. The value Gboard actually runs with
 * arrives from Google's servers, per package, as a rollout. A renamed package has no registered
 * configuration, nothing is delivered, and every flag falls back to whatever shipped — which for
 * this one is off. Stock Gboard on the same device gets the rollout and works.
 *
 * So the rename is the correlation and the missing Phenotype delivery is the mechanism. It is the
 * same shape as Rambler and modern haptics: the APK's defaults are not what Google runs.
 *
 * ### The gate, read out of the dex
 *
 * `Loni;` is `InlineSuggestionCandidateViewController` — it owns `initializeInlineSuggestionViews`
 * and `submitInlineSuggestion`. Its `x()Z` is asked from four places before a candidate is shown:
 *
 * ```
 * Loni;->x()Z
 *    0: if this.O -> false
 *    6: if this.P -> false
 *   11: if this.Q -> false
 *   16: sget Lonj;->b            # enable_inline_suggestions_on_client_side
 *   28: if !flag -> false        # ← ships 0, so it stops here
 *   31: if !this.f -> false
 *   36: if !this.N -> false
 *   41-63: Loxv;->y(), Loru;->z(), Lmel;->v()
 *   66: return true
 * ```
 *
 * ### Necessary, not proven sufficient
 *
 * Five other conditions and three interface calls sit in that method, and they are runtime state
 * rather than flags — nothing here can say statically whether they hold on a given device. Forcing
 * the flag removes the one blocker that is visible and fixed. If suggestions still do not appear,
 * the answer is above or below this flag and not in it.
 *
 * `enable_inline_suggestions_on_decoder_side` is deliberately **not** forced. It also ships off, but
 * nothing has established that it gates display rather than something in the decoder, and forcing
 * flags on suspicion is how Rambler cost four installs. One flag, one install, one answer.
 */
@Suppress("unused")
val inlineSuggestionsPatch = bytecodePatch(
    name = "Inline autofill suggestions",
    description = "Restores the inline suggestions password managers and autofill services show " +
        "above the keyboard. Gboard ships this feature switched off and relies on Google enabling " +
        "it per install, which never happens for a renamed package — so installing as a Gboard " +
        "clone loses it. This forces it on. Off by default until it has been confirmed on a device.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        // Isolating, and the reason is the one that broke Rambler's third attempt. This flag does
        // write its own `const/4 v1, #0` between its name and its factory call, which looks like
        // sole ownership — but a later flag in the same <clinit> reads v1 again without rewriting
        // it. Owning the constant written *before* you says nothing about who reads it *next*.
        // Rewriting it in place would switch on whatever that sibling gates.
        forceFlagsOn(
            "enable_inline_suggestions_on_client_side",
            isolating = setOf("enable_inline_suggestions_on_client_side"),
        )
    }
}
