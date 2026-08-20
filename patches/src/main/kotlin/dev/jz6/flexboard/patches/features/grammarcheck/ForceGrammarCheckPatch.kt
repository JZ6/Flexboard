package dev.jz6.flexboard.patches.features.grammarcheck

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.stringOrNull
import dev.jz6.flexboard.patches.shared.toDescriptor

/**
 * Turns Gboard's grammar check on even when its feature flag never syncs.
 *
 * The settings row for grammar check is gated by the Phenotype flag `enable_grammar_checker`,
 * which defaults to **off** in the APK and only turns on when GMS serves the flag's value.
 * Flags are registered per package **and signing identity**: the manifest meta-data registration
 * stays on the original package name, but a Morphe-patched APK is resigned, so GMS doesn't
 * attribute the flags to Gboard and the sync never lands. Every such flag keeps its compiled-in
 * default — which is why rows like this one vanish from settings on the patched build.
 *
 * Rather than patching the visibility call site (which shares its flag-read shape with other
 * rows and would take thesis-level fingerprinting to hit exactly once), this patch flips the
 * default the flag is constructed with, in the flag holder's `<clinit>`:
 *
 * ```
 * const-string   v0, "enable_grammar_checker"
 * const/4        v1, 0x0                      <- 0x0 becomes 0x1
 * invoke-static  {v0, v1}, Lnxs;->a(Ljava/lang/String;Z)Lnxp;
 * ```
 *
 * If GMS ever did sync flags for this build, the server value would override ours — which is
 * what Gboard's users see upstream anyway, so both paths converge on "on".
 *
 * The grammar checker itself may still require its native model to be downloaded; when it isn't
 * present, the row shows and the feature simply underlines nothing, which is the same behaviour
 * stock Gboard ships in locales without a model.
 */
@Suppress("unused")
val forceGrammarCheckPatch = bytecodePatch(
    name = "Grammar Check Row",
    description = "Force the grammar check settings row to appear. The Phenotype flag that " +
        "governs it never syncs on a patched build, so its compiled-in default (off) sticks.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        GrammarCheckerFlagClinitFingerprint.method.forceDefaultOn()
    }
}

/**
 * The holder of Gboard's spell/grammar flag set. Its `<clinit>` is a monotone block of
 * `const-string` + `const/4` + `Lnxs;->a` triples — one per flag — and the string is what R8
 * cannot move, so we anchor on it and assert the shape rather than trusting a line number.
 */
private object GrammarCheckerFlagClinitFingerprint : Fingerprint(
    definingClass = "Ljpf;",
    name = "<clinit>",
    parameters = emptyList(),
    returnType = "V",
)

private fun MutableMethod.forceDefaultOn() {
    val instructions = instructions.toList()

    val keyIndex = instructions.indexOfFirst { it.stringOrNull() == "enable_grammar_checker" }
    check(keyIndex >= 0) {
        "const-string \"enable_grammar_checker\" not found in ${this.toDescriptor()} — " +
            "the grammar flag is no longer initialised in this <clinit>"
    }

    // The default is the literal right after the key, fed straight into the flag constructor.
    val tail = instructions.drop(keyIndex + 1)
    val defaultIndex = keyIndex + 1 + tail.indexOfFirst { instruction ->
        instruction is OneRegisterInstruction && when (instruction) {
            is NarrowLiteralInstruction -> instruction.narrowLiteral
            is WideLiteralInstruction -> instruction.wideLiteral
            else -> null
        } == 0L
    }
    check(defaultIndex > keyIndex) {
        "No zero literal follows \"enable_grammar_checker\" in ${this.toDescriptor()} — " +
            "the flag's default is no longer false"
    }
    val defaultInstruction = instructions[defaultIndex] as OneRegisterInstruction

    // The constructor call must be right beside it; if it isn't, this isn't the flag's default
    // (it might be some unrelated constant now) and patching would silently corrupt the init.
    val constructorCallIndex = instructions.withIndex().drop(defaultIndex + 1).firstOrNull { (_, it) ->
        it is com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction &&
            it.reference.toString().contains("Lnxs;->a(Ljava/lang/String;Z)")
    } ?: error(
        "The flag constructor ${"Lnxs;->a(Ljava/lang/String;Z)Lnxp;"} is not called after " +
            "\"enable_grammar_checker\" in ${toDescriptor()} — the flag is initialised " +
            "differently on this build"
    )
    check(constructorCallIndex.index - defaultIndex <= 4) {
        "\"enable_grammar_checker\"'s constructor call is ${constructorCallIndex.index - defaultIndex} " +
            "instructions after its default in ${toDescriptor()} — not the " +
            "const-string + const/4 + invoke triple this patch expects"
    }

    replaceInstruction(
        defaultIndex,
        "const/4 v${defaultInstruction.registerA}, 0x1",
    )
}
