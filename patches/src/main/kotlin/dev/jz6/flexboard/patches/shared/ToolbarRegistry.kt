package dev.jz6.flexboard.patches.shared

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * One toolbar button registered through Gboard's **own** access-point machinery, rather than
 * spliced into the split method's list.
 *
 * Handles a button end-to-end: an allowed-set id, a label (string resource id or a literal),
 * an icon, and a `Runnable` click action. The action may take a single `Int` constructor
 * argument — one class in the extension can serve many buttons this way, told apart by an
 * ordinal (see `TextAction.java`).
 *
 * ## What makes this "native"
 *
 * The access-point id has to be picked from Gboard's allowed set — `res/array/…` id
 * `0x7f0300dc`, read once at startup into the order manager. Any other string is dropped by the
 * read filter before the customize UI ever writes the order back, so a button keyed on it can
 * be dragged but never persisted.
 *
 * With an allowed id, the whole Gboard-native flow then just happens: the read filter passes,
 * the controller's register call lands the definition in the registry map and folds the id into
 * the shown order, the customize-write path stores the order string verbatim, and on every
 * reload the order manager re-folds any registered id back in.
 *
 * ## Where the call goes
 *
 * The bar controller's constructor. By its tail the registry map is initialized and the order
 * manager has already read the allowed set from resources, so a registration here is a full
 * native registration at the one instant that predates every observer. The emission anchors
 * on the tail `return-void`, writes into dead scratch registers `v0`/`v1`/`v2`, and leaves the
 * receiver `p0` untouched.
 */
internal data class NativeToolbarButton(
    /** The access-point id the button is registered under. Must be in the allowed-set. */
    val id: String,
    /**
     * The drawable resource id, as an smali-readable hex literal (e.g. `"0x7f080218"`). Pick one
     * Gboard bundles — `tools/apk/glyphs.py` finds unused Material shapes.
     */
    val icon: String,
    /**
     * The label as a Gboard string-resource hex id, e.g. `"0x7f140576"`. Mutually exclusive with
     * [labelLiteral]; a resource is preferable because it gets translated for free.
     */
    val labelRes: String? = null,
    /**
     * The label as a literal string written straight into the builder's pass-through field.
     * The completeness bit still has to be set, so when a literal is given the resource-id
     * setter is called with `0` first. Must be a smali-safe string — no `"`, no newlines,
     * no `\`, because this flows into a `const-string` operand unparsed.
     */
    val labelLiteral: String? = null,
    /** Same shape as label. Defaults to whatever the label uses. Mutually exclusive per-row. */
    val contentDescriptionRes: String? = null,
    val contentDescriptionLiteral: String? = null,
    /**
     * The extension-side `Runnable` click action, as a full constructor descriptor —
     * `"Ldev/jz6/flexboard/extension/toolbar/TestAction;-><init>()V"` or
     * `"Ldev/jz6/flexboard/extension/textaction/TextAction;-><init>(I)V"`, not just the class
     * name. The helper extracts the class half for `new-instance`, and hands the full string to
     * `invoke-direct`.
     *
     * Declaring it as a `const val` in the patch file is what lets `check_shared_constants.py`
     * see the emission across the helper boundary and verify the Java side actually declares
     * `implements Runnable`.
     */
    val actionCtor: String,
    /**
     * `Int` constructor arguments, loaded as `const/4` (or `const/16` above 7) before the
     * `<init>` invoke. Must match [actionCtor]'s parameter list. At most one Int slot is
     * emitted today — the shape that needs more is also the place to generalize this.
     */
    val actionArgs: List<Int> = emptyList(),
) {
    init {
        require((labelRes != null) != (labelLiteral != null)) {
            "Exactly one of labelRes / labelLiteral must be set on $id"
        }
        require(
            contentDescriptionRes == null || contentDescriptionLiteral == null,
        ) {
            "At most one of contentDescriptionRes / contentDescriptionLiteral must be set on $id"
        }
        require(actionCtor.startsWith("Ldev/jz6/flexboard/extension/")) {
            "actionCtor on $id must live in the extension: $actionCtor"
        }
        val paramList = actionCtor.substringAfter("-><init>(", "").substringBefore(")")
        require(actionCtor.contains("-><init>(")) {
            "actionCtor on $id must be a constructor descriptor: $actionCtor"
        }
        require(paramList.all { it == 'I' }) {
            "actionCtor on $id must take only Int parameters (one per actionArgs entry): $actionCtor"
        }
        require(paramList.length == actionArgs.size) {
            "actionCtor on $id declares ${paramList.length} Int parameters but " +
                "actionArgs has ${actionArgs.size}: $actionCtor vs $actionArgs"
        }
        require(actionArgs.size <= 1) {
            "actionArgs on $id carries ${actionArgs.size} parameters; only one Int slot is " +
                "emitted today, and the shape that needs more is also the place to generalize this"
        }
        actionArgs.forEach { arg ->
            require(arg in -8..MAX_CONST_16_SAFE) {
                "actionArgs on $id contains $arg — const/4 only encodes -8..7 and const/16 only " +
                    "down to -32768; outside that range the emitted smali fails to assemble"
            }
        }
        labelLiteral?.let { requireSmaliSafe(it, "labelLiteral", id) }
        contentDescriptionLiteral?.let { requireSmaliSafe(it, "contentDescriptionLiteral", id) }
    }

    /** The content-description spec: its own if given, the label's otherwise. */
    val effectiveContentDescriptionRes: String? get() = contentDescriptionRes ?: labelRes
    val effectiveContentDescriptionLiteral: String? get() = contentDescriptionLiteral ?: labelLiteral
}

// Smali constants are uninterpreted text — a `"`, `\`, or a newline breaks assembly.
private fun requireSmaliSafe(literal: String, what: String, id: String) {
    require(
        !literal.contains('"') &&
            !literal.contains('\\') &&
            !literal.contains('\n') &&
            !literal.contains('\r'),
    ) {
        "$what on $id contains a character smali can't carry unparsed — " +
            "use the resource-id variant for that shape"
    }
}

/** The bar-controller's `<init>` register count on Gboard 18.0.3 — the value the insertion
 * assumes. A Gboard bump that moves this is asserted by preflight. */
private const val CONTROLLER_INIT_REGISTER_COUNT = 13

/** `const/4` encodes a 4-bit signed value (-8..7). Larger-or-more-negative args use `const/16`. */
private const val MAX_CONST_4_VALUE = 7

/** `const/16` encodes a 16-bit signed value; the emission does not reach below it. */
private const val MAX_CONST_16_SAFE = 32767

/** The bar-versus-overflow split, identified by what it does to its `List` parameter. */
private fun Method.splitsAccessPoints(): Boolean {
    if (parameterTypes.map(Any::toString) != listOf("Ljava/util/List;")) return false
    if (returnType != "V") return false
    val called = calledDescriptors()
    return called.count { it == "Ljava/util/List;->subList(II)Ljava/util/List;" } == 2 &&
        called.any { it == "Ljava/lang/Math;->min(II)I" }
}

// -------------------------------------------------------------------------------------------
// Where the call goes
// -------------------------------------------------------------------------------------------

/**
 * Emits one block per button that builds its access point with the existing builder and calls
 * the bar controller's register method on it. All blocks sit at the same hook point — the tail
 * of `<init>` — and execute in patch-application order, which is not something this layer needs
 * to care about: order in the registry is irrelevant to order on the bar.
 */
internal fun BytecodePatchContext.emitNativeToolbarButtons(
    builder: AccessPointBuilder,
    buttons: List<NativeToolbarButton>,
) {
    check(buttons.isNotEmpty()) { "emitNativeToolbarButtons called with no buttons" }

    // Anchor the bar-controller class on the split method — shape-derived, not name-derived.
    val splits = methodsMatching { it.splitsAccessPoints() }
    check(splits.size == 1) {
        "The bar-controller anchor moved: expected exactly one method that splits a List around " +
            "subList+Math.min, found ${splits.size}: ${splits.map { it.toDescriptor() }}"
    }
    val controllerType = splits.single().definingClass
    val controllerClass = classDefByOrNull(controllerType)
        ?: error("$controllerType is not in the APK; the bar controller cannot be hooked")

    // The register call's name is a one-letter R8 alias on every Gboard build and changes
    // underneath us; what does not change is the *shape* — a (ApType, Z)V method on the
    // controller that Lays.put's into the registry map.
    val registerCall = resolveControllerRegisterCall(controllerClass)
    val initDef = resolveInitDef(controllerType, controllerClass)
    val initDescriptor = initDef.toDescriptor()
    val init = mutableClassDefBy(controllerType).methods.single {
        it.toDescriptor() == initDescriptor
    }
    init.assertRegisterCount(CONTROLLER_INIT_REGISTER_COUNT, initDescriptor)

    val tailIndex = init.implementation!!.instructions
        .indexOfLast { it.opcodeName() == "RETURN_VOID" }
    check(tailIndex >= 0) {
        "$initDescriptor has no return-void — the constructor's shape has changed"
    }

    // Three scratch registers cover everything a button's emission touches: v0 holds the builder
    // then the finished `mic`, v1 holds each argument in turn, and v2 is needed only when an
    // action has an Int ordinal (the action instance sits in v1, the ordinal in v2). The
    // receiver `p0` (v10 at this register count) is read as `g`'s target, never written.
    validateScratchRegisters(
        scratch = listOf(0, 1, 2),
        avoid = listOf(10, 11, 12),
        what = initDescriptor,
    )

    val emission = buttons.joinToString("\n\n") { it.toSmali(builder, registerCall) }
    init.addInstructions(tailIndex, emission)
}

/**
 * The controller's registration call, derived from its *what-it-does* rather than its name. Only
 * one method on the controller matches the `(ApType, Z)V` shape *and* writes into the registry
 * map via `Lays.put`; others are similar in either/or. Shape + call-target together is the pin.
 */
internal fun resolveControllerRegisterCall(controllerClass: ClassDef): String {    val lAysPut = "Lays;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    val candidates = controllerClass.methods.filter { method ->
        val params = method.parameterTypes.map(Any::toString)
        params.size == 2 &&
            params[1] == "Z" &&
            method.returnType == "V" &&
            method.implementation?.instructions?.any { instruction ->
                instruction.opcodeName() == "INVOKE_VIRTUAL" &&
                    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)
                        ?.toString() == lAysPut
            } == true
    }
    check(candidates.size == 1) {
        "The bar controller's register call moved: expected exactly one (*, Z)V method on " +
            "${controllerClass.type} that invokes Lays.put on `h`, found ${candidates.size}: " +
            candidates.map { it.toDescriptor() }
    }
    return candidates.single().toDescriptor()
}

/** The immutable `<init>(Context, ?)` declaration; identified once and shared by the rest. */
internal fun resolveInitDef(
    controllerType: String,
    controllerClass: ClassDef,
): com.android.tools.smali.dexlib2.iface.Method {
    return controllerClass.methods.singleOrNull {
        it.name == "<init>" &&
            it.parameterTypes.size == 2 &&
            it.parameterTypes[0].toString() == "Landroid/content/Context;"
    } ?: error(
        "$controllerType has no <init>(Context, ?) — the bar-controller constructor's shape " +
            "has changed and the hook point must be re-derived",
    )
}

// -------------------------------------------------------------------------------------------
// Emission
// -------------------------------------------------------------------------------------------

/**
 * The smali block that constructs the button's `mic` and registers it with the controller.
 *
 * Idempotence on Gboard's side: a second `g(...)` for the same `id` is a merge over `h`, and
 * `mic.equals` compares the data fields, so a second registration with identical contents is
 * a no-op. Each orientation or IME switch re-instantiates the controller and re-runs this
 * emission, which collapses to the same end state.
 */
private fun NativeToolbarButton.toSmali(
    builder: AccessPointBuilder,
    registerCall: String,
): String {
    val labelSetters = if (labelRes != null)
        """
            const v1, $labelRes
            invoke-virtual { v0, v1 }, ${builder.setLabel}
        """.trimIndent()
    else
        """
            const/4 v1, 0x0
            invoke-virtual { v0, v1 }, ${builder.setLabel}
            const-string v1, "$labelLiteral"
            iput-object v1, v0, ${builder.labelField}
        """.trimIndent()

    val descSetters = if (effectiveContentDescriptionRes != null)
        """
            const v1, $effectiveContentDescriptionRes
            invoke-virtual { v0, v1 }, ${builder.setContentDescription}
        """.trimIndent()
    else
        """
            const/4 v1, 0x0
            invoke-virtual { v0, v1 }, ${builder.setContentDescription}
            const-string v1, "$effectiveContentDescriptionLiteral"
            iput-object v1, v0, ${builder.contentDescriptionField}
        """.trimIndent()

    // v1: the Runnable instance. v2 (scratch, only when an ordinal is passed): the Int load.
    val argSetup = if (actionArgs.isEmpty()) ""
    else {
        val arg = actionArgs.single()
        val constOp = if (arg in -8..MAX_CONST_4_VALUE) "const/4" else "const/16"
        "\n        $constOp v2, $arg"
    }
    val ctorRegisters = if (actionArgs.isEmpty()) "v1" else "v1, v2"

    return """
        invoke-static { }, ${builder.newBuilder}
        move-result-object v0

        const-string v1, "$id"
        invoke-virtual { v0, v1 }, ${builder.setId}

        const v1, $icon
        invoke-virtual { v0, v1 }, ${builder.setIcon}

        $labelSetters
        $descSetters

        new-instance v1, ${actionCtor.substringBefore("->")}$argSetup
        invoke-direct { $ctorRegisters }, $actionCtor
        invoke-virtual { v0, v1 }, ${builder.setAction}

        invoke-virtual { v0 }, ${builder.build}
        move-result-object v0

        const/4 v1, 0x1
        invoke-virtual { p0, v0, v1 }, $registerCall
    """.trimIndent()
}

// -------------------------------------------------------------------------------------------
// Hotkeys: twelve slots, each conditional on the user's live settings
// -------------------------------------------------------------------------------------------

/** The number of hotkey slots the toolbar can hold. */
internal const val HOTKEY_SLOTS = 12

/** `if-eqz` jumps here to skip a slot whose text hasn't been set, or whose slot exceeds the count. */
private const val HOTKEY_SKIP_LABEL = "flexboard_hk_skip"

/**
 * Emits one conditional registration block per hotkey slot at the tail of the bar controller's
 * `<init>`.
 *
 * Unlike [emitNativeToolbarButtons] — where the label, icon and id are constants picked at
 * patch time — every attribute of a hotkey is read at toolbar-build time by the extension's
 * `Hotkeys` class: the id's existence at all is gated by `shown`, and the icon/label/action are
 * derived from the user's settings. The block the emulator builds is therefore identical in
 * *shape* per slot but entirely runtime-populated.
 *
 * Registers (same wiring as the text-action buttons):
 *  - `p0` is the receiver the register call is invoked on;
 *  - `v0` holds the builder then the finished `mic`;
 *  - `v1` carries each argument in turn;
 *  - `v2` is the second Int passed into the action's constructor when it takes an ordinal;
 *  - `v4` is the shown-guard's scratch — dead before and after the block's own use.
 *
 * All twelve blocks sit in one smali string: `addInstructions` parses the internal `:skip_…`
 * labels once, and the label names are per-slot, so the assembler resolves each branch to its
 * own block's end.
 */
internal fun BytecodePatchContext.emitNativeHotkeys(builder: AccessPointBuilder) {
    val splits = methodsMatching { it.splitsAccessPoints() }
    check(splits.size == 1) {
        "The bar-controller anchor moved: expected exactly one method that splits a List around " +
            "subList+Math.min, found ${splits.size}: ${splits.map { it.toDescriptor() }}"
    }
    val controllerType = splits.single().definingClass
    val controllerClass = classDefByOrNull(controllerType)
        ?: error("$controllerType is not in the APK; the bar controller cannot be hooked")
    val registerCall = resolveControllerRegisterCall(controllerClass)
    val initDef = resolveInitDef(controllerType, controllerClass)
    val initDescriptor = initDef.toDescriptor()
    val init = mutableClassDefBy(controllerType).methods.single {
        it.toDescriptor() == initDescriptor
    }
    init.assertRegisterCount(CONTROLLER_INIT_REGISTER_COUNT, initDescriptor)

    val tailIndex = init.implementation!!.instructions
        .indexOfLast { it.opcodeName() == "RETURN_VOID" }
    check(tailIndex >= 0) { "$initDescriptor has no return-void" }

    // p1 is the constructor's Context argument at this register count; p0 is the receiver.
    validateScratchRegisters(
        scratch = listOf(0, 1, 2, 4),
        avoid = listOf(10, 11, 12),
        what = initDescriptor,
    )

    val emission = ((1..HOTKEY_SLOTS).joinToString("\n\n") { slot ->
        hotkeyBlock(slot, builder, registerCall)
    } + "\n\nnop\n").trimIndent()
    // WithLabels: the slot blocks carry twelve distinct internal `:…skip_N` labels, which the
    // plain `addInstructions` rejects — same reason the scrub clamp uses the labelled variant.
    // The trailing `nop` on the last line is not decoration: `addInstructionsWithLabels`
    // (reversed-SubList-walk, `externalLabels[0]` on an empty array → `ArrayIndexOutOfBoundsException:
    // length=0; index=0`) crashes the patcher whenever a branch targets an internal label that has
    // no instruction after it *in the same emission*. Eleven of our labels bind to the next
    // block's opening instruction, but the twelfth would be past-the-end — one `nop` is its home.
    init.addInstructionsWithLabels(tailIndex, emission)
}

/** One slot's conditional registration block. The guard is a single forward jump. */
private fun hotkeyBlock(
    slot: Int,
    builder: AccessPointBuilder,
    registerCall: String,
): String {
    // const/4 only encodes -8..7; slots 8–12 need const/16.
    val constOp = if (slot in -8..7) "const/4" else "const/16"

    return """
        $constOp v4, $slot
        invoke-static { p1, v4 }, $HOTKEYS_SHOWN
        move-result v4
        if-eqz v4, :$HOTKEY_SKIP_LABEL$slot

        invoke-static { }, ${builder.newBuilder}
        move-result-object v0

        const-string v1, "flexboard_hotkey_$slot"
        invoke-virtual { v0, v1 }, ${builder.setId}

        $constOp v1, $slot
        invoke-static { p1, v1 }, $HOTKEYS_ICON
        move-result v1
        invoke-virtual { v0, v1 }, ${builder.setIcon}

        const/4 v1, 0x0
        invoke-virtual { v0, v1 }, ${builder.setLabel}
        $constOp v1, $slot
        invoke-static { p1, v1 }, $HOTKEYS_LABEL
        move-result-object v1
        iput-object v1, v0, ${builder.labelField}

        const/4 v1, 0x0
        invoke-virtual { v0, v1 }, ${builder.setContentDescription}
        $constOp v1, $slot
        invoke-static { p1, v1 }, $HOTKEYS_LABEL
        move-result-object v1
        iput-object v1, v0, ${builder.contentDescriptionField}

        new-instance v1, $HOTKEY_CLASS
        $constOp v2, $slot
        invoke-direct { v1, v2 }, $HOTKEY_CTOR
        invoke-virtual { v0, v1 }, ${builder.setAction}

        invoke-virtual { v0 }, ${builder.build}
        move-result-object v0

        const/4 v1, 0x1
        invoke-virtual { p0, v0, v1 }, $registerCall

        :$HOTKEY_SKIP_LABEL$slot
    """.trimIndent()
}

// Credit where it is due: these consts exist solely so the constants checker can see the
// descriptor across the string-interpolation boundary and verify the Java side declares them.
private const val HOTKEYS_CLASS = "Ldev/jz6/flexboard/extension/toolbar/Hotkeys;"
private const val HOTKEYS_SHOWN = "$HOTKEYS_CLASS->shown(Landroid/content/Context;I)Z"
private const val HOTKEYS_ICON = "$HOTKEYS_CLASS->iconOf(Landroid/content/Context;I)I"
private const val HOTKEYS_LABEL = "$HOTKEYS_CLASS->labelOf(Landroid/content/Context;I)Ljava/lang/String;"
private const val HOTKEY_CLASS = "Ldev/jz6/flexboard/extension/toolbar/Hotkey;"
private const val HOTKEY_CTOR = "$HOTKEY_CLASS-><init>(I)V"

// -------------------------------------------------------------------------------------------
// The order-read filter: admit our ids
// -------------------------------------------------------------------------------------------

/** The string prefix every Flexboard-registered toolbar id uses. */
internal const val FLEXBOARD_ID_PREFIX = "flexboard_"

/**
 * Widens the toolbar order manager's read filter to admit Flexboard ids.
 *
 * Stock, the filter is a loop over the persisted string array that keeps only entries the
 * allowed set (`Lvxe`, read once from `res/array/0x7f0300dc`) contains:
 *
 * ```
 *  9: aget-object v3, v5, v2         ; id = persisted[i]
 * 17: if-eqz v3, -> 28               ; null id -> skip
 * 19: invoke contains(v7, v3)        ; allowed set?
 * 23: if-eqz v4, -> 28               ; not allowed -> skip
 * 25: invoke add(v0, v3)             ; keep it
 * 28: i++
 * ```
 *
 * Two kinds of id must survive:
 *  - the four text buttons, which borrow dormant ids from Gboard's own allowed set, and
 *  - the twelve hotkeys, which can't — the set only has two dormant ids left.
 *
 * Rather than touching the ARSC, we insert one extra bypass right before the `contains` call:
 * any id beginning `flexboard_` is treated as allowed. Our twelve hotkey ids pass; the four
 * text-action ids (also `flexboard_`-prefixed in the read filter? no — they reuse *Gboard's*
 * allowed-set ids, which pass the `contains` branch) stay untouched either way. No collision —
 * the prefix is namespaced, and the preflight asserts none of them appear in Gboard's array so a
 * future Gboard can't claim them from under us.
 *
 * Register safety: at the point of insertion the loop is live in `v0`–`v7`; `v4` is the
 * `contains` result register's home — written right after our jump lands and dead in each
 * iteration before then — so it doubles as the scratch for `startsWith`. `v3` (the id) is
 * untouched.
 */
internal fun BytecodePatchContext.admitFlexboardToolbarIds() {
    val filterOwner = "Lmjv;"
    val candidates = methodsMatching { method ->
        method.definingClass == filterOwner &&
            method.parameterTypes.map(Any::toString) ==
                listOf("[Ljava/lang/String;", "Lvol;", "Lvxe;") &&
            method.returnType == "Lvvw;" &&
            method.implementation != null
    }
    check(candidates.size == 1) {
        "The toolbar order filter moved: expected exactly one $filterOwner method with " +
            "([Ljava/lang/String;Lvol;Lvxe;)Lvvw;, found ${candidates.size}: " +
            candidates.map { it.toDescriptor() }
    }
    val filter = candidates.single()
    val mutable = mutableClassDefBy(filterOwner).methods.single {
        it.toDescriptor() == filter.toDescriptor()
    }
    val instructions = mutable.implementation!!.instructions

    val containsCall = "Lvxe;->contains(Ljava/lang/Object;)Z"
    val containsIndex = instructions.indexOfFirst { instruction ->
        instruction.opcodeName() == "INVOKE_VIRTUAL" &&
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)
                ?.toString() == containsCall
    }
    check(containsIndex >= 0) {
        "$filterOwner.${filter.name}: no $containsCall call found — Gboard's order filter no " +
            "longer consults the allowed set where this patch expects it"
    }

    // The add call is what contains' true-branch falls through to. From the loop's shape: the
    // `move-result` and the skip jump sit between them.
    val addIndex = containsIndex + 3
    check(addIndex < instructions.size) {
        "$filterOwner.${filter.name} is too short for the contain-check's follow-on instructions"
    }

    mutable.addInstructionsWithLabels(
        containsIndex,
        """
        const-string v4, "$FLEXBOARD_ID_PREFIX"
        invoke-virtual { v3, v4 }, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
        move-result v4
        if-nez v4, :$HOTKEY_PREFIX_ALLOWED_LABEL
        """.trimIndent(),
        ExternalLabel(HOTKEY_PREFIX_ALLOWED_LABEL, instructions[addIndex]),
    )
}

/** Jump target for the id-admission bypass — the loop's "keep this id" instruction. */
private const val HOTKEY_PREFIX_ALLOWED_LABEL = "flexboard_id_allowed"
