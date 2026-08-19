package dev.jz6.flexboard.patches.features.scrubsettings

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.features.scrubdelete.ApplyPreferenceValuesFingerprint
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD

/**
 * Writes Flexboard's starting values into Gboard's preference file on the first run.
 *
 * ## Why a write rather than a default
 *
 * Every other value in this project takes effect as the fallback operand of a preference read
 * emitted into Gboard's bytecode: the number lives in the patch, and an unset preference picks it
 * up. Three literals would have done this the same way.
 *
 * The reason not to is that such a default follows the code. Change it in a later release and it
 * moves every user who never touched the slider — someone who has spent a month with a keyboard
 * finds it different after an update they did not ask for. Writing once inverts that: the first run
 * after installing decides, the value becomes an ordinary stored preference indistinguishable from
 * one the user set, and later releases can pick different starting numbers for new installs without
 * disturbing anyone.
 *
 * ## Why this is one instruction
 *
 * The extension has been able to write preferences all along — it is how the settings screen stores
 * a slider, in plain Java with no bytecode involved. So the patch does not need to reach a setter
 * on Gboard's store at all. It hands the extension a `Context` and gets out of the way; see
 * `Defaults`, and `Preferences` for why the file that `Context` resolves to is the subtle part.
 *
 * Doing it in smali would have meant deriving a string-keyed setter and reusing the `getInt` whose
 * two same-signature siblings on that class are a documented trap — for the same result.
 *
 * ## The insertion
 *
 * `LatinApp.applyPreferenceValues` runs at Application start, before any keyboard is built and so
 * before any of these values is read. `flickSymbolsPatch` and `forceScrubPreferencesPatch` already
 * insert here for the same reason.
 *
 * `p0` is the `LatinApp` itself, an `Application` and therefore a `Context`, so the argument needs
 * no lookup and nothing obfuscated crosses into Java. With `registerCount` 13 and two parameter
 * words it lands in v11, inside the four-bit register field a `35c` invoke can encode — emitting a
 * `pN` an invoke cannot address is what produced an unappliable bundle once before, so the check
 * below is not ceremony. See `docs/register-encoding.md`.
 *
 * **No register is written.** The insertion reads a parameter and calls a static, which is why it
 * needs no liveness proof and why it cannot interact with the other two insertions at this same
 * entry whatever order they apply in.
 */
internal val seedDefaultsPatch = bytecodePatch(
    description = "Writes Flexboard's starting values into Gboard's preference store on first " +
        "run, so they behave as defaults for new installs without moving anyone who has already " +
        "settled on their own.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    extendWith("extensions/extension.mpe")

    execute {
        ApplyPreferenceValuesFingerprint.method.apply {
            val registerCount = implementation?.registerCount
                ?: error("$APPLY_PREFERENCES has no implementation")
            check(registerCount == APPLY_PREFERENCES_REGISTER_COUNT) {
                "$APPLY_PREFERENCES has $registerCount registers, expected " +
                    "$APPLY_PREFERENCES_REGISTER_COUNT — refusing to guess the register mapping"
            }
            check(parameterTypes.map(Any::toString) == listOf(PREFERENCE_STORE)) {
                "$APPLY_PREFERENCES takes $parameterTypes, expected a single $PREFERENCE_STORE"
            }

            // p0 is `this`, and it has to be reachable by a 35c invoke's four-bit register field.
            val receiver = registerCount - APPLY_PREFERENCES_PARAMETER_WORDS
            check(receiver < PACKED_INVOKE_REGISTER_LIMIT) {
                "p0 of $APPLY_PREFERENCES is v$receiver, which a 35c invoke cannot address; the " +
                    "argument would have to be copied out with move-object/from16 first"
            }

            addInstructions(0, "invoke-static { p0 }, $SEED_DEFAULTS")
        }
    }
}

private const val APPLY_PREFERENCES =
    "Lcom/google/android/apps/inputmethod/latin/LatinApp;->d(Lqhy;)V"

private const val PREFERENCE_STORE = "Lqhy;"

private const val APPLY_PREFERENCES_REGISTER_COUNT = 13

/** `this` plus the store. */
private const val APPLY_PREFERENCES_PARAMETER_WORDS = 2

/** A `35c` invoke encodes each register in a nibble. */
private const val PACKED_INVOKE_REGISTER_LIMIT = 16

private const val SEED_DEFAULTS =
    "Ldev/jz6/flexboard/extension/prefs/Defaults;->seed(Landroid/content/Context;)V"
