package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.jz6.flexboard.patches.shared.assertRegisterCount
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.toDescriptor
import dev.jz6.flexboard.patches.shared.validateScratchRegisters

/**
 * The six configurable hotkey slots: their constructor registration, and the start-input refresh
 * that re-registers them when the keyboard is raised for a new editor.
 *
 * Both emissions build the same per-slot block from [HotkeySite], which is why they are one file
 * -- separating them put the site definition on one side of the seam and its only two callers on
 * the other. Reads the controller from [ToolbarCanvas.kt].
 */
/** The hotkey slots Flexboard registers: everything emitted loops this range once. */
internal const val HOTKEY_SLOTS = 6

/** Every flexboard toolbar id carries this prefix — how the constants checker tells the
 * generated per-slot keys from a typo. If it changes, `ToolbarSlotsPatch`'s strings move too. */
internal const val HOTKEY_ID_PREFIX = "flexboard_hotkey_"

// -------------------------------------------------------------------------------------------
// Per-slot toolbar buttons whose every attribute is runtime data
// -------------------------------------------------------------------------------------------

/**
 * Emits one conditional registration block per hotkey slot at the tail of the bar controller's
 * `<init>`.
 *
 * Unlike [emitNativeToolbarButtons] — where the label, icon and id are constants picked at
 * patch time — every attribute of a hotkey is read at toolbar-build time by the extension's
 * `Hotkeys` class: the id's existence at all is gated by `shown`, and the icon/label/action are
 * derived from the user's settings. The block the patcher builds is therefore identical in
 * *shape* per slot but entirely runtime-populated.
 *
 * Registers (same wiring as the text-action buttons):
 *  - `p0` is the receiver the register call is invoked on;
 *  - `v0` holds the builder then the finished `mic`;
 *  - `v1` carries each argument in turn;
 *  - `v2` is the second Int passed into the action's constructor when it takes an ordinal;
 *  - `v4` is the shown-guard's scratch — dead before and after the block's own use.
 *
 * Admission changed hands since the first implementation: the ids are widened into Gboard's
 * own allowed-set array by `ToolbarSlotsPatch` now (docs/toolbar-access-points.md), so no dex
 * touches the order-read filter at all.
 */
internal fun BytecodePatchContext.emitNativeHotkeys(builder: AccessPointBuilder) {
    val canvas = resolveControllerCanvas()
    val init = mutableClassDefBy(canvas.controllerType).methods.single {
        it.toDescriptor() == canvas.initDescriptor
    }
    init.assertRegisterCount(CONTROLLER_INIT_REGISTER_COUNT, canvas.initDescriptor)

    val tailIndex = init.implementation!!.instructions
        .indexOfLast { it.opcodeName() == "RETURN_VOID" }
    check(tailIndex >= 0) {
        "${canvas.initDescriptor} has no return-void — the constructor's shape has changed"
    }

    // p1 is the constructor's Context argument at this register count; p0 is the receiver.
    validateScratchRegisters(
        scratch = listOf(0, 1, 2, 4),
        avoid = listOf(10, 11, 12),
        what = canvas.initDescriptor,
    )

    val emission = ((1..HOTKEY_SLOTS).joinToString("\n\n") { slot ->
        hotkeyBlock(slot, builder, HOTKEY_CTOR_SITE, canvas.registerCall)
    } + "\n\nnop\n").trimIndent()
    // WithLabels: the slot blocks each carry their own internal `:…skip_N` label, which the
    // plain `addInstructions` rejects. The trailing `nop` is not decoration:
    // `addInstructionsWithLabels` (reversed-SubList-walk, `externalLabels[0]` on an empty array →
    // `ArrayIndexOutOfBoundsException: length=0; index=0`) crashes the patcher whenever a branch
    // targets an internal label that has no instruction after it *in the same emission*. Every
    // slot's label but the last binds to the next block's opening instruction; the last needs a
    // home past the end — one `nop` is it. check_emission_lint.py greps for this shape too.
    init.addInstructionsWithLabels(tailIndex, emission)
}

/**
 * The per-site differences between the two homes of one emission shape: the controller
 * `<init>` (context arrives as the constructor's `p1`; the register call rides `p0`) and the
 * start-input refresh (context comes off the module's own getter; the register call rides the
 * module's held controller field). Everything else — the guard, the builder chain, the label —
 * is one template, so a fix to the block shape cannot land in one site and not the other.
 *
 * Registers are fixed per site and both sites use scratch {v0,v1,v2,v4} only, disjoint from the
 * pinned parameters of either hook (validated next to each insertion).
 */
private class HotkeySite(
    /** Skip-label prefix; the slot number is appended by the template. */
    val labelPrefix: String,
    /** Instructions loading the Context into [ctx]; empty when the hook hands one over. */
    val prelude: String,
    /** Register naming the Context for the shown/icon/label calls (`p1` or a local like `v1`). */
    val ctx: String,
    /** Scratch register for the slot constant and the guard result. Must differ from [ctx]. */
    val guard: String,
    /** Register each builder argument is staged in (the icon id, the label string, the action). */
    val arg: String,
    /** Register the Hotkey constructor's ordinal is staged in. */
    val ordinal: String,
    /** Instructions staging the register call's receiver into [receiver]; empty for the ctor. */
    val tailLoad: String,
    /** Register the register call is invoked on. */
    val receiver: String,
)

private val HOTKEY_CTOR_SITE = HotkeySite(
    labelPrefix = HOTKEY_SKIP_LABEL,
    prelude = "",
    ctx = "p1",
    guard = "v4",
    arg = "v1",
    ordinal = "v2",
    tailLoad = "",
    receiver = "p0",
)

private fun hotkeyRefreshSite(controllerField: String) = HotkeySite(
    labelPrefix = HOTKEY_REFRESH_LABEL,
    prelude = """
        invoke-virtual { p0 }, $MODULE_CONTEXT
        move-result-object v1
    """.trimIndent(),
    ctx = "v1",
    guard = "v0",
    arg = "v2",
    ordinal = "v4",
    tailLoad = "iget-object v1, p0, $controllerField",
    receiver = "v1",
)

/** One slot's conditional registration block. The guard is a single forward jump. */
private fun hotkeyBlock(
    slot: Int,
    builder: AccessPointBuilder,
    site: HotkeySite,
    registerCall: String,
): String {
    // const/4 only encodes -8..7. At [HOTKEY_SLOTS] = 6 the widening branch is unreachable today;
    // it stays because the slot count is a constant someone may raise, and a silently truncated
    // slot ordinal would be far harder to spot than an extra branch here.
    val constOp = if (slot in 1..7) "const/4" else "const/16"
    // Plain unbraced names in the template: `${site.x}` inside an invoke's `{ ... }` would put a
    // `}` mid-register-list, and the constants checker's emitted-call parser would silently stop
    // seeing the extension invocations it exists to verify.
    val pre = site.prelude
    val lab = site.labelPrefix
    val ctx = site.ctx
    val g = site.guard
    val a = site.arg
    val ord = site.ordinal
    val tail = site.tailLoad
    val recv = site.receiver

    return """
        $pre
        $constOp $g, $slot
        invoke-static { $ctx, $g }, $HOTKEYS_SHOWN
        move-result $g
        if-eqz $g, :$lab$slot

        invoke-static { }, ${builder.newBuilder}
        move-result-object v0

        const-string $a, "$HOTKEY_ID_PREFIX$slot"
        invoke-virtual { v0, $a }, ${builder.setId}

        $constOp $a, $slot
        invoke-static { $ctx, $a }, $HOTKEYS_ICON
        move-result $a
        invoke-virtual { v0, $a }, ${builder.setIcon}

        const/4 $a, 0x0
        invoke-virtual { v0, $a }, ${builder.setLabel}
        $constOp $a, $slot
        invoke-static { $ctx, $a }, $HOTKEYS_LABEL
        move-result-object $a
        iput-object $a, v0, ${builder.labelField}

        const/4 $a, 0x0
        invoke-virtual { v0, $a }, ${builder.setContentDescription}
        $constOp $a, $slot
        invoke-static { $ctx, $a }, $HOTKEYS_LABEL
        move-result-object $a
        iput-object $a, v0, ${builder.contentDescriptionField}

        new-instance $a, $HOTKEY_CLASS
        $constOp $ord, $slot
        invoke-direct { $a, $ord }, $HOTKEY_CTOR
        invoke-virtual { v0, $a }, ${builder.setAction}

        invoke-virtual { v0 }, ${builder.build}
        move-result-object v0

        $tail
        const/4 $a, 0x1
        invoke-virtual { $recv, v0, $a }, $registerCall

        :$lab$slot
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

private const val HOTKEY_SKIP_LABEL = "flexboard_hotkey_skip_"

// -------------------------------------------------------------------------------------------
// Refresh on every keyboard open
// -------------------------------------------------------------------------------------------

/**
 * Emits one guarded re-registration block per slot at the tail of the toolbar module's
 * start-input callback, so a settings edit takes effect on the next keyboard open instead of
 * the next process start. The stock constructor emission stays — it seeds the session — and
 * re-registration is safe by construction: the register call re-`put`s the entry into the
 * registry map and the fold's dedupe set swallows the repeat.
 *
 * The hook is `Lmln.fn(Loru;LEditorInfo;ZLjava/util/Map;Lnve;)Z` — the module's
 * onStartInputView, public final on 18.0.3 — inserted ahead of its tail `return`. At that
 * point every local (v0..v7) is dead: only the return statement remains. The module's Context
 * comes from `Lnvd;->ac()`, and the live bar controller is its Lmlh-typed field.
 */
internal fun BytecodePatchContext.emitHotkeyRefresh(builder: AccessPointBuilder) {
    // Same controller resolution as the constructor emission; the register call is shared.
    val canvas = resolveControllerCanvas()

    // The start-input signature is the *module-wide* base API (Lnvd) — dozens of modules
    // declare it. What singles out the toolbar module is that it also OWNS the bar controller:
    // one field of the controller's type. Both conditions together identify it uniquely.
    val owning = methodsMatching { it.signatureMatchesToolbarStartInput() }
        .filter { moduleMethod ->
            val cls = classDefByOrNull(moduleMethod.definingClass) ?: return@filter false
            cls.fields.any { it.type == canvas.controllerType }
        }
    check(owning.size == 1) {
        "The toolbar module start-input anchor moved: expected exactly one start-input method " +
            "on a class that also fields the bar controller, found ${owning.size}: " +
            owning.map { it.toDescriptor() }
    }
    val modules = owning
    val startDef = modules.single()
    val moduleType = startDef.definingClass
    val moduleClass = classDefByOrNull(moduleType)
        ?: error("$moduleType is not in the APK; the toolbar module cannot be hooked")
    val controllerFields = moduleClass.fields.filter { it.type == canvas.controllerType }.toList()
    check(controllerFields.size == 1) {
        "$moduleType should carry exactly one ${canvas.controllerType} field, found " +
            controllerFields.map { "${it.definingClass}->${it.name}:${it.type}" }
    }
    val controllerField = controllerFields.single().let {
        "${it.definingClass}->${it.name}:${it.type}"
    }

    val startDescriptor = startDef.toDescriptor()
    val start = mutableClassDefBy(moduleType).methods.single {
        it.toDescriptor() == startDescriptor
    }
    start.assertRegisterCount(START_INPUT_REGISTER_COUNT, startDescriptor)

    // The other two emitters validate their scratch set; this one used to only assert the register
    // count, which does not rule out a collision. At 14 registers with 6 arguments the parameters
    // occupy v8..v13, leaving v0/v1/v2/v4 as locals that are dead at the return.
    validateScratchRegisters(
        scratch = listOf(0, 1, 2, 4),
        avoid = (START_INPUT_REGISTER_COUNT - START_INPUT_ARGUMENT_COUNT until START_INPUT_REGISTER_COUNT).toList(),
        what = startDescriptor,
    )

    val returns = start.implementation!!.instructions
        .count { it.opcodeName().startsWith("RETURN") }
    // The refresh block is spliced before the *last* return. That is only equivalent to "before
    // every exit" while there is exactly one, so a build that gains an early return would make
    // the refresh silently unreachable on that path rather than fail here.
    check(returns == 1) {
        "$startDescriptor has $returns return sites, expected 1 — splicing the hotkey refresh " +
            "before the last one would leave the other paths without it"
    }

    val returnIndex = start.implementation!!.instructions
        .indexOfLast { it.opcodeName().startsWith("RETURN") }
    check(returnIndex >= 0) { "$startDescriptor has no return — shape moved" }

    val refreshSite = hotkeyRefreshSite(controllerField)
    val emission = ((1..HOTKEY_SLOTS).joinToString("\n\n") { slot ->
        hotkeyBlock(slot, builder, refreshSite, canvas.registerCall)
    } + "\n\nnop\n").trimIndent()
    // Same trailing-label rule as the constructor emission: the nop houses the last slot's branch.
    start.addInstructionsWithLabels(returnIndex, emission)
}

/** The module-wide start-input signature (declared per module; dozens match — see the caller
 * for the bar-controller-field narrowing that makes it unique). */
private fun Method.signatureMatchesToolbarStartInput(): Boolean =
    returnType == "Z" &&
        parameterTypes.map(Any::toString) == listOf(
            "Loru;", "Landroid/view/inputmethod/EditorInfo;", "Z", "Ljava/util/Map;", "Lnve;",
        )

private const val MODULE_CONTEXT = "Lnvd;->ac()Landroid/content/Context;"

private const val HOTKEY_REFRESH_LABEL = "flexboard_hotkey_refresh_"

/** `fn`'s register count on 18.0.3 — what the insertion assumes; pinned by preflight. */
private const val START_INPUT_REGISTER_COUNT = 14

/**
 * Argument words of the start-input method, receiver included: `(Loru;, EditorInfo, Z, Map, Lnve;)`
 * is five parameters plus `this`. Subtracted from the register count to locate the parameter block,
 * which is what the scratch set has to stay clear of.
 */
private const val START_INPUT_ARGUMENT_COUNT = 6
