package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.assertRegisterCount
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.toDescriptor
import dev.jz6.flexboard.patches.shared.validateScratchRegisters

/**
 * Registers a single <b>Test</b> button through Gboard's own access-point registry.
 *
 * ## Why not the split-list splice
 *
 * Every button before this one — the text actions, the custom hotkeys — enters the bar by
 * having the split method's `List` parameter rewritten at entry. That works visually, but
 * the customize screen writes its own string into `access_points_showing_order` from the ids
 * it sees, and ours are not in it because they were never in the allowed set. On every
 * rebuild they are gone, and on every customize save the order it re-persists has no record
 * of where they were dragged to. Customise-drag is therefore not real persistence: a
 * refresh slides them back to the front.
 *
 * ## What makes a Gboard access point "real"
 *
 * Three things converging on one id:
 *
 *  1. **The id is in the allowed set** — a `string-array` resource read once at
 *     `Lmku` construction into an `ImmutableSet`. Unknown ids are dropped silently when
 *     the persisted order string is reloaded.
 *  2. **A definition is in the bar controller's registry** — an `ArrayMap<String, mic>`
 *     on `Lmlh`, populated by its `g(mic, boolean)` method. This is where the icon, label
 *     and click action live.
 *  3. **The id is in the shown order** — the `List<String>` inside the `Lmku`'s current
 *     `Lmjv`. `g()` adds to both when (1) and (2) hold.
 *
 * The order string is rewritten verbatim by {@code Lmjz;->q} on save without filtering, and
 * the allowed-set filters only read-side. So a definition registered under an allowed id
 * survives the whole customise→save→reload cycle untouched.
 *
 * ## Borrowing an id Gboard has already allowed
 *
 * The allowed set contains 43 ids. A handful have no handler anywhere in Gboard — no method
 * references the literal, no provider registers them. They exist as seeds the user-facing
 * surface never grew into, and using one costs nothing:
 *
 *  `flag_editor`, `editor_info`, `muse_toggle_playground_ap`, `jetson_feedback`,
 *  `undo_cooperative`, `signboard_education`
 *
 * This patch registers under {@code flag_editor}. If Gboard later ships that feature, the
 * two would collide — ours would overwrite its entry in the registry map. If that happens,
 * pick another from the list: they all have the same property.
 *
 * ## Where the call goes
 *
 * `Lmlh.<init>` is the bar controller's constructor. By its tail its registry map `h` is
 * empty and its order manager `g` has loaded the allowed set — both fields set, nothing
 * observing them yet. Calling {@code g(mic, true)} here is *registration before the bar
 * exists*; the rest of the system treats our button exactly as if it had been there from
 * the start. The order-manager persistence folds the id into the shown order, and the next
 * time Customize writes the string back, it sticks.
 *
 * ## Click
 *
 * `mhx.q(Runnable)` bundles the runnable as an {@code ACCESS_POINT_ACTION} (keycode
 * `0xffff63b9`) key-data, which Gboard's press dispatcher runs natively. The payload is
 * just [TestAction] in the extension: commit "test" at the cursor. No runnable-vs-keycode
 * plumbing to figure out on the patch side.
 *
 * This patch is <b>default-off</b>: it is an architectural test, not a user-facing feature.
 */
@Suppress("unused")
val toolbarNativeTestPatch = bytecodePatch(
    name = "Toolbar Native Test",
    description = "Add a 'Test' button to the toolbar through Gboard's own access-point " +
        "registry so drag reorder and persistence work natively. Writes 'test' at the cursor " +
        "on tap. Architectural proof-of-concept; off by default.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    dependsOn(basePatch)

    execute {
        val builder = resolveAccessPointBuilder()
        emitNativeTestButton(builder)
    }
}

// -------------------------------------------------------------------------------------------
// The button
// -------------------------------------------------------------------------------------------

/**
 * The dormant id we register under. See the patch documentation for how it was picked: in the
 * allowed set, zero references in Gboard's own dex.
 */
private const val TEST_ID = "flag_editor"

/** An icon Gboard already bundles — Material `select_all`, proven to render on-device. */
private const val TEST_ICON = "0x7f080218"

private const val TEST_LABEL = "Test"

private const val TEST_ACTION_CLASS = "Ldev/jz6/flexboard/extension/toolbar/TestAction;"
private const val NEW_TEST_ACTION = "$TEST_ACTION_CLASS-><init>()V"

/** `Lmlh.<init>` is compiled with 13 registers today; assert so a different build fails loudly. */
private const val CONTROLLER_INIT_REGISTER_COUNT = 13

// -------------------------------------------------------------------------------------------
// Insertion
// -------------------------------------------------------------------------------------------

/**
 * Builds and registers the button at the tail of the bar controller's constructor.
 *
 * ## Derivation strategy
 *
 * Names are out from under us twice over — the controller class and the access-point type
 * are both R8-shrunk on every Gboard build — so everything here is anchored on what the
 * code does rather than what it is called. The controller is the class owning the
 * split-the-`List` method the other patches already hook; the registration call is the
 * controller's unique `(ApType, Z)V` method that writes the registry map via
 * `Lays;->put` on the `h` field; the constructor is the only two-argument `<init>` whose
 * first parameter is `Context`.
 *
 * ## Register safety
 *
 * The insertion sits just before the constructor's `return-void`. By that point `v0`–`v5`
 * are dead — they were reused during the body for one-off construction steps whose last
 * writes precede the final fields being stored. `p0` (`v10` today) is the receiver and is
 * untouched. Two-degree validation: an exact register count is asserted, and the scratch
 * list is checked against the input register range.
 */
private fun BytecodePatchContext.emitNativeTestButton(
    builder: AccessPointBuilder,
) {
    // The bar-controller class is pinned by the split method the other toolbar patches
    // already depend on.
    val split = methodsMatching { it.splitsAccessPoints() }.single()
    val controllerType = split.definingClass
    val controllerClass = classDefByOrNull(controllerType)
        ?: error("$controllerType is not in the APK; the bar controller cannot be hooked")

    // The registration call: the controller's unique (ApType, Z)V that Lays.put's into its
    // registry map. Derived rather than named because `g` is a one-letter obfuscated alias
    // that will change underneath us; the shape does not.
    val registerCall = run {
        val candidates = controllerClass.methods.filter { method ->
            val params = method.parameterTypes.map(Any::toString)
            params.size == 2 &&
                params[1] == "Z" &&
                method.returnType == "V" &&
                method.implementation?.instructions?.any { instruction ->
                    instruction.opcodeName() == "INVOKE_VIRTUAL" &&
                        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)
                            ?.toString() ==
                        "Lays;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                } == true
        }
        check(candidates.size == 1) {
            "Expected exactly one (*, Z)V method on $controllerType writing the registry via " +
                "Lays.put — the bar-controller's register call — but found ${candidates.size}: " +
                candidates.map { it.toDescriptor() }
        }
        candidates.single().toDescriptor()
    }

    // The constructor: the only two-argument <init> taking (Context, *).
    val initDef = controllerClass.methods.singleOrNull {
        it.name == "<init>" &&
            it.parameterTypes.size == 2 &&
            it.parameterTypes[0].toString() == "Landroid/content/Context;"
    } ?: error(
        "$controllerType has no <init>(Context, ?) — the bar-controller constructor's shape " +
            "has changed and the hook point must be re-derived",
    )

    val init = mutableClassDefBy(controllerType).methods.single {
        it.toDescriptor() == initDef.toDescriptor()
    }
    init.assertRegisterCount(CONTROLLER_INIT_REGISTER_COUNT, initDef.toDescriptor())

    // The tail of a constructor is exactly one instruction in — the last return-void.
    // Everything else in the body has run, including mku initialisation.
    val tailIndex = init.implementation!!.instructions
        .indexOfLast { it.opcodeName() == "RETURN_VOID" }
    check(tailIndex >= 0) {
        "${initDef.toDescriptor()} has no return-void — the constructor's shape has changed"
    }

    // v0..v1 are dead after the constructor body's last writes; p0 (v10) is the receiver
    // and must not be touched.
    validateScratchRegisters(
        scratch = listOf(0, 1),
        avoid = listOf(10, 11, 12),
        what = initDef.toDescriptor(),
    )

    init.addInstructions(
        tailIndex,
        """
            invoke-static { }, ${builder.newBuilder}
            move-result-object v0

            const-string v1, "$TEST_ID"
            invoke-virtual { v0, v1 }, ${builder.setId}

            const v1, $TEST_ICON
            invoke-virtual { v0, v1 }, ${builder.setIcon}

            const/4 v1, 0x0
            invoke-virtual { v0, v1 }, ${builder.setLabel}
            invoke-virtual { v0, v1 }, ${builder.setContentDescription}
            const-string v1, "$TEST_LABEL"
            iput-object v1, v0, ${builder.labelField}
            iput-object v1, v0, ${builder.contentDescriptionField}

            new-instance v1, $TEST_ACTION_CLASS
            invoke-direct { v1 }, $NEW_TEST_ACTION
            invoke-virtual { v0, v1 }, ${builder.setAction}

            invoke-virtual { v0 }, ${builder.build}
            move-result-object v0

            const/4 v1, 0x1
            invoke-virtual { p0, v0, v1 }, ${registerCall}
        """,
    )
}
