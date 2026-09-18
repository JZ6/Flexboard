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
    val mentioned = mutableSetOf<String>()
    val elsewhere = mutableMapOf<String, MutableList<String>>()
    // Without this, a moved factory makes every flag fail with "it is no longer a boolean flag",
    // which sends the reader to the flags instead of to the one descriptor that actually moved.
    checkMethodExists(BOOLEAN_FLAG_FACTORY, "the Phenotype boolean flag factory")

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
            // Only where the name actually declares a boolean flag. Elsewhere it is another
            // project's extension, or our own, mentioning the same string -- see
            // [booleanFlagCallIndex]. Recorded either way so the diagnostics below can tell
            // "nobody declares this" apart from "it is declared but no longer a boolean".
            mentioned += name
            if (booleanFlagCallIndex(body, nameIndex) == null) {
                elsewhere.getOrPut(name) { mutableListOf() } += descriptor
                continue
            }
            mutable.flipFlagDefault(name, nameIndex, body, name in isolating)
            flipped += name
        }
    }

    // Three outcomes, and they used to be two. A flag mentioned only in places that never call the
    // factory is not missing -- it is present and has stopped being a boolean, which is the case the
    // old message described and the old control flow could not reach without also failing on
    // unrelated classes.
    val notBoolean = (mentioned - flipped).filter { it in elsewhere }
    check(notBoolean.isEmpty()) {
        notBoolean.sorted().joinToString("; ") { name ->
            "\"$name\" is named in ${elsewhere[name]?.sorted()} but none of those is followed by " +
                "$BOOLEAN_FLAG_FACTORY within $FACTORY_WINDOW instructions"
        } + " — the flag exists and is no longer a boolean, and writing one into it would corrupt it"
    }

    val missing = wanted - flipped
    check(missing.isEmpty()) {
        "These Phenotype flags are not declared anywhere in the dex: ${missing.sorted()} — " +
            "Gboard has renamed or dropped them, and the features they gate would silently not " +
            "appear. Four of five enabled is indistinguishable from success on a device."
    }
}

/**
 * Index of the boolean-flag factory call this flag name feeds, or `null` when it feeds none.
 *
 * Separated out and made pure because it is doing two jobs that used to be one. A site where the
 * name is followed by the factory is a flag *declaration*; a site where it is not is simply some
 * other code that mentions the same string, and the difference decides whether the emission belongs
 * there at all.
 *
 * Conflating them broke a real install. Morphe merges every selected bundle's extension into the dex
 * before patches run, so a scan over "every `<clinit>` mentioning this flag name" sees other
 * projects' classes as well as Gboard's. A user running Flexboard 2.4.1 alongside two other Gboard
 * bundles hit `Ldev/jason/gboardpatches/extension/rambler/GboardRambler1803StockPolicy;-><clinit>`,
 * which names `enable_rambler_toolbar_at_cursor_position` for its own reasons and never calls the
 * factory — and the whole patch run failed on a class that was none of our business.
 */
internal fun booleanFlagCallIndex(body: List<Instruction>, nameIndex: Int): Int? =
    (nameIndex + 1 until minOf(nameIndex + 1 + FACTORY_WINDOW, body.size))
        .firstOrNull { body[it].callsMethod(BOOLEAN_FLAG_FACTORY) }

private fun MutableMethod.flipFlagDefault(
    name: String,
    nameIndex: Int,
    body: List<Instruction>,
    isolating: Boolean,
) {
    val callIndex = booleanFlagCallIndex(body, nameIndex)
        ?: error(
            "\"$name\" in ${toDescriptor()} is not followed by $BOOLEAN_FLAG_FACTORY within " +
                "$FACTORY_WINDOW instructions — it is no longer a boolean flag, and whatever it " +
                "is now would be corrupted by writing a boolean into it"
        )

    // Second argument of the call: the default. The first is the name.
    val booleanRegister = body[callIndex].invokeRegisterAt(1)

    // Which constant feeds this call. Preferring the flag's own window keeps the common case
    // exact; falling back to an earlier write is what makes a hoisted default resolvable at all.
    val ownIndex = (nameIndex + 1 until callIndex).lastOrNull { index -> writesConst(body, index, booleanRegister) }
    val constIndex = ownIndex
        ?: (0 until nameIndex).lastOrNull { index -> writesConst(body, index, booleanRegister) }
        ?: error(
            "\"$name\" in ${toDescriptor()} reads v$booleanRegister as its default but nothing in " +
                "this <clinit> ever writes that register — the call no longer has the shape this " +
                "patch understands"
        )

    val literal = when (val instruction = body[constIndex]) {
        // Order matters: NarrowLiteralInstruction extends WideLiteralInstruction, so the wide arm
        // matches both. A const-wide reaching here would be replaced by a narrow const/4 and orphan
        // its high half, so refuse it by name rather than by interface.
        is WideLiteralInstruction -> {
            check(!instruction.opcodeName().startsWith("CONST_WIDE")) {
                "\"$name\"'s default in ${toDescriptor()} is a ${instruction.opcodeName()} — a " +
                    "64-bit constant, which this patch cannot rewrite as a boolean without " +
                    "leaving the second word of the pair holding a stale value"
            }
            instruction.wideLiteral
        }
        else -> error("\"$name\"'s default in ${toDescriptor()} is not a literal")
    }
    check(literal == 0L) {
        "\"$name\" already defaults to $literal in ${toDescriptor()}, not 0 — Gboard ships it on, " +
            "so this patch would be claiming credit for nothing and hiding a real change"
    }

    // **Both directions.** The window above proves the constant was not inherited from an earlier
    // flag. It says nothing about who inherits it next, and that was the whole bug: in Ljpf; one
    // `const/4 v1, #0` is written once and read by four flag calls, so rewriting it in place turned
    // on three flags nobody asked for — one of them fronting the LLM machinery that has already
    // stopped Gboard starting once.
    val nextWrite = (callIndex + 1 until body.size)
        .firstOrNull { booleanRegister in body[it].destinationRegistersOrEmpty() }
        ?: body.size
    val laterReader = (callIndex + 1 until nextWrite)
        .firstOrNull { booleanRegister in body[it].registersRead() }

    if (ownIndex == null || laterReader != null) {
        check(isolating) {
            val why = if (ownIndex == null) {
                "takes its default from a register loaded before the flag's own name"
            } else {
                "shares its default with ${body[laterReader!!].stringOrNull()?.let { "\"$it\"" } ?: "a later read"} " +
                    "at index $laterReader, which reads v$booleanRegister before anything rewrites it"
            }
            "\"$name\" in ${toDescriptor()} $why — the constant is shared with other flags in this " +
                "<clinit>, and rewriting it would enable them too. Pass it in " +
                "forceFlagsOn(isolating = ...) to give it a constant of its own instead."
        }
        isolateAndFlip(name, callIndex, booleanRegister, body)
        return
    }

    check(!isolating) {
        "\"$name\" in ${toDescriptor()} loads its own default and nothing later reads it, so it no " +
            "longer needs the isolating emission — drop it from forceFlagsOn(isolating = ...) " +
            "rather than leave a claim about Gboard's bytecode that stopped being true."
    }
    replaceInstruction(constIndex, "const/4 v$booleanRegister, 0x1")
}

/** A `const`-family write of [register] at [index]. */
private fun writesConst(body: List<Instruction>, index: Int, register: Int): Boolean {
    val instruction = body[index]
    return instruction.opcodeName().startsWith("CONST") &&
        (instruction as? OneRegisterInstruction)?.registerA == register
}

/**
 * The emission for a flag whose default is hoisted and shared. Rather than rewrite the shared
 * constant — which would flip every flag reading that register — this writes a one instruction
 * override immediately before the flag's own call and restores the register straight after, so the
 * change is scoped to a single call and the siblings never see it.
 */
@Suppress("UNUSED_PARAMETER")
private fun MutableMethod.isolateAndFlip(
    name: String,
    callIndex: Int,
    booleanRegister: Int,
    body: List<Instruction>,
) {
    // The constant itself is left alone; the caller has already proved it is a zero.
    // A move-result must stay welded to its invoke, so the restore goes after it, not before.
    val movesResult = body.getOrNull(callIndex + 1)?.opcodeName()?.startsWith("MOVE_RESULT") == true
    val restoreIndex = callIndex + if (movesResult) 2 else 1
    // Higher index first: inserting the override at callIndex would otherwise shift the restore.
    addInstruction(restoreIndex, "const/4 v$booleanRegister, 0x0")
    addInstruction(callIndex, "const/4 v$booleanRegister, 0x1")
}

/**
 * The `<clinit>` that declares [flag], found by carrying the flag name rather than by being named.
 *
 * A `Fingerprint` with `accessFlags = listOf(STATIC)` does **not** match these: a static
 * initialiser is `STATIC | CONSTRUCTOR` (0x10008), and asking for one flag of the two matched
 * nothing and failed the patch on a device with "Failed to match the fingerprint" and no
 * indication of which. `forceFlagsOn` never had the problem because it resolves holders this way,
 * which is the approach both long-flag rewrites should have used from the start.
 */
internal fun BytecodePatchContext.flagHolderClinit(flag: String): MutableMethod {
    val holder = methodsMatching { method ->
        method.name == "<clinit>" &&
            method.implementation?.instructions?.any { it.stringOrNull() == flag } == true
    }.sole {
        "Expected exactly one <clinit> declaring \"$flag\", found $it. The flag is either gone " +
            "or now declared in more than one place, and rewriting the wrong one would be silent."
    }
    // The mutable counterpart, resolved by descriptor the same way forceFlagsOn does it: the
    // immutable Method is what the search returns, and only the mutable one can be written to.
    val descriptor = holder.toDescriptor()
    return mutableClassDefBy(holder.definingClass).methods.single { it.toDescriptor() == descriptor }
}
