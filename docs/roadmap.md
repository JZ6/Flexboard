# Roadmap


# Roadmap entries written by the user verbatim.

should we move select all copy paste to same as the hotkey buttons

some settings disabled like grammer check and ai writing tools, rambler mode etc

flick up to undo autocorrect 

gesture down on a to select all?

increased tool bar size fit more buttons

clean up the current changelog, remove all bump commits from the changelog, and make the past stable releases show all commits from the dev releases before it


## Done from the queue

- **"redo settings to use native gboard"** (1.4.0-dev, after dev.2): the settings screen is hosted
  by Gboard's own settings stack — the row's `android:fragment` instantiates an extension fragment
  subclassing `CommonPreferenceFragment` (compile-only stub; see `stubs/`), the screen is a real
  `res/xml/flexboard_settings.xml` of `InlineSliderPreference` rows, and values persist through the
  datastore bridge into the same store the swipe patch reads. The hand-built Activity, its theme
  mirroring and the rename-patch retarget are deleted; old int-typed keys abandoned for
  `flexboard_swipe_*`. Mechanism and pins: [`docs/gboard-settings-hosting.md`](gboard-settings-hosting.md).

## Historical: the dead merge-splice architecture (kept for context)

Under "Formerly broken / solved by native registration": the old design tried to splice extra
buttons into the split-method list and hand-merge them against `access_points_showing_order` in
`ToolbarMerge.merge`. It never landed ordering right, crashed the 4-square customize overflow
(persistence write couldn't see unregistered ids), and snapped buttons back to the front on
every rebuild. All of that is deleted; native registration via `emitNativeToolbarButtons` is
the only way in.

**Summary of the pre-native findings (the 'fix X' series that was wrong-headed):**
- "ToolbarMerge previously fought itself: merged-by-pairs-approach produced duplicates and drift" — fixed by deleting ToolbarMerge.
- "Hotkey.labelAt returns '' / hasContent / hotkeySlotOf" — fixed by deleting Hotkey.
- "`addInstructionsWithLabels` chokes on two sequential insertions" — still true; emission is label-free everywhere.
- "?attr/colorControlNormal does not resolve at recompile time" — still true; don't use AppCompat attrs.
- "The settings Activity should render only enabled sections via markers" — deferred by user.

## Pending (investigated, needs implementation)

**Bigger Toolbar returns natively.** Hotkeys have since shipped — six slots on `flexboard_*` ids
admitted by `toolbarIdAdmissionPatch`. Bigger Toolbar has not. Both previous attempts tried to own
the count and both broke the user's ability to remove buttons; the plan that follows from that is
`docs/toolbar-capacity.md`, and it raises the capacity only.

**Widen the allowed-set array when hotkeys return.** Done — `toolbarIdAdmissionPatch` splices the
twelve `flexboard_hotkey_N` ids into the allowed-set array via `res/values` (strings + items),
zero dex change; research in [`docs/toolbar-access-points.md`](toolbar-access-points.md). Inert
until a patch registers those ids — which is the hotkeys return, when it comes.

**Rolled back: the 1.4.0-dev.1–dev.5 native hotkeys.** The dex side was solid (conditional
`Lmlh` blocks + the `Lmjv;->c` order-filter bypass), but the feature's per-slot ListPreference
screens needed two string-array entries in `res/values/`, and values-file surgery failed twice
inside Morphe's encode: once from arsclib minting a fake type out of the filename
(`flexboard_hotkey_icons.xml` → type `flexboard_hotkey_icon`), once from a splice that left a
second `<resources>` opening tag mid-file (parse died at `arrays.xml` line 7141). The whole
stack was reset to the pre-hotkeys base. When hotkeys re-land: keep the dex half, and serve
the slot pickers **without touching `res/values/`** — hardcode the icon choices in the
extension's Java and build the lists at runtime.

**Re-landed in 1.4.0-dev.4** on the widened allowed-set array instead of the filter bypass:
extension core (`499af89`), registration + settings rows (`fc80d12`) — count slider,
twelve inline text EditText rows, label/labelOf fixes and trailing-nop carried over — and the
import/export blob row (`3ba0de6`), later replaced by real Export/Import buttons. Slot icons
default from the bundled-glyph table and are user-changeable again, wired through the ported
click hook (`aA`) that finally exists. The ui went through two passes: first tap-to-cycle rows
(superseded — user feedback), then the current **picker grid dialog** off the tapped row's own
activity context, with export/import as a show-the-blob / paste-the-blob popup pair.
`IconListPreference` was skipped deliberately: its icon list arrives through the extras *Bundle*,
which no XML inflater in the port populates.

Device note, resolved in dev.5: slots also re-register from the toolbar module's start-input
callback (`Lmln.fn`), so a settings edit takes effect when the keyboard next opens. The
constructor emission stays as the session seed. (The count slider went away too: slots ship as
numbered placeholders, clear-text to hide.)



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

The **Toolbar Native Test** patch (removed once it had served its purpose) did this: hooked the
`Lmlh.<init>` tail, built an
`mic` with id `flag_editor`, icon from stock, a literal "Test" label, and a click-runnable that
committed "test" at the cursor via a small extension action. It worked on device, and the same
shape becomes the long-term home for Text Actions + Hotkeys (each `g(...)s` its own id(s) into
`h`), and `ToolbarMerge` shrinks back to "read the order string to seed the shown order"
rather than the current half-broken injection.

## The generalisation: NativeToolbarButton + emitNativeToolbarButtons

After the test button proved out on device, the shape was promoted into a shared helper at
`patches/shared/ToolbarHotkeys.kt`:

- **`NativeToolbarButton`** — a spec carrying id / icon / label (res-or-literal) / optional
  contentDescription / an actionCtor (`"Ldev/.../T;-><init>(... )V"`) plus `actionArgs`
  (`const/4`-`const/16` ordinals).
- **`emitNativeToolbarButtons(builder, buttons)`** — resolves the bar-controller class (via the
  split-method anchor), resolves the register call by shape (the unique `(ApType, Z)V` that
  `Lays.put`s into `h`), asserts the constructor register count, and emits one block per button
  at the `<init>` tail.

Any future toolbar feature consumes it as `emitNativeToolbarButtons(builder, listOf(...))` and
never thinks about hook sites, `Builders`, or the allowed-set — picking from the dormant ids
below. `ToolbarNativeTestPatch` was a 30-line call into the helper, and was deleted once
Text Action Buttons and Toolbar Hotkeys had both proven the mechanism in production.

A checker convention flows from this: the spec's `actionCtor` has to be a `const val` in the
patch file, full member-descriptor form; `check_shared_constants.py` then treats each as if
`invoke-direct` on a real extension class and verifies it against the actual Java source. The
checker was also fixed to strip Kotlin comments before scanning — KDoc references to extension
members were firing the "silently stopped checking" guard.

## Replaced: the legacy merge splice

The split-method list splice that used to implement toolbar insertion is gone:

- `TextActionsPatch.kt` (three buttons emitted into `Lmlh;->C(List)V`) **deleted** — superseded
  by `ToolbarButtonsPatch.kt`, which registers the same three buttons at the `<init>` tail using
  its own admitted ids.
- `CustomHotkeysPatch.kt` **deleted** — the 12-slot hotkeys feature hung off the same merge
  registry and will come back as native registration with its own ids (three dormant ids remain
  for this — plus `jetson_feedback` and `signboard_education` — and if twelve are needed we
  will have to widen the `0x7f0300dc` array, which is one ARSC patch).
- `ToolbarMerge.java` and `emitToolbarMergeCall` **deleted** — no more order-string pair merge.
  Reordering now lands in Gboard's own Customize UI; the bar rebuild path reads the resulting
  order string as-is. The merge class was doing work Customize already knows how to do.
- `BasePatch` no longer emits the merge call.

The dormant allowed-set ids, none of which Flexboard uses any more:

| id                          | status                                        |
| --------------------------- | --------------------------------------------- |
| `editor_info`               | the sentinel `toolbarIdAdmissionPatch` finds the array by |
| `undo_cooperative`          | free                                          |
| `muse_toggle_playground_ap` | free                                          |
| `jetson_feedback`           | free                                          |
| `signboard_education`       | free                                          |

The text action buttons squatted on the first three until they moved to their own admitted ids
(`flexboard_select_all`, `flexboard_copy`, `flexboard_paste`), the same way the hotkey slots
always did. `editor_info` is still load-bearing, but only as a landmark: the allowed-set array's
name is obfuscated per build, so the splice locates it by looking for a member it knows.

Old prefs on users' devices (`flexboard_select_all` etc. in `mlh.h`, and hotkey values in the
user's shared-prefs) are orphaned by the swap; neither breaks anything — the old ids drop out
of the order filter silently, and hotkey fields in the settings screen are inert until the
hotkey patch returns in native form.

## Pending (investigated, needs implementation)

**Toolbar reorder persistence — register as providers.** Gboard's customize-write path reconstructs the saved order string from the registered-provider list — the `Lmjv`/`Lmjz` order hierarchy — and drops any id it doesn't know. Our injected buttons (`flexboard_select_all`, `flexboard_copy`, `flexboard_paste`, `flexboard_hotkey_N`) aren't providers, so they never appear in the order string, and every customize session forgets them. On rebuild, `merge()` can only place them canonicallly at the front (which is why it currently always does that — see `ToolbarMerge.mergeOrdered`). To fix: register our access points with Gboard's provider machinery via a bytecode patch, instead of injecting into the bar's rendered list after the fact. Then the customize-write path has them by construction, and drag-persistence just works.

**Toolbar crash on clicking the 4-square overflow icon.** Reproduces as a crash depending on state; likely the icons where a draw with our registered ids into Customize's key paths. Needs a device logcat to identify. Likely related to not persisting: the customize view expects reordable ids, ours are absent, and the difference in list contents likely crashes the section/commit handler with an unexpected value.

## Parked: grey out settings rows for un-ticked patches

Replaces the older "conditional settings sections" idea (dead: re-deriving patch selection at
build time; shared-file finalize ordering between patches would be a new, unproven assumption;
checkers would have to weaken their exact row sets). **This design keeps the static screen and
verifies everything statically — the only runtime question is which probe says a feature is in.**

Why it was kicked down the road in Aug 2026: worth doing, not urgent — the only always-visible
inert row today is the swipe slider, and the hotkeys block *works* even with its engine unticked
(it accumulates prefs that go live on a later tick).

**Step 1 — how the screen knows what was patched in (per feature):**

- *Hotkeys: free probe, zero patch code.* `toolbarIdAdmissionPatch` splices `flexboard_hotkey_N`
  strings into resources, so `getIdentifier("flexboard_hotkey_1", "string", pkg) != 0` at
  runtime == hotkeys are in the APK. Nothing to write, nothing to stale.
- *Swipe (and anything without a resource probe): store marker.* The feature patch seeds
  `flexboard_feature_swipe=1` from the app-start hook (the `SeedDefaultsPatch` shape: one
  fingerprint-anchored call into an extension writer). Known caveat: repatching *without* the
  feature leaves the marker behind (prefs survive). Parked decision: acceptable on the dev
  channel; if it ever matters, version the marker value with a build stamp.

**Step 2 — what "greyed" renders as (two tiers):**

- *Tier 1 — text-disabled, zero new dex surface:* the existing first-tap sync pass
  (`FlexboardSettingsFragment.syncRowIconsOnce`, post-popup-build version) also reads the
  probes; rows whose feature is absent get a summary like "needs the Swipe Left to Delete patch"
  and `aA` short-circuits them (still returns true — no dialog, no cycle). No new pinned
  letters, fragment already falls through `d()` nulls safely. ~40 extension lines + the marker
  seed; checker contract untouched (rows always exist).
- *Tier 2 — visual disable:* `setEnabled(false)` on the port — findable the way `n`/`N` were
  (`performClick`'s `ac()Z` reads the enabled field; the letter writing it is one `(... Z)V`
  away). Adds exactly one preflight pin. Real grey, at the cost of a bind-flash: rows look
  enabled from inflation until the first sync pass, because the port offers no row-bind hook
  the stub can override (same staleness story as the icons — accepted there too).

Do NOT weaken the checker lanes to build this: the row-count pin and key-family rules keep exact
sets; greyness is a runtime decision read off properties that don't touch the XML.

Trigger to revisit Tier 2 or conditional sections for real: the screen collecting several more
always-visible sections, or a feature whose inert rows actively mislead (user moves a slider,
nothing happens, no hint why).

## Earlier observations to not lose

- `ToolbarMerge` previously fought itself: merged-by-pairs-approach produced duplicates and drift. Simplified to: always prepend, never read the order.
- `Hotkey.labelAt` now returns "" instead of `null` when empty; `Hotkey.hasContent` and `hotkeySlotOf` in the merge filter empties from the register path. Empty slots must not reach the bar.
- `?attr/colorControlNormal` does not resolve at recompile time; use framework attrs (`android:textColorPrimary`) or hard colors.
- The icon `$0x7f...` in the hotkeys array needs nothing from the listed modes — they stay bundled in the APK and are just unused.
- morphe-patcher's `addInstructionsWithLabels` (label-aware smali insertion) chokes on two sequential insertions into one method if labels are involved (`ArrayIndexOutOfBoundsException: length=0`). The fix is to remove labels from the inserted bytecode entirely (avoid `if-eqz`-generated `:absent` chains); branchless smali survives.
- The settings Activity should render only enabled sections via markers — parked pending a decision; user's roadmap entry says "hotkeys and toolbar config still in flexboard settings when they arent patched; settings should only be added with the patches".


## Shipped

The list above is kept as written; this notes which of it has landed, rather than pruning it.

- **update settings to match rest of gboard** — the screen inherits Gboard's own settings theme, so
  the colours follow it including Material You, and the metrics match androidx preference rows.
- **can we make the backspace swipe work as before without being limited to max 1 word delete** — a
  swipe starting on the backspace key keeps Gboard's distance per word and is not capped.
- **increased tool bar size fit more buttons** — *Bigger Toolbar*, raise-only slider for the
    bar's capacity (the max Gboard allows before pushing to overflow). Stock is five, which is
    fewer than the nine ids Flexboard already admits. The plan is `docs/toolbar-capacity.md`: two
    immediates, raising the flag default and the `[3, 8]` clamp it is measured against, and
    **no count override and no preference writes** — the count stays the user's. Also covers
    **max tool icon slider isnt working**.

- **tool bar amount used to be different between inner and outer screen of a fold** — covered
    natively, and for free: Gboard already branches the count preference by device class
    (`foldable_access_points_count_on_bar` vs `access_points_count_on_bar`) inside `Lmku;->b(I)I`,
    and the capacity plan raises the ceiling both are measured against without writing either. Each
    screen keeps its own count, set through Gboard's own UI.

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
