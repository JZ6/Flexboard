package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.jz6.flexboard.patches.features.scrubdelete.PREFERENCE_STORE
import dev.jz6.flexboard.patches.features.scrubdelete.PREFERENCE_STORE_GET
import dev.jz6.flexboard.patches.features.scrubdelete.checkPreferenceStorePins
import dev.jz6.flexboard.patches.features.scrubdelete.resolvePreferenceGetInt
import dev.jz6.flexboard.patches.features.scrubsettings.scrubSettingsScreenPatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.fieldDescriptor
import dev.jz6.flexboard.patches.shared.indexOfSoleCall
import dev.jz6.flexboard.patches.shared.invokeRegisterAt
import dev.jz6.flexboard.patches.shared.invokeRegisterCount
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.toDescriptor

/**
 * Makes the number of icons on Gboard's toolbar adjustable.
 *
 * ## Two numbers, and only one of them is the count
 *
 * This shipped in `1.1.0-dev.1` writing the wrong one, so the distinction leads.
 *
 * The toolbar is the **access points bar**. It holds a *capacity*, [ACCESS_POINTS_BAR]`->m:I`,
 * computed once in its constructor from a Phenotype flag clamped to `[3, 8]`. That number reads like
 * the count and is not: nothing about how many icons appear is decided by it. What the bar renders
 * is simply whatever list it is handed —
 *
 * ```
 * m(List)  ->  d = list.size()          // no clamp against m anywhere
 *              W(list)                  // one child view per element
 * ```
 *
 * — and the list is cut to length somewhere else entirely, in the access-points controller:
 *
 * ```
 * n = min(definedCountOnBar(capacity), list.size())
 * subList(0, n)     -> the bar
 * subList(n, size)  -> the overflow panel
 * ```
 *
 * `definedCountOnBar` is the count. Raising the capacity underneath it changes nothing, for two
 * reasons that stack: it is only that function's *argument*, and the function then puts it through
 * two gates that can each ignore it —
 *
 *  - `min(access_points_count_on_bar, capacity)` whenever Gboard's own preference is set, which pins
 *    the result below the capacity;
 *  - a reduced mode that returns a flat 3 whatever the capacity is.
 *
 * So the read belongs at `definedCountOnBar`, which sits after both gates and *is* the returned
 * count. That is the insertion this patch leads with, and it is the one that makes the slider work.
 *
 * ## Why the capacity is still written
 *
 * [ACCESS_POINTS_BAR]`->m:I` is read in eight more places, and every one of them is asking the same
 * question: *is the bar at capacity?* The chevron swap, the odd-count spacer in `onMeasure`, the
 * reserved-slot bump in `L()`, and — the one that matters — `y(Lmic;I)`, which **evicts the last
 * child** before inserting when it believes the bar is full.
 *
 * Leave the capacity at 5 while eight icons are on the bar and that eviction fires on every slot
 * insert. So both numbers move together, and this patch keeps the constructor insertion for no other
 * purpose. It is bookkeeping, not the feature.
 *
 * ## The evidence that sent the first attempt to the wrong place
 *
 * `1.1.0-dev.1` was diagnosed from the expand chevron failing to appear: `T()` swaps the chevron in
 * whenever `d != m`, so a raised capacity should have produced one even with no extra icons to show,
 * and its absence was read as "the value never reached the field".
 *
 * **That inference was void.** `T()` opens with `if (!D) return`, and `D:Z` is set only by the two
 * temporary-access-point flows (`E`, `F`, `G`). In ordinary use `T()` does nothing at all, so it
 * could not have produced a chevron either way, and the chevron seen in normal operation is not
 * `T()`'s — it is an ordinary access point in the list, marked `expand_label_on_top_bar`.
 *
 * With that struck out, nothing suggests the write failed, and the one defect that *is* established
 * — the capacity is not the count — explains the whole result on its own. The second suspect
 * recorded at the time, that the constructor read might not re-run because views are cached, has no
 * evidence behind it and is not carried forward. It also stops mattering: `definedCountOnBar` is
 * called on every list update rather than at view inflation.
 *
 * ## Why this one is worth a preference read
 *
 * Every preference this project reads is an *insertion*, and an insertion needs registers proved
 * dead against each Gboard build. That is what makes configs expensive here, and why three of them
 * were removed rather than carried. This one is worth the cost twice over:
 *
 *  - **There is no single right value.** How many icons fit depends on how wide the screen is —
 *    [ACCESS_POINTS_BAR]`->K(II)I` gives each item `min((width + 2·padding)/(n + 1), width/n)`, so
 *    more icons simply means narrower ones. Unlike the hold delay, no constant is right everywhere.
 *  - **Both insertion points are cheap.** `definedCountOnBar` is entered with three dead locals and
 *    already holds the preference store in a field, so it needs no `Context` and no liveness
 *    argument at all. The constructor's is the cheapest in the project: an unobfuscated class, a
 *    string literal for an anchor, the `Context` already a parameter, two scratch registers.
 *
 * ## Neither field is named
 *
 * `m:I` is obfuscated, and nothing here writes it down: the insertion goes *before* Gboard's own
 * `iput` and leaves that instruction to do the write, so the patch locates an instruction rather
 * than naming a field. The preference store on the controller is obfuscated too, and is read back
 * out of the very method being patched — the sole field it touches of the store's type.
 *
 * ## Before trusting this on a device
 *
 * `compileKotlin` is blind to Gboard and `preflight.py` is blind to the Kotlin, so neither says
 * anything about behaviour. The version that shipped broken passed both. This is the list that was
 * written and not run:
 *
 *  - the toolbar shows five icons with the slider untouched;
 *  - moving it to 8 and reopening the keyboard shows 8, with the surplus leaving the overflow panel;
 *  - 10 renders without clipping, and 3 works;
 *  - drag-to-reorder and long-press customise still work at every setting;
 *  - Gboard's own settings screens are unaffected.
 *
 * Add one step at the front: **force-stop Gboard** before the first check. The count insertion runs
 * on every list rebuild and does not need it, but the capacity insertion is in a view constructor,
 * so a cached keyboard view can leave the two disagreeing until the process restarts. If 8 icons
 * appear only after a force-stop, that is the capacity lagging and not a failure of the count.
 *
 * ## If it misbehaves
 *
 * The two insertions are independent and can be bisected. Dropping the capacity one leaves the
 * count working with the bar's own full-ness tests reading low — cosmetically wrong around the
 * chevron, and `y(Lmic;I)` may evict on slot inserts, but the icons move. Dropping the count one
 * puts the patch back to exactly what `1.1.0-dev.1` did, which is nothing; there is no point
 * keeping it alone.
 *
 * If the count override itself is the problem — the keyboard fails to open, or the bar comes up
 * empty — the emitted block reduces to a bare `const/16` and `return` with no preference read and
 * no store field, which cannot be upset by anything except the register count. That loses the
 * slider and hardcodes a number, so it is a diagnostic step rather than a shipping fallback.
 */
@Suppress("unused")
val toolbarCountPatch = bytecodePatch(
    name = "Bigger Toolbar",
    description = "Makes the number of icons on the toolbar above the keyboard adjustable, from " +
        "Gboard's own settings. Anything past the limit stays in the overflow menu.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    // The entry that reaches the screen writing this preference. Shipping the reader without it
    // would leave a value nothing can ever set. Both the manifest entry and the settings row it
    // adds are idempotent, so depending on it alongside `scrubTuningPatch` is safe.
    dependsOn(scrubSettingsScreenPatch)

    // Carries FlexboardSettingsActivity, which the manifest entry that patch writes names.
    extendWith("extensions/extension.mpe")

    execute {
        checkPreferenceStorePins()

        // The count. This is the one that makes the slider do anything.
        resolveDefinedCountOnBar().overrideCountFromPreference(this)

        // Resolving it is the assertion that the class is still the one that caps the bar. The
        // fingerprint requires the flag name as a literal, and R8 renames classes, methods and
        // fields but never string contents — so this is the one anchor here that a rebuild of
        // Gboard cannot move.
        AccessPointsBarStaticInitFingerprint.method

        // The capacity, so the bar's own "am I full?" tests agree with the count above.
        AccessPointsBarConstructorFingerprint.method.raiseCapacityFromPreference(this)
    }
}

/**
 * Unobfuscated, because Android instantiates views declared in XML by name and R8 cannot rename
 * what a layout addresses as a string. `res/HNz.xml` declares it.
 */
internal const val ACCESS_POINTS_BAR =
    "Lcom/google/android/libraries/inputmethod/accesspoint/widget/AccessPointsBar;"

/**
 * The Phenotype flag that sets the capacity, read in `<clinit>` with a
 * `ro.com.google.ime.top_icon_num` system-property override. Used here only as the proof that this
 * class is still the thing being patched; the flag's own value is irrelevant once the preference
 * overrides what it computed.
 */
private const val MAX_ACCESS_POINTS_FLAG = "config_max_access_points"

/**
 * The preference key.
 *
 * **Duplicated in `FlexboardSettingsActivity`**, which writes what this reads, along with [MIN] and
 * [MAX] below. They cannot be shared: that class is compiled into the extension DEX, a separate
 * Gradle module with no dependency on the patches. `check_shared_constants.py` is what keeps the two
 * sides honest — a comment alone would let them drift silently.
 */
internal const val TOOLBAR_COUNT_KEY = "flexboard_toolbar_count"

/**
 * The slider's range, and the bounds a stored value has to fall within to be used at all.
 *
 * Three is Gboard's own floor, and the count its reduced mode drops to regardless. Ten is past the
 * eight the flag path accepts, which is as far as Google's own layout has been built against —
 * overriding the count directly means that clamp does not bind us, and nothing clips beyond it, the
 * icons just keep getting narrower.
 */
internal const val TOOLBAR_COUNT_MIN = 3
internal const val TOOLBAR_COUNT_MAX = 10

/**
 * Out of range for the slider, so an unset preference reads as "leave Gboard alone".
 *
 * The count insertion cannot do what the capacity insertion does and pass Gboard's own value as the
 * default, because at the point it runs Gboard has not computed one yet — it runs *first*, and
 * falling through is how the stock number is preserved. So the default only has to be a value the
 * range check rejects.
 */
private const val UNSET_COUNT = "-0x1"

private const val STOCK_COUNT_LABEL = "flexboard_stock_count"

/** A `35c` invoke addresses its registers in 4-bit nibbles, so v15 is the highest usable one. */
private const val PACKED_INVOKE_REGISTER_LIMIT = 16

// -------------------------------------------------------------------------------------------
// The count
// -------------------------------------------------------------------------------------------

/**
 * Gboard's own log line for the order-update callback, and the anchor for the count.
 *
 * A format string is an unusually good anchor. R8 rewrites classes, methods and fields and leaves
 * string contents alone, and this one does not merely identify a location — it *names the value*,
 * in Google's own words, as the defined count on the bar. Nothing derived by shape says that much.
 */
private const val DEFINED_COUNT_LOG =
    "oldVisibleCountOnBar %d, currentVisibleCountOnBar %d, definedCountOnBar %d"

/** Alongside the format string, so the match is pinned to the controller it belongs to. */
private const val LIST_HOLDER_SOURCE_FILE = "AccessPointsListHolderController.java"

/** `this` and the capacity — asserted so the three scratch registers are provably locals. */
private const val DEFINED_COUNT_REGISTER_COUNT = 5
private const val DEFINED_COUNT_PARAMETER_WORDS = 2

/**
 * Dead at the insertion point by construction rather than by analysis.
 *
 * The insertion is at *method entry*, where no local holds anything yet — the strongest position
 * available in this project, and worth saying out loud next to the constructor insertion below,
 * which needs a backward-liveness fixpoint to justify two registers. Here the register count is the
 * whole proof: five registers, two parameter words, so v0–v2 are locals and nothing has run.
 */
private val DEFINED_COUNT_SCRATCH_REGISTERS = listOf(0, 1, 2)

/**
 * The reduced-mode-and-preference gate the count is put through.
 *
 * Asserted rather than used. It is what makes the resolved method the one that *finishes* the
 * calculation rather than one of the steps inside it, which is the whole difference between this
 * insertion and the one that shipped broken.
 *
 * The signature alone does not find it: the store's own id-keyed `getInt` is `(II)I` too, and
 * `preflight.py` failed on exactly that before this was narrowed. What separates them is which one
 * is handed the **capacity parameter** — the gate is the call this method's argument flows into,
 * and the store read is a call it has nothing to do with.
 */
private const val COUNT_GATE_SIGNATURE = "(II)I"

/** The order-update callback, located by the log line naming the value it prints. */
internal object DefinedCountOnBarFingerprint : Fingerprint(
    parameters = emptyList(),
    returnType = "V",
    strings = listOf(DEFINED_COUNT_LOG, LIST_HOLDER_SOURCE_FILE),
)

/**
 * Resolves the method returning the number of access points the bar is to show.
 *
 * It is a single obfuscated letter on an obfuscated class, so it is never named. It is read out of
 * the callback that logs it: within that method there is exactly one call taking an `int` and
 * returning an `int`, and Gboard's own format string says what that value is.
 *
 * The resolved method is then checked to still be shaped like `return gate(…, capacity)`: one
 * [COUNT_GATE_SIGNATURE] call taking its own parameter, and that call's result being what it hands
 * back. A method that no longer does both is not the one this patch means, however well the letters
 * line up — and the register layout the insertion needs is asserted here too, since the emitter
 * below reads the store off `p0`.
 */
private fun BytecodePatchContext.resolveDefinedCountOnBar(): MutableMethod {
    val logging = DefinedCountOnBarFingerprint.method

    val candidates = logging.instructions
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .filter { it.parameterTypes.map(Any::toString) == listOf("I") && it.returnType == "I" }
        .map { it.toString() }
        .distinct()

    check(candidates.size == 1) {
        "Expected exactly one (I)I call in ${logging.toDescriptor()} — the one whose result the " +
            "log line calls definedCountOnBar — but found ${candidates.size}: $candidates"
    }

    val descriptor = candidates.single()
    val owner = descriptor.substringBefore("->")
    val method = mutableClassDefBy(owner).methods.singleOrNull { it.toDescriptor() == descriptor }
        ?: error("$owner does not declare $descriptor")

    check(!AccessFlags.STATIC.isSet(method.accessFlags)) {
        "$descriptor is static, so it has no receiver to read the preference store off"
    }

    val registerCount = method.implementation?.registerCount
        ?: error("$descriptor has no implementation")
    check(registerCount == DEFINED_COUNT_REGISTER_COUNT) {
        "$descriptor has $registerCount registers, expected $DEFINED_COUNT_REGISTER_COUNT — " +
            "refusing to guess which registers are free"
    }

    // `this` and one int parameter, so the capacity is the last register. Asserted above by the
    // signature the candidate was selected on, and by the register count here.
    val capacityRegister = registerCount - 1
    val gates = method.instructions.withIndex().filter { (_, instruction) ->
        val reference =
            (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@filter false
        val signature = "(${reference.parameterTypes.joinToString("")})${reference.returnType}"
        signature == COUNT_GATE_SIGNATURE &&
            (0 until instruction.invokeRegisterCount()).any {
                instruction.invokeRegisterAt(it) == capacityRegister
            }
    }
    check(gates.size == 1) {
        "Expected exactly one $COUNT_GATE_SIGNATURE call taking the capacity in $descriptor — the " +
            "gate that applies Gboard's own count preference and its reduced mode — but found " +
            "${gates.size}. This is no longer the method that finishes the calculation, and " +
            "inserting before it would put the value back where it can be overridden."
    }

    // The other half, and what stops the check above from being satisfied by coincidence: the
    // gate's result has to be what the method hands back. Input and output together say the body is
    // `return gate(…, capacity)` — and overriding at entry only outranks the gate if the gate is
    // the last word on the value.
    val gateIndex = gates.single().index
    val moveResult = method.instructions.getOrNull(gateIndex + 1)
    val returnResult = method.instructions.getOrNull(gateIndex + 2)
    check(
        moveResult?.opcodeName() == "MOVE_RESULT" &&
            returnResult?.opcodeName() == "RETURN" &&
            (moveResult as OneRegisterInstruction).registerA ==
            (returnResult as OneRegisterInstruction).registerA,
    ) {
        "The count gate's result is not what $descriptor returns — it is followed by " +
            "${moveResult?.opcodeName()} then ${returnResult?.opcodeName()}. Something now " +
            "happens to the value after the gate, and overriding at entry would skip it too."
    }

    return method
}

/**
 * Returns the stored count instead of computing one, when a count has been stored.
 *
 * The whole insertion is at method entry, and it either returns or falls through into Gboard's
 * untouched body. That shape is what makes an unset or out-of-range preference *exactly* stock: not
 * a value chosen to look like Gboard's, but Gboard's own code running with nothing done to it.
 * An out-of-range value falls back the same way rather than being clamped into range, because a
 * corrupt or hand-edited preference should read as "unset" and not as a number nobody chose.
 *
 * Overriding here deliberately outranks both of the gates below it — Gboard's own
 * `access_points_count_on_bar`, and the reduced mode that forces 3. Those exist to lower the count,
 * and a user who has moved this slider has said what they want more recently and more explicitly
 * than either.
 *
 * The receiver is addressed as `p0` rather than as a computed register so the emitted code does not
 * depend on the register count being read correctly — though it is asserted anyway, because the
 * scratch registers do depend on it.
 */
private fun MutableMethod.overrideCountFromPreference(context: BytecodePatchContext) {
    // Resolved, not named: the store has a second (String, I)I method that reads the value as text
    // and parses it. Emitting that one would compile, verify and quietly parse a preference that
    // was never written as a string.
    val getInt = context.resolvePreferenceGetInt()

    val registerCount = implementation?.registerCount
        ?: error("${toDescriptor()} has no implementation")
    check(registerCount == DEFINED_COUNT_REGISTER_COUNT) {
        "${toDescriptor()} has $registerCount registers, expected " +
            "$DEFINED_COUNT_REGISTER_COUNT — refusing to guess which registers are free"
    }
    check(registerCount - DEFINED_COUNT_PARAMETER_WORDS == DEFINED_COUNT_SCRATCH_REGISTERS.size) {
        "${toDescriptor()} has ${registerCount - DEFINED_COUNT_PARAMETER_WORDS} local registers, " +
            "and the insertion needs exactly ${DEFINED_COUNT_SCRATCH_REGISTERS.size}"
    }
    check(DEFINED_COUNT_SCRATCH_REGISTERS.all { it < PACKED_INVOKE_REGISTER_LIMIT }) {
        "Scratch registers $DEFINED_COUNT_SCRATCH_REGISTERS do not all fit a 35c invoke's nibbles"
    }

    // The store, read out of the method being patched rather than named. Exactly one field of that
    // type is touched here, and it is declared on the receiver's own class — which is what makes
    // `iget-object … p0` type-correct, without resolving anything on the class itself. Which
    // register Gboard's own read uses does not matter; preflight asserts it is p0 there too, as a
    // consistency check rather than a requirement of this emission.
    val storeReads = instructions.filter {
        it.opcodeName() == "IGET_OBJECT" && it.fieldReferenceOrNull()?.type == PREFERENCE_STORE
    }
    check(storeReads.size == 1) {
        "Expected exactly one $PREFERENCE_STORE field read in ${toDescriptor()}, found " +
            "${storeReads.size} — the preference store is no longer reachable from the receiver"
    }
    val storeField = storeReads.single().fieldDescriptor()
    check(storeField.substringBefore("->") == definingClass) {
        "$storeField is read off something other than the receiver in ${toDescriptor()}, so it " +
            "cannot be reached with `iget-object … p0`"
    }

    val (store, bound, fallback) = DEFINED_COUNT_SCRATCH_REGISTERS
    val entry = instructions.first()

    addInstructionsWithLabels(
        0,
        """
            iget-object v$store, p0, $storeField
            const-string v$bound, "$TOOLBAR_COUNT_KEY"
            const/4 v$fallback, $UNSET_COUNT
            invoke-virtual { v$store, v$bound, v$fallback }, $getInt
            move-result v$store
            const/16 v$bound, $TOOLBAR_COUNT_MIN
            if-lt v$store, v$bound, :$STOCK_COUNT_LABEL
            const/16 v$bound, $TOOLBAR_COUNT_MAX
            if-gt v$store, v$bound, :$STOCK_COUNT_LABEL
            return v$store
        """,
        ExternalLabel(STOCK_COUNT_LABEL, entry),
    )
}

// -------------------------------------------------------------------------------------------
// The capacity
// -------------------------------------------------------------------------------------------

/** `this`, the Context and the AttributeSet — asserted so the scratch registers are provably free. */
private const val BAR_CONSTRUCTOR_REGISTER_COUNT = 9
private const val BAR_CONSTRUCTOR_PARAMETER_WORDS = 3

/**
 * Dead at the `iput`, by backward liveness over the real control flow.
 *
 * Not by reading forward from the insertion point: v2 is written two instructions later and v5 four
 * before, so a first-touch scan would reach the same answer here by luck, and the same scan gets
 * `ScrubMotionEventHandler->r` wrong in a way that corrupts a word count silently. `preflight.py`
 * runs the fixpoint properly and guards that v0, v1, v3 and v7 are correctly *not* free.
 */
private val TOOLBAR_SCRATCH_REGISTERS = listOf(2, 5)

/**
 * `AccessPointsBar.<clinit>`, required to still declare the flag by name.
 *
 * Matching on the string is the whole point: it is a semantic assertion that this class is the one
 * that caps the toolbar, and it survives obfuscation in a way that no member name does.
 */
internal object AccessPointsBarStaticInitFingerprint : Fingerprint(
    definingClass = ACCESS_POINTS_BAR,
    name = "<clinit>",
    parameters = emptyList(),
    returnType = "V",
    strings = listOf(MAX_ACCESS_POINTS_FLAG),
)

/**
 * The two-argument view constructor, which is the one XML inflation calls and the only place
 * `->m:I` is ever written.
 */
internal object AccessPointsBarConstructorFingerprint : Fingerprint(
    definingClass = ACCESS_POINTS_BAR,
    name = "<init>",
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;"),
    returnType = "V",
)

/**
 * Overrides the computed capacity with the stored count, immediately before it is written.
 *
 * This does **not** move the number of icons — see the class KDoc. It keeps the bar's own
 * "am I full?" tests agreeing with the count the controller was given, which matters most for
 * `y(Lmic;I)`, where a bar that believes itself full evicts its last child before inserting.
 *
 * ## Locating the write
 *
 * Two anchors, in order, neither of them a member name — on top of the string literal the
 * `<clinit>` fingerprint has already matched, which is what says this class is the toolbar's cap at
 * all:
 *
 *  1. the single call returning the flag's boxed value, `Lnxp;->g()Ljava/lang/Object;`;
 *  2. the single `iput` after it whose **field type** is `I`.
 *
 * The type is what does the work in step 2. `iput` (`0x59`) covers int and float alike, so filtering
 * on the opcode would also catch `->e:F` and `->f:F`, the two dimensions read out of the same
 * `TypedArray` just below. Filtering after the flag call is what excludes `->y:I`, which is written
 * up at offset 31.
 *
 * ## What is emitted
 *
 * The stored value is read with Gboard's own computed capacity as its **default**, so an unset
 * preference leaves the register exactly as Gboard left it and the behaviour is stock down to the
 * instruction.
 *
 * The insertion lands at the start of a `try` range whose handler recycles the `TypedArray` and
 * rethrows. That is benign in both directions — whether or not the range ends up covering these
 * instructions, a throw from the store propagates out of the constructor either way, and neither
 * path leaves anything half-written.
 *
 * `v5` is left holding a `String` where the two paths merge. Safe for the same reason the undo
 * epilogue is: the register is dead there, so nothing reads it before it is written again.
 */
private fun MutableMethod.raiseCapacityFromPreference(context: BytecodePatchContext) {
    val getInt = context.resolvePreferenceGetInt()

    val registerCount = implementation?.registerCount
        ?: error("$ACCESS_POINTS_BAR-><init> has no implementation")
    check(registerCount == BAR_CONSTRUCTOR_REGISTER_COUNT) {
        "$ACCESS_POINTS_BAR-><init> has $registerCount registers, expected " +
            "$BAR_CONSTRUCTOR_REGISTER_COUNT — refusing to guess which registers are free"
    }

    // The Context is the constructor's own first parameter, so unlike the scrub patches there is no
    // field to resolve and nothing to prove assignable: the signature the fingerprint matched on
    // already says what this register holds.
    val contextRegister = registerCount - BAR_CONSTRUCTOR_PARAMETER_WORDS + 1

    val flagIndex = instructions.indexOfSoleCall(FLAG_ACCESSOR, "$ACCESS_POINTS_BAR-><init>")

    val capacityWrites = instructions.withIndex().filter { (index, instruction) ->
        index > flagIndex &&
            instruction.opcodeName() == "IPUT" &&
            instruction.fieldReferenceOrNull()?.type == "I"
    }
    check(capacityWrites.size == 1) {
        "Expected exactly one int-typed field write after $FLAG_ACCESSOR in " +
            "$ACCESS_POINTS_BAR-><init>, found ${capacityWrites.size} — the capacity is no longer " +
            "the one value stored from the flag comparison"
    }
    val capacityIndex = capacityWrites.single().index
    val capacityWrite = capacityWrites.single().value

    // The register Gboard's own `iput` is about to read. Writing into it is the whole edit; the
    // instruction itself is left exactly as it is.
    val capacityRegister = (capacityWrite as? TwoRegisterInstruction)?.registerA
        ?: error("The capacity write in $ACCESS_POINTS_BAR-><init> is not a two-register `iput`")

    val (store, scratch) = TOOLBAR_SCRATCH_REGISTERS
    check(TOOLBAR_SCRATCH_REGISTERS.distinct().size == TOOLBAR_SCRATCH_REGISTERS.size) {
        "Scratch registers $TOOLBAR_SCRATCH_REGISTERS are not distinct"
    }
    check(capacityRegister !in TOOLBAR_SCRATCH_REGISTERS &&
        contextRegister !in TOOLBAR_SCRATCH_REGISTERS) {
        "Scratch registers $TOOLBAR_SCRATCH_REGISTERS collide with the capacity " +
            "(v$capacityRegister) or the Context (v$contextRegister) in $ACCESS_POINTS_BAR-><init>"
    }
    check(TOOLBAR_SCRATCH_REGISTERS.all { it < PACKED_INVOKE_REGISTER_LIMIT }) {
        "Scratch registers $TOOLBAR_SCRATCH_REGISTERS do not all fit a 35c invoke's nibbles"
    }

    addInstructionsWithLabels(
        capacityIndex,
        """
            invoke-static { v$contextRegister }, $PREFERENCE_STORE_GET
            move-result-object v$store
            const-string v$scratch, "$TOOLBAR_COUNT_KEY"
            invoke-virtual { v$store, v$scratch, v$capacityRegister }, $getInt
            move-result v$store
            const/16 v$scratch, $TOOLBAR_COUNT_MIN
            if-lt v$store, v$scratch, :$STOCK_COUNT_LABEL
            const/16 v$scratch, $TOOLBAR_COUNT_MAX
            if-gt v$store, v$scratch, :$STOCK_COUNT_LABEL
            move v$capacityRegister, v$store
        """,
        ExternalLabel(STOCK_COUNT_LABEL, capacityWrite),
    )
}

/**
 * The flag supplier's getter. Pinned rather than derived: it is used only to *order* the search for
 * the capacity write, and if it moves the count assertion below fails loudly rather than patching
 * something else.
 */
private const val FLAG_ACCESSOR = "Lnxp;->g()Ljava/lang/Object;"

private fun Instruction.fieldReferenceOrNull(): FieldReference? =
    (this as? ReferenceInstruction)?.reference as? FieldReference
