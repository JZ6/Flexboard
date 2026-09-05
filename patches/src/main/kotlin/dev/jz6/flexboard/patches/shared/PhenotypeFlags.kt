package dev.jz6.flexboard.patches.shared

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

/**
 * Flipping the compiled-in default of Gboard's boolean Phenotype flags.
 *
 * ## Why any of this is necessary
 *
 * Phenotype registers flags per package **and signing identity**. A Morphe build is resigned, so
 * GMS never attributes the flags to Gboard, the sync never lands, and every flag keeps whatever
 * default was compiled into the APK. On 18.0.3 that is 666 booleans shipping `false`. Most are
 * genuinely off for everyone — experiments, staged rollouts, dead code — but the ones Google
 * enables server-side are simply lost on a patched build, with no error and no setting to notice.
 *
 * ## The shape, and the trap underneath it
 *
 * Each flag is a triple in some class's `<clinit>`:
 *
 * ```
 * const-string   v0, "enable_grammar_checker"
 * const/4        v1, 0x0                      <- the default; 0x0 becomes 0x1
 * invoke-static  {v0, v1}, Lnxs;->a(Ljava/lang/String;Z)Lnxp;
 * ```
 *
 * The name is a string, so R8 cannot move it, and it is the only durable anchor here. But the
 * boolean register is *reused* down the whole method — six flags in `Lika;` all pass `v1` — so
 * "find a zero near the name" is not enough. What makes the flip safe is that each call
 * re-initialises the register immediately before use, so the constant belongs to exactly one flag.
 *
 * That is checked rather than assumed: [forceFlagsOn] requires the `const` writing the boolean
 * register to sit **between** the name and the call. Where a flag's default is instead hoisted and
 * shared — `super_insert` is one — no such constant exists, the flag is rejected, and nothing is
 * written. Flipping a shared constant would silently enable every other flag reading it, which is
 * the kind of change that produces a half-built feature nobody can trace back to a patch.
 */
private const val BOOLEAN_FLAG_FACTORY = "Lnxs;->a(Ljava/lang/String;Z)Lnxp;"

/** How far after the name the factory call may sit before this stops looking like the triple. */
private const val FACTORY_WINDOW = 5

/**
 * Forces each named boolean flag's compiled-in default to true.
 *
 * Every name must resolve, and each is asserted individually: a flag Gboard has renamed or
 * removed fails the patch rather than being quietly skipped, because "we enabled four of five"
 * is indistinguishable from success on a device.
 */
/**
 * Forces the named boolean Phenotype flags on by rewriting their compiled-in defaults.
 *
 * By default a flag must load its own constant between its name and the factory call. Flags listed
 * in [isolating] are the exception: Gboard hoists one zero and feeds it to several flags in the
 * same `<clinit>`, so there is no constant belonging to the flag alone. Those get a dedicated
 * constant inserted for their call and the shared register put back immediately afterwards, which
 * leaves every sibling on the original zero.
 *
 * [isolating] is opt-in rather than a silent fallback. The strict rule exists because flipping a
 * shared constant turns on flags nobody asked for, and a fallback would hide exactly that.
 */
internal fun BytecodePatchContext.forceFlagsOn(
    vararg names: String,
    isolating: Set<String> = emptySet(),
) {
    require(isolating.all { it in names }) {
        "isolating names a flag that is not being forced on: ${(isolating - names.toSet()).sorted()}"
    }
    val wanted = names.toSet()

    // One pass over every class: the flags are spread across unrelated <clinit>s, and resolving
    // each by its own fingerprint would mean hardcoding five obfuscated class letters that move
    // on every Gboard build. The name string is the only durable anchor, so the class is found
    // by carrying it rather than by being named.
    val holders = methodsMatching { method ->
        method.name == "<clinit>" &&
            method.implementation?.instructions?.any { it.stringOrNull() in wanted } == true
    }

    val flipped = mutableSetOf<String>()
    for (holder in holders) {
        val body = holder.implementation?.instructions?.toList() ?: continue
        val descriptor = holder.toDescriptor()
        val mutable = mutableClassDefBy(holder.definingClass).methods
            .single { it.toDescriptor() == descriptor }

        // Descending, so an earlier flip cannot shift the index of a later one in the same method.
        val sites = body.withIndex()
            .filter { (_, instruction) -> instruction.stringOrNull() in wanted }
            .sortedByDescending { it.index }
        for ((nameIndex, instruction) in sites) {
            val name = instruction.stringOrNull()!!
            mutable.flipFlagDefault(name, nameIndex, body, name in isolating)
            flipped += name
        }
    }

    val missing = wanted - flipped
    check(missing.isEmpty()) {
        "These Phenotype flags are not declared anywhere in the dex: ${missing.sorted()} — " +
            "Gboard has renamed or dropped them, and the features they gate would silently not " +
            "appear. Four of five enabled is indistinguishable from success on a device."
    }
}

private fun MutableMethod.flipFlagDefault(
    name: String,
    nameIndex: Int,
    body: List<Instruction>,
    isolating: Boolean,
) {
    val callIndex = (nameIndex + 1 until minOf(nameIndex + 1 + FACTORY_WINDOW, body.size))
        .firstOrNull { body[it].callsMethod(BOOLEAN_FLAG_FACTORY) }
        ?: error(
            "\"$name\" in ${toDescriptor()} is not followed by $BOOLEAN_FLAG_FACTORY within " +
                "$FACTORY_WINDOW instructions — it is no longer a boolean flag, and whatever it " +
                "is now would be corrupted by writing a boolean into it"
        )

    // Second argument of the call: the default. The first is the name.
    val booleanRegister = body[callIndex].invokeRegisterAt(1)

    // The constant must be this flag's own, written after the name and before the call. A default
    // loaded earlier is shared with every later flag using the same register, and flipping it
    // would turn those on too.
    val defaultIndex = (nameIndex + 1 until callIndex).lastOrNull { index ->
        val instruction = body[index]
        instruction.opcodeName().startsWith("CONST") &&
            (instruction as? OneRegisterInstruction)?.registerA == booleanRegister
    }
    if (defaultIndex == null) {
        check(isolating) {
            "\"$name\" in ${toDescriptor()} takes its default from a register loaded before the " +
                "flag's own name — the constant is shared with other flags in this <clinit>, and " +
                "flipping it would enable them too. Pass it in forceFlagsOn(isolating = ...) to " +
                "give it a constant of its own instead."
        }
        isolateAndFlip(name, nameIndex, callIndex, booleanRegister, body)
        return
    }

    check(!isolating) {
        "\"$name\" in ${toDescriptor()} loads its own default now, so it no longer needs the " +
            "isolating emission — drop it from forceFlagsOn(isolating = ...) rather than leave a " +
            "claim about Gboard's bytecode that stopped being true."
    }
    val literal = when (val instruction = body[defaultIndex]) {
        is WideLiteralInstruction -> instruction.wideLiteral
        is NarrowLiteralInstruction -> instruction.narrowLiteral.toLong()
        else -> error("\"$name\"'s default in ${toDescriptor()} is not a literal")
    }
    check(literal == 0L) {
        "\"$name\" already defaults to $literal in ${toDescriptor()}, not 0 — Gboard ships it on, " +
            "so this patch would be claiming credit for nothing and hiding a real change"
    }

    replaceInstruction(defaultIndex, "const/4 v$booleanRegister, 0x1")
}

/**
 * The emission for a flag whose default is hoisted and shared. Rather than rewrite the shared
 * constant — which would flip every flag reading that register — this writes a one instruction
 * override immediately before the flag's own call and restores the register straight after, so the
 * change is scoped to a single call and the siblings never see it.
 */
private fun MutableMethod.isolateAndFlip(
    name: String,
    nameIndex: Int,
    callIndex: Int,
    booleanRegister: Int,
    body: List<Instruction>,
) {
    // The shared constant still has to be a zero: if Gboard already ships this flag on, the patch
    // would be claiming credit for nothing, exactly as in the dedicated case.
    val sharedIndex = (0 until nameIndex).lastOrNull { index ->
        val instruction = body[index]
        instruction.opcodeName().startsWith("CONST") &&
            (instruction as? OneRegisterInstruction)?.registerA == booleanRegister
    } ?: error(
        "\"$name\" in ${toDescriptor()} reads v$booleanRegister as its default but nothing in " +
            "this <clinit> ever writes that register — the call no longer has the shape this " +
            "patch understands"
    )
    val literal = when (val instruction = body[sharedIndex]) {
        is WideLiteralInstruction -> instruction.wideLiteral
        is NarrowLiteralInstruction -> instruction.narrowLiteral.toLong()
        else -> error("\"$name\"'s shared default in ${toDescriptor()} is not a literal")
    }
    check(literal == 0L) {
        "\"$name\" already defaults to $literal in ${toDescriptor()}, not 0 — Gboard ships it on, " +
            "so this patch would be claiming credit for nothing and hiding a real change"
    }
    // A move-result must stay welded to its invoke, so the restore goes after it, not before.
    val movesResult = body.getOrNull(callIndex + 1)?.opcodeName()?.startsWith("MOVE_RESULT") == true
    val restoreIndex = callIndex + if (movesResult) 2 else 1
    // Higher index first: inserting the override at callIndex would otherwise shift the restore.
    addInstruction(restoreIndex, "const/4 v$booleanRegister, 0x0")
    addInstruction(callIndex, "const/4 v$booleanRegister, 0x1")
}
