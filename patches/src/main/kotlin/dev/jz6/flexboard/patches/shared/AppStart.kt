package dev.jz6.flexboard.patches.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod

/**
 * Handing a `Context` to the extension at Gboard's Application start.
 *
 * Three patches need the same thing — a moment early enough that nothing has read a preference yet,
 * and something to write them with. `LatinApp.applyPreferenceValues` is that moment: it is reached
 * from `Llzd;->onCreate()V`, which `LatinApp` overrides, and it runs before any keyboard is built.
 *
 * ## Why a `Context` and not the store
 *
 * The method's parameter is Gboard's own preference store, and reaching for it is the obvious move.
 * It is also the wrong one. Writing a preference through that store means resolving a setter on an
 * obfuscated class, and reading one means picking the right method from same-signature siblings —
 * `Lqhy;` has two `(String, I)I` getters, one of which parses the value as text, and its id-keyed
 * `contains` has a sibling that answers "is it currently true?" instead of "has it ever been set?".
 * Each of those is a silently-wrong failure waiting for a letter to move.
 *
 * None of it is necessary. A Gboard preference key is just a string resource's value — its store
 * resolves an id through `PreferenceKeyCache`, which is `Resources.getString` behind a map — and
 * the file is an ordinary `SharedPreferences`, which the extension already opens to store what the
 * settings screen writes. So the patch hands over `p0`, the `LatinApp` itself, and everything else
 * happens in Java against framework APIs that cannot be renamed.
 *
 * That leaves exactly one Gboard-shaped assumption per caller — this method's shape — instead of
 * one per accessor.
 *
 * ## The insertion
 *
 * `p0` is `this`, an `Application` and so a `Context`. With `registerCount` 13 and two parameter
 * words it lands in v11, inside the four-bit field a `35c` invoke encodes; emitting a `pN` an
 * invoke cannot address is what produced an unappliable bundle once before, which is why
 * [callAtAppStart] checks rather than assumes. See `docs/register-encoding.md`.
 *
 * **No register is written.** The insertion reads a parameter and calls a static, so it needs no
 * liveness proof, and the three callers cannot interact whatever order they apply in.
 */
fun applyPreferenceValuesFingerprint() = Fingerprint(
    definingClass = "Lcom/google/android/apps/inputmethod/latin/LatinApp;",
    name = "d",
    parameters = listOf(PREFERENCE_STORE_TYPE),
    returnType = "V",
)

/**
 * Emits `invoke-static { p0 }, [descriptor]` at the head of Gboard's Application start.
 *
 * Call as `applyPreferenceValuesFingerprint().method.callAtAppStart(…)` from inside `execute`, where
 * resolving the fingerprint has a patch context to do it with.
 */
internal fun MutableMethod.callAtAppStart(descriptor: String) {
    val registerCount = assertRegisterCount(
        APPLY_PREFERENCES_REGISTER_COUNT,
        APPLY_PREFERENCES,
    )
    check(parameterTypes.map(Any::toString) == listOf(PREFERENCE_STORE_TYPE)) {
        "$APPLY_PREFERENCES takes $parameterTypes, expected a single $PREFERENCE_STORE_TYPE — p0 " +
            "is only known to be a Context because of what this method's signature says"
    }

    val receiver = registerCount - APPLY_PREFERENCES_PARAMETER_WORDS
    check(receiver < PACKED_INVOKE_REGISTER_LIMIT) {
        "p0 of $APPLY_PREFERENCES is v$receiver, which a 35c invoke cannot address; the argument " +
            "would have to be copied out with move-object/from16 first"
    }

    addInstructions(0, "invoke-static { p0 }, $descriptor")
}

private const val APPLY_PREFERENCES =
    "Lcom/google/android/apps/inputmethod/latin/LatinApp;->d(Lqhy;)V"

internal const val PREFERENCE_STORE_TYPE = "Lqhy;"

private const val APPLY_PREFERENCES_REGISTER_COUNT = 13

/** `this` plus the store. */
private const val APPLY_PREFERENCES_PARAMETER_WORDS = 2
