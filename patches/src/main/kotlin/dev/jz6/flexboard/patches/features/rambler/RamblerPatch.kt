package dev.jz6.flexboard.patches.features.rambler

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.forceFlagsOn
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.sole
import dev.jz6.flexboard.patches.shared.stringOrNull

/**
 * The activation-mode discriminator, and the whole reason this feature looked impossible.
 *
 * `Lmqk;->c()` is one comparison: `ad_activation_type == La;->ad(4)`, and `ad(4)` computes `4 - 2`.
 * The flag ships as 1, so the comparison is false and every boolean above it is irrelevant. It is a
 * `long`, declared through the `J` flag factory rather than the `Z` one.
 */
private const val AD_ACTIVATION_TYPE = "ad_activation_type"

/** What `La;->ad(4)` evaluates to. Not a version number — an enum ordinal the gate tests against. */
private const val AGENTIC_ACTIVATION = 2L

/** Ships as this. Asserted, so a build that already moved on fails loudly rather than being re-set. */
private const val STOCK_ACTIVATION = 1L

private fun activationTypeHolderFingerprint() = Fingerprint(
    accessFlags = listOf(com.android.tools.smali.dexlib2.AccessFlags.STATIC),
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    strings = listOf(AD_ACTIVATION_TYPE),
)

/**
 * Turns on Google Rambler — Gboard's agentic dictation, internally *jetson*.
 *
 * ## What was actually in the way
 *
 * Six conditions gate `Lmev;->B(Context)`: three booleans, a module registration, a user preference,
 * and [AD_ACTIVATION_TYPE]. Only the last one is interesting. It is a `long`, and this project's
 * flag helper is boolean-only, which was originally read as "out of reach" — wrongly.
 * `ToolbarCapacityPatch` has rewritten a long-valued flag literal in place since the toolbar work,
 * and that is all this needs. See `docs/phenotype-flags.md`.
 *
 * ## What this does and does not do
 *
 * It **exposes the option**; it does not switch the feature on behind the user's back. Once the
 * flags are set, Rambler appears as a choice in Gboard's Voice settings, and choosing it is what
 * writes the `enable_jetson` preference the remaining conditions read. That is deliberate: the
 * feature talks to a server, has a quota and records a consent, and none of those are things a
 * patch should accept on someone's behalf.
 *
 * Only three of the six flags in this family are actually off. `config_agentic_dictation`,
 * `enable_jetson_in_toolbar` and `filter_rambler_contributed_input_view_session` all resolve to 1
 * on 18.0.3 — the last two by sharing a constant that holds 1, which is why "hoisted" is not a
 * synonym for "off" and why the effective value has to be read rather than inferred from the
 * sharing.
 *
 * All three that *are* off use the isolating emission, and the reason differs. The two rambler
 * toolbar flags inherit a constant written earlier. `enable_agentic_dictation` writes its own —
 * and is still shared, because `agentic_dictation_enable_promo_banner` reads the same register
 * afterwards without rewriting it. Owning the constant that precedes you says nothing about who
 * reads it next; both directions have to be checked, and both now are.
 *
 * **Unverified on a device.** Off by default.
 */
@Suppress("unused")
val ramblerPatch = bytecodePatch(
    name = "Enable Rambler",
    description = "Exposes Google Rambler — Gboard's agentic dictation, which rewrites what you " +
        "say into composed text — as a choice in Voice settings. It is not switched on for you: " +
        "the feature uses a Google server, has its own quota and asks for consent, so picking it " +
        "is left to you. Off by default and not yet confirmed working on a device.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        // Only the flags Gboard actually ships off. Three of the six in this family already
        // default to 1 on 18.0.3 -- config_agentic_dictation with a constant of its own, and
        // enable_jetson_in_toolbar and filter_rambler_contributed_input_view_session by sharing
        // one that holds 1. Forcing a flag that is already on is refused by forceFlagsOn, on the
        // grounds that it claims credit for nothing and hides a real change, and it was right to.
        forceFlagsOn(
            "enable_agentic_dictation",
            "enable_rambler_al_toolbar",
            "enable_rambler_toolbar_at_cursor_position",
            // All three are isolated. The two rambler toolbar flags read a constant hoisted from
            // earlier in the <clinit>. enable_agentic_dictation writes its own `const/4 v1, #0` --
            // but `agentic_dictation_enable_promo_banner` then reads v1 again at index 38 without
            // rewriting it, so the constant is shared *forward* rather than inherited. Rewriting
            // it in place would switch on a promo banner nobody asked for.
            isolating = setOf(
                "enable_agentic_dictation",
                "enable_rambler_al_toolbar",
                "enable_rambler_toolbar_at_cursor_position",
            ),
        )

        raiseActivationType()
    }
}

/**
 * Rewrites [AD_ACTIVATION_TYPE]'s declared default from [STOCK_ACTIVATION] to [AGENTIC_ACTIVATION].
 *
 * A literal rewrite rather than an override, because the value is read once at class initialisation
 * and compared, not consulted per call. Like-for-like: a `const-wide/16` is replaced by a
 * `const-wide/16`, so the register pair is preserved.
 */
private fun app.morphe.patcher.patch.BytecodePatchContext.raiseActivationType() {
    val method = activationTypeHolderFingerprint().method
    val body = method.instructions.toList()

    val nameIndex = body.withIndex()
        .filter { (_, instruction) -> instruction.stringOrNull() == AD_ACTIVATION_TYPE }
        .sole { "\"$AD_ACTIVATION_TYPE\" is loaded $it times in ${method.definingClass}, expected 1" }
        .index

    // The declaration is const-string / const-wide / invoke-static, in that order. Searching a short
    // window rather than assuming adjacency, for the same reason the undo-autocorrect anchor does.
    val defaultIndex = (nameIndex + 1 until minOf(nameIndex + 5, body.size))
        .firstOrNull { body[it].opcodeName().startsWith("CONST_WIDE") }
        ?: error(
            "No const-wide follows \"$AD_ACTIVATION_TYPE\" in ${method.definingClass} — it is no " +
                "longer declared as a long flag, and writing a long into whatever it became would " +
                "corrupt it",
        )

    val literal = (body[defaultIndex] as com.android.tools.smali.dexlib2.iface.instruction
        .WideLiteralInstruction).wideLiteral
    check(literal == STOCK_ACTIVATION) {
        "\"$AD_ACTIVATION_TYPE\" already defaults to $literal, not $STOCK_ACTIVATION — Gboard has " +
            "changed the activation mode it ships, and forcing $AGENTIC_ACTIVATION over the top " +
            "would be guessing rather than enabling"
    }

    val register = (body[defaultIndex] as OneRegisterInstruction).registerA
    method.replaceInstruction(defaultIndex, "const-wide/16 v$register, 0x$AGENTIC_ACTIVATION")
}
