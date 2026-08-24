# A bigger toolbar, natively — how far the bar can grow, and who decides

Researched on Gboard 18.0.3 (dex trace below). Short version: **Gboard already carries the whole
mechanism; it ships unset.** The patch needs one store write (a preference Gboard reads and never
writes) plus one two-instruction smali tail at a single seam. User choice and ordering stay
completely stock.

## The stock chain, end to end

1. **Hard ceiling per bar instance** — `AccessPointsBar.<init>`:
   ```smali
   v2 = a.getInt(2, 5)                 # XML styleable default = 5
   v4 = config_max_access_points(long) # phenotype flag, sysprop: ro.com.google.ime.top_icon_num
   if (v4 in 3..8) m = v4 else m = v2  # flag valid only inside [3,8]
   ```
   So the ceiling is 5 by default, up to 8 via a phenotype flag nothing sets (flag default `-1`,
   no sync on patched builds — same family as the grammar row). Redundant with what the patch
   needs: the entire value lands in field `m` at `iput` on pc 72.
2. **The shown count** — `Lmku.b(I)I` ("definedCountOnBar" in their own log line):
   reads the *user* preference (`access_points_count_on_bar`, or
   `foldable_access_points_count_on_bar` when the device class says foldable — Gboard already
   branches), default `-1` = unset. Then `Lmjv.a(pref, capacity)` =
   `min(pref, capacity)`; unset → `Lmjv.b(n)` = their phones-vs-reduced-state clamp
   (`min(3, n)` on the reduced variant). On top of all of which the callers
   (`Lmlh.C(List)`, `Lmlh.e`) clamp to the ordered list's actual size.
3. Compose: **shown = min(pref, m, order.length)**. On stock, pref is unset, so stock shows
   `min(default-count, m)` ≈ 5, matching every phone.

## What that means for "raise the max, let the user pick"

The native answer:

- **Raise** = make `m` as big as sane: two instructions at the `iput m` seam:
  `m = max(m, flexboardMax)`. Nothing else in the chain has to move — `min(pref, m, order)`
  already bends the right way.
- **User choice** = the stock ordering flow (customize sheet → drag). Since pref is unset by
  default on patched builds, Gboard's own unset-path gives "as many as fit" while users who drag
  more in get them. We additionally *stage both Gboard prefs* (plain + foldable) to the slider
  number at apply-time: that keeps `min(pref, m)` neutral — the slider wins, not the unset path —
  **and the foldable outer/inner displays just work**, because the class check is Gboard's own
  (`Lnmm.f` in `Lmku.b` — preflight already pins the foldable enum constant).

The width-fit reality (a 320dp bar can't hold 12 buttons) is handled by Gboard's own measure
paths (`onMeasure` reads the same field `m`); the user-tunes-until-pretty bit is theirs, with the
overflow drawer as their own escape hatch. No geometry work belongs to this patch.

## The patch shape

1. **Extension**: `ToolbarCapacity.maxFor(Context)` reads our slider
   `flexboard_toolbar_max` (0 = stock), `min`'d at 0, no upper bound beyond 12 (preflight pins
   its bounds with the others). Where it runs: invoked at patch time? no — at the seam.
   On the same apply path, `GboardSettings`-style writers stage `access_points_count_on_bar` and
   `foldable_access_points_count_on_bar` to the same int (id-resolved strings, same pattern as
   existing force-* writes).
2. **Patch**: one smali tail insert after `iput m` in the bar ctor: call the extension, compare,
   keep the larger. Scratch registers from preflight verification at that insertion point.
3. **Settings**: one InlineSlider row "Slots on the toolbar (max)" under the existing Flexboard
   screen. defaults to the stock 5.

## Why not the phenotype flag itself

`config_max_access_points` *is* the flag, but clobbering the `[3..8]` validity window presents a
permanent cap of 8, reads from a store we don't control, and syncs nothing without GMS. The
store-write + one tail insert is smaller, pinnable, and survives every Gboard bump that doesn't
rename `AccessPointsBar`.

## Pins to add before implementing

- bar ctor exists with register count 9 and the exact pc window (`getInt(2,5)`, `g()`, the 3/8
  window, `iput m` on pc 72 — all asserted positionally).
- flag triple in `<clinit>` present (`config_max_access_points`, `ro.com.google.ime.top_icon_num`,
  `Lnxs;->e`).
- both pref key strings still resolve through `Lqhy`'s name resolution, both same semantics.
- insertion-point registers dead at the seam (pin them the way the hotkey seam's were).

## Rollout

One user-visible patch, default on, default slider 5; dev release, device test is: stock bar
unchanged at slider=5, slider=8 shows up to 8 with more dragged in from customize, foldable outer
screen unchanged, inner screen same, overflow drawer unchanged.
