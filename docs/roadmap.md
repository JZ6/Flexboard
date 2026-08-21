# Roadmap

## Native registration (being proven out — Parked)

The "Pending (investigated, needs implementation)" section above named the right goal but
mis-diagnosed the seam: the "registered-provider" route isn't needed. Follow-up tracing of the
order-write path confirms the save side (`Lmjz;->q`) writes whatever list it is handed with no
filtering; the **only** filter is on read — `Lmjv;->c` drops ids not in the allowed-set.

That set is the string-array resource `0x7f0300dc`, read once into `mku.c`. Six ids in it are
dormant — zero dex code references them: `flag_editor`, `editor_info`,
`muse_toggle_playground_ap`, `jetson_feedback`, `undo_cooperative`, `signboard_education`.
Borrowing one of them means: the read filter accepts it, the save path keeps it, and Customize
renders it as a fully capable draggable entry.

The bar controller `Lmlh` has `h: ArrayMap<String, mic>` — the actual AP registry. Calling
`mlh.g(mic, true)` on it both stores the definition *and* folds the id into the shown order via
the order manager. `mjv.n(ctx, mxf, h, c, extras)` then re-folds `extras` on every rebuild, so
the button survives orientation/fold changes.

The **Toolbar Native Test** patch (default-off) does this: hooks `Lmlh.<init>` tail, builds an
`mic` with id `flag_editor`, icon from stock, a literal "Test" label, and a click-runnable that
commits "test" at the cursor via the extension's `TestAction`. If it works on device, the same
shape becomes the long-term home for Text Actions + Hotkeys (each `g(...)s` its own id(s) into
`h`), and `ToolbarMerge` shrinks back to "read the order string to seed the shown order"
rather than the current half-broken injection.

## The generalisation: NativeToolbarButton + emitNativeToolbarButtons

After the test button proved out on device, the shape was promoted into a shared helper at
`patches/shared/ToolbarRegistry.kt`:

- **`NativeToolbarButton`** — a spec carrying id / icon / label (res-or-literal) / optional
  contentDescription / an actionCtor (`"Ldev/.../T;-><init>(... )V"`) plus `actionArgs`
  (`const/4`-`const/16` ordinals).
- **`emitNativeToolbarButtons(builder, buttons)`** — resolves the bar-controller class (via the
  split-method anchor), resolves the register call by shape (the unique `(ApType, Z)V` that
  `Lays.put`s into `h`), asserts the constructor register count, and emits one block per button
  at the `<init>` tail.

Any future toolbar feature consumes it as `emitNativeToolbarButtons(builder, listOf(...))` and
never thinks about hook sites, `Builders`, or the allowed-set — picking from the dormant ids
below. `ToolbarNativeTestPatch` is now a 30-line call into the helper.

A checker convention flows from this: the spec's `actionCtor` has to be a `const val` in the
patch file, full member-descriptor form; `check_shared_constants.py` then treats each as if
`invoke-direct` on a real extension class and verifies it against the actual Java source. The
checker was also fixed to strip Kotlin comments before scanning — KDoc references to extension
members were firing the "silently stopped checking" guard.

## Replaced: the legacy merge splice

The split-method list splice that used to implement toolbar insertion is gone:

- `TextActionsPatch.kt` (three buttons emitted into `Lmlh;->C(List)V`) **deleted** — superseded
  by `ToolbarButtonsPatch.kt` which registers the same three buttons using dormant-allowed-set
  ids (`editor_info`, `undo_cooperative`, `muse_toggle_playground_ap`) at `<init>` tail.
- `CustomHotkeysPatch.kt` **deleted** — the 12-slot hotkeys feature hung off the same merge
  registry and will come back as native registration with its own ids (three dormant ids remain
  for this — plus `jetson_feedback` and `signboard_education` — and if twelve are needed we
  will have to widen the `0x7f0300dc` array, which is one ARSC patch).
- `ToolbarMerge.java` and `emitToolbarMergeCall` **deleted** — no more order-string pair merge.
  Reordering now lands in Gboard's own Customize UI; the bar rebuild path reads the resulting
  order string as-is. The merge class was doing work Customize already knows how to do.
- `BasePatch` no longer emits the merge call.

The remaining dormant allowed-set ids and their slots:

| id                          | used by                          |
| --------------------------- | -------------------------------- |
| `flag_editor`               | `ToolbarNativeTestPatch`         |
| `editor_info`               | Select all                       |
| `undo_cooperative`          | Copy                             |
| `muse_toggle_playground_ap` | Paste                            |
| `jetson_feedback`           | free                             |
| `signboard_education`       | free                             |

Old prefs on users' devices (`flexboard_select_all` etc. in `mlh.h`, and hotkey values in the
user's shared-prefs) are orphaned by the swap; neither breaks anything — the old ids drop out
of the order filter silently, and hotkey fields in the settings screen are inert until the
hotkey patch returns in native form.

## Pending (investigated, needs implementation)

**Toolbar reorder persistence — register as providers.** Gboard's customize-write path reconstructs the saved order string from the registered-provider list — the `Lmjv`/`Lmjz` order hierarchy — and drops any id it doesn't know. Our injected buttons (`flexboard_select_all`, `flexboard_copy`, `flexboard_paste`, `flexboard_hotkey_N`) aren't providers, so they never appear in the order string, and every customize session forgets them. On rebuild, `merge()` can only place them canonicallly at the front (which is why it currently always does that — see `ToolbarMerge.mergeOrdered`). To fix: register our access points with Gboard's provider machinery via a bytecode patch, instead of injecting into the bar's rendered list after the fact. Then the customize-write path has them by construction, and drag-persistence just works.

**Toolbar crash on clicking the 4-square overflow icon.** Reproduces as a crash depending on state; likely the icons where a draw with our registered ids into Customize's key paths. Needs a device logcat to identify. Likely related to not persisting: the customize view expects reordable ids, ours are absent, and the difference in list contents likely crashes the section/commit handler with an unexpected value.

## Earlier observations to not lose

- `ToolbarMerge` previously fought itself: merged-by-pairs-approach produced duplicates and drift. Simplified to: always prepend, never read the order.
- `Hotkey.labelAt` now returns "" instead of `null` when empty; `Hotkey.hasContent` and `hotkeySlotOf` in the merge filter empties from the register path. Empty slots must not reach the bar.
- `?attr/colorControlNormal` does not resolve at recompile time; use framework attrs (`android:textColorPrimary`) or hard colors.
- The icon `$0x7f...` in the hotkeys array needs nothing from the listed modes — they stay bundled in the APK and are just unused.
- morphe-patcher's `addInstructionsWithLabels` (label-aware smali insertion) chokes on two sequential insertions into one method if labels are involved (`ArrayIndexOutOfBoundsException: length=0`). The fix is to remove labels from the inserted bytecode entirely (avoid `if-eqz`-generated `:absent` chains); branchless smali survives.
- The settings Activity should render only enabled sections via markers — parked pending a decision; user's roadmap entry says "hotkeys and toolbar config still in flexboard settings when they arent patched; settings should only be added with the patches".

# Roadmap entries written by the user verbatim.

swipe length seem to be reversed? lower value takes more swipe to swipe multiple words on the delete key

update settings to match rest of gboard

some settings disabled like grammer check and ai writing tools, rambler mode etc

flick up to undo autocorrect 

gesture down on a to select all

use graph 6 material icon for fleksy settings

increased tool bar size fit more buttons

ok now clean up the current changelog, remove all bump commits from the changelog, and make the past stable releases show all commits from the dev releases before it

read the package rename patch from morphe, and see if any improvments can be made to ours, or should we just use theirs.

Task 4 — Already shipped per the roadmap. The settings screen inherits Gboard's theme (colours, Material You), uses framework-only widgets, and approximates androidx metrics. The remaining gap is structural: Gboard uses SwitchPreferenceCompat and custom slider preferences, which the extension can't use without resources. Needs device testing to identify specific visual gaps.

## Shipped

The list above is kept as written; this notes which of it has landed, rather than pruning it.

- **update settings to match rest of gboard** — the screen inherits Gboard's own settings theme, so
  the colours follow it including Material You, and the metrics match androidx preference rows.
- **can we make the backspace swipe work as before without being limited to max 1 word delete** — a
  swipe starting on the backspace key keeps Gboard's distance per word and is not capped.
- **increased tool bar size fit more buttons** — *Bigger Toolbar*, a 3–12 slider for the number of
  icons on the access points bar. Shipped in `1.1.0-dev.1` and did nothing, withheld in
  `1.1.0-dev.2`, rebuilt against the right target: it had been raising the bar's *capacity*, which
  the count is computed from but not bound by. The count itself is now overridden, above both of the
  gates that were discarding the capacity. Also covers **max tool icon slider isnt working**.

- **tool bar amount used to be different between inner and outer screen of a fold** — it was, and
  the first cut of *Bigger Toolbar* flattened it: Gboard picks its count preference by device class,
  a fold changes class when it opens, and overriding at entry returned before that choice. There are
  now two sliders, the second applying only while unfolded and falling back to the first.

- **add select all copy paste hotkeys** — *Text Editing Buttons* puts one-tap **Select all**,
  **Copy** and **Paste** on the toolbar. Cut is not built; it is the same shape again, one entry in
  a list. The icons turned out to be free: Gboard bundles Material's set and draws none of them, and
  `tools/apk/glyphs.py` finds them by geometry now that every drawable name is stripped.

- **Hot keys as new tool bar objects** — six slots under **Hotkeys** in Flexboard's settings, each
  typing whatever string you put in it. A slot you have not filled in builds no button at all, so
  they cost nothing until used and clearing a field removes the button again. The label on each is
  your own text: the access point carries a label *resource id* and a label *String*, and returns
  the String whenever the id is zero — and a sweep found nothing outside one accessor reading that
  id, so setting it to zero cannot break a rendering path.

  The toolbar slider was assumed to be a prerequisite, on the grounds that adding buttons only
  helps if there is room. That turned out to be wrong, which was lucky at the time, because the
  slider did not work. A new button is prepended to the ordered list the bar is built from, so it
  takes the first slot and pushes whatever used to be last into the overflow panel — no extra room
  needed. With the slider working the two now compose properly: raise the count and the button
  costs nothing that used to be visible.

- **set new defaults, swipe length 60%, icons on the toolbar 6, icons when unfolded 12** — all
  three, and **written into the store on first run** rather than baked into the patch as read
  fallbacks. That distinction is the point: a fallback follows the code, so changing it later moves
  everyone who never touched a slider. A written value is yours from the first run, and a future
  release can pick different starting numbers for new installs without touching a keyboard someone
  has got used to. The toolbar had no starting value at all before — unset fell through to whatever
  Gboard computed.

- **what other material symbols are there, im pretty sure undo exists** — it does not. All 2,170
  published Material Icons were matched against the APK's 496 vector drawables, and 29 shapes are
  bundled at 35 ids; the table is in [`gboard-bindings.md`](gboard-bindings.md#material-icons-gboard-bundles).
  `undo`, `redo`, `search`, `send`, `add`, `edit` and every numbered glyph match nothing, while
  `spellcheck` and `auto_awesome` are there. The hotkey icons were picked out of that list, which is
  also why they are shapes rather than the digits one through six. The `glyphs.py` tail check was
  fixed — it had been bleeding into the next `<path>` element and skipping filled icons, so
  `add`, `close`, `content_paste`, `delete`, `edit`, `mic`, `search` and `send` all reported
  "0 points" and were silently absent from earlier audits.

On **gesture down on a to select all**: not built, and deliberately not. It is the same action
reached a different way, and the toolbar button was the cheaper half. Gboard's own long-press
popups are defined in compiled keyboard metadata rather than in code, so putting an action there
means hooking the soft-key bind path and rewriting that metadata at runtime — a much larger change
than the one access-point insertion this took.
