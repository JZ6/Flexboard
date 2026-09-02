# Toolbar access points beyond the dormant five

> **Superseded in part.** This was written when Flexboard's buttons squatted on ids
> Gboard ships dormant, and it argues for minting our own instead. That argument
> won: the hotkey slots did it first and the text action buttons followed, so every
> Flexboard button now uses a `flexboard_*` id admitted by `ToolbarSlotsPatch`. The
> mechanism described below is what ships; the framing of the dormant ids as the
> resource being budgeted is history.

How Flexboard can put arbitrarily many buttons on Gboard's toolbar while keeping drag-reorder,
persistence and the customize drawer 100% native — researched on Gboard 18.0.3 (dex + arsc, see
preflight pins `toolbar_*` and `docs/gboard-bindings.md` for the anchor table).

## The admission mechanism, end to end

Everything keys on one immutable name set, built **once per controller** from one resource:

```
Lmku.<init>(Context, Lmxf):
    getResources().getStringArray(0x7f0300dc)   ← the ONLY read, pinned
    Lvxe.o(array)  →  iput Lmku.c               ← the allowed-id set
    Lmjv.n(ctx, cfg, sched, Lmku.c, Lwbi.a)     ← handed to the order manager
```

`0x7f0300dc` (`array_0x7f0300dc` under the obfuscated names) is a 43-entry `<array>` of
`@string/…` references whose values are access-point ids: `sticker`, `gif_search`, `translate`,
`settings`, `voice`, … and the five dormants (`editor_info`, `undo_cooperative`,
`muse_toggle_playground_ap`, `jetson_feedback`, `signboard_education`) the current three text
buttons borrow.

The set gates exactly two places, and both were walked instruction by instruction:

1. **Registration fold** — `Lmlh.g(Lmic, Z)` (the bar controller's register call):
   `map.put(name, ap)` happens for every AP, then `Lmku.c.contains(name)` decides the rest:
   true → `Lmku.f:add` dedupe + `Lmjv.f(name)` fold into the shown list; false → the id is only
   logged (`"Invalid access point %s is added"`) and never reaches the bar.
2. **Order read filter** — `Lmjv.c([String, Lvol, Lvxe)`:
   split the `;`-joined saved order, keep only names the set contains.

Persistence is format-stable and needs nothing from us: the order is one preference string of
semicolon-joined ids (`Lmkb.q` reads, `Lmkb.u` reads the built-in default, `Lmkb.w` resets both).
The drawer/bar render names by looking each up in the registry map (`Lmlh.w()` →
`h.get(name)`, nulls skipped; `Lmlh.d()` lists all registered APs) — so **allowed names that are
never registered with an AP draw absolutely nothing**. Widening the set alone is inert.

## Options weighed

| | A. Widen the array (resources only) | B. Prefix-bypass the read filter (dex) | C. Runtime reflection on the live set |
|---|---|---|---|
| Patch surface | `res/values` only: N new strings + N array items | 1 method (`Lmjv.c`), ~3 instructions | 1 extension call |
| Persistence/reorder | untouched | untouched | untouched |
| Gboard-bump fragility | pinned by content sentinel (`editor_info` item) + count | pinned by method shape (already pinned historically) | set is immutable `Lvxe`; private field; reflection off a moving obfuscated target — rejected |
| Rollback history | the class of bug dead since lanes: dev.3/dev.4 both died in values surgery, both reproduced and fixed on the desk | the pre-rollback hotkeys used it; works, but now covers only the read filter — registration fold still needs the set | — |
| Drawer-native | yes | yes | yes |

**Recommended: A.** It injects at the single place both gates read, changes zero dex, and every
risk it carries is now mechanically gated: the in-patch DOM-parse assert, lane B's full arclib
rebuild, and preflight. The dormant-id trick stays for permanent buttons (zero resource churn);
the widened ids are for *families* of buttons (hotkeys 1–12, future slots) with a `flexboard_`
name prefix so our additions are greppable inside Gboard's own logs.

Option B stays documented in git history (`eb84cf4` patch half, `20b34dd` extension half,
`751b0d0` label fix) as the fallback if widening ever becomes impossible (e.g. the array moves
from resources to a phenotype flag — that would actually make this *easier*, a values-less
string write, but is not the case today).

## Implementation sketch (when unpaused)

1. `res/values/strings.xml` merge: one `<string name="flexboard_hotkey_N">flexboard_hotkey_N</string>`
   per admitted id (the value *is* the id — the encoder needs a name and the dex needs the value;
   making them identical keeps both lookups trivial).
2. `res/values/arrays.xml` merge: find the allowed array by **content sentinel** — the item whose
   referenced string resolves to `editor_info` — never by the obfuscated name; append
   `<item>@string/flexboard_hotkey_N</item>` entries.
3. Registration code unchanged: buttons register with ids `flexboard_hotkey_N`; both gates pass
   because the set now contains them.
4. Gates to write before shipping:
   - preflight: sentinel resolution (`editor_info` present + resolvable), array size = 43 + N,
     `Lmku.<init>` reads `0x7f0300dc` exactly once, set stored to field `c`, both consumers
     (`Lmlh.g` contains-check, `Lmjv.c` filter) unchanged in shape (registers/params).
   - lane B: `VALUE_MERGES` entries for the strings+arrays splices (the lane errors on unmapped
     values files — that's today's enforcement; the mapping is the unblock).
   - constants checker: id count shared Kotlin↔Java when hotkeys land.
5. Bar capacity is a *separate* axis (`definedCountOnBar`, bar ctor) and stays parked: admission
   ≠ display space. Widened ids with capacity untouched simply wait in the drawer/overflow like
   any stock AP would.
