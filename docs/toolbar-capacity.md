# Toolbar capacity — the plan, and why the last two attempts were wrong

Flexboard admits **nine** toolbar ids on `dev` — six hotkeys (`flexboard_hotkey_1..6`) and three
text actions (`flexboard_select_all`, `flexboard_copy`, `flexboard_paste`). Gboard's bar holds
**five icons**, and those five are shared with Gboard's own access points. A user cannot display
even the six hotkeys we ship.

The capacity is therefore not a cosmetic nice-to-have. It is the binding constraint on two
features that are already released.

This note records how the count is actually produced, why the two previous attempts at raising it
both failed, and the plan that follows from the difference.

## How Gboard computes the count

`Lmku;->b(I)I` is the sole producer of the rendered count. Its whole body:

```
 0 iget-object   Lmku;->e:Lmjv;
 1 iget-object   Lmku;->h:Lnmm;      # device class
 2 sget-object   Lnmm;->f:Lnmm;      # DEVICE_FOLDABLE
 3 const         0x7f140a43          # foldable_access_points_count_on_bar
 4 const         0x7f1409af          # access_points_count_on_bar
 5 iget-object   Lmku;->b:Lqhy;      # preference store
 6 const/4       -0x1                # default: unset
 7 invoke-virtual Lcdl;->l(II)I      # read the user's preference
 9 invoke-virtual Lmjv;->a(II)I      # the gate
11 return
```

The gate (`docs/gboard-bindings.md` carries the row):

```
Lmjv;->a(pref, cap) = pref >= 0 ? min(pref, cap) : Lmjv;->b(cap)
Lmjv;->b(cap)       = m() ? min(3, cap) : cap        # reduced mode floors at 3
```

and `Lmlh;->C(List)V` then takes `n = min(Lmku;->b(bar.i()), size)`.

**`min(pref, capacity)` is not an obstacle. It is the mechanism by which the user removes buttons
from the bar.** Gboard expresses "take this off the bar" as a decrease of
`access_points_count_on_bar`, leaving the button in the order list. Everything below follows from
that one fact.

## Why stock is five

The capacity comes from a Phenotype flag with a built-in default of `-1`:

```
Lcom/.../AccessPointsBar;-><clinit>()V
  0: const-string   v0, 'config_max_access_points'
  2: const-wide/16  v1, #-1                     <-- the built-in default
  4: const-string   v3, 'ro.com.google.ime.top_icon_num'
  6: invoke-static  Lnxs;->e(String, J, String)Lnxp;
 10: sput-object    AccessPointsBar->a:Lnxp;
```

and the constructor clamps it to `[3, 8]`, falling back to the styled attribute when it is out of
range:

```
Lcom/.../AccessPointsBar;-><init>(Context, AttributeSet)V
 43: const/4    v3, #3       # lower bound  -- ALSO the styled-attribute index, see below
 44: const/4    v4, #5       # styled-attribute fallback
 45: invoke-virtual {v0, v2, v4}, TypedArray;->getInt(II)I
 48: move-result v2
 51: invoke-interface {v5}, Lnxp;->g()Ljava/lang/Object;
 57: invoke-virtual {v5}, Ljava/lang/Long;->intValue()I
 60: move-result v4
 61: const/16   v5, #8       # THE CEILING
 63: if-gt      v4, v5, -> 68
 65: if-lt      v4, v3, -> 68
 67: goto       -> 72        # accept the flag value
 68: move       v4, v2       # reject -> fall back to the attribute (5)
 72: iput       v4, v6, AccessPointsBar->m:I
```

`-1` fails the lower bound, so the capacity is the styled attribute: **5**. Confirmed on a Pixel 6,
which means Phenotype is not pushing an override on that device.

## Why the two previous attempts failed

Both wrote the number into the wrong place, in opposite directions.

**Era A (`ToolbarCountPatch.kt`, removed in `54703a9`)** overrode the count at the entry of
`Lmku;->b(I)I`, returning the slider value and bypassing the gate. It also seeded Gboard's own
`access_points_count_on_bar` to 15, which `f812fd9` removed as inert-and-visible — a phantom 15 in
Gboard's own settings screen.

Bypassing the gate means bypassing the user's removals. Era A's release note (`e588743`) admits
the checklist items *"drag-to-reorder, Gboard's own settings screens"* were never exercised, so
this was probably present and simply untested.

**Era B (`BiggerToolbarPatch.kt`, removed in `e075526`)** raised only the capacity and then had the
extension stage Gboard's count preference so the gate would resolve to the slider:

```java
if (value <= stock) return stock;
stageCountPreference(prefs, context, COUNT_ON_BAR_ID, value);
stageCountPreference(prefs, context, FOLDABLE_COUNT_ON_BAR_ID, value);
return value;
```

`maxFor` runs on **every bar construction**, and `stageCountPreference` only skips when the stored
value already equals the slider. So removing a button lowered Gboard's count, the next inflation
wrote it straight back up, and the bar refilled from the order list. Reported on device as *buttons
returning after being removed*. That is the reason for the revert, and it was never written down.

**The shared root cause: both attempts tried to own the count. The count belongs to the user.**

## The plan

Raise the capacity. Touch nothing else. Specifically, **no count override and no writes to any
Gboard preference.**

Two immediate edits, both anchored by the `config_max_access_points` string, which R8 cannot
rename:

| Where | From | To |
|---|---|---|
| `<clinit>` offset 2 | `const-wide/16 v1, #-1` | the desired capacity |
| `<init>` offset 61 | `const/16 v5, #8` | the new ceiling |

The first makes the flag supply a real value; the second lets it past the clamp. With the capacity
raised and the count left alone:

- an unset preference yields `count = capacity`, so the bar grows;
- removing a button lowers Gboard's preference and `min(pref, capacity)` honours it;
- reduced mode still floors at 3, as Gboard intends.

No insertions, no scratch registers, no liveness analysis, no extension code, and nothing on the
write side to fight the user with.

### Traps

- **Do not touch the lower bound.** `const/4 v3, #3` at offset 43 is reused at offset 75 as the
  styled-attribute index for `getDimension(v3, v2)`. Editing it corrupts the bar's padding.
- **`const/16` is a 21s immediate**, so the encoding is not the constraint. `K(II)I` divides the
  available width (`min((w + 2(e+f))/(n+1), w/n)`) rather than overflowing it, so a higher ceiling
  only makes each icon narrower.
- **A Phenotype push would override the default.** The `<clinit>` edit changes the fallback, not
  the served value. Today no override is present; if one appears, the capacity reverts and the
  feature silently stops working. Preferring a hook on `Lnxp;->g()` would beat that, at the cost of
  an insertion.
- **A pre-existing low preference still caps the user.** Someone whose
  `access_points_count_on_bar` is already 5 sees 5 after patching and must raise it in Gboard's own
  toolbar settings. That is discoverability, not a defect — and "fixing" it with a seed is exactly
  what produced the phantom 15 and the Era B regression.

### Suggested ceiling

Twelve. Nine Flexboard ids plus a few of Gboard's. Note that the old maximum of 12 was chosen
without this rationale — `7c5dd48` says *"the range exists to be wide rather than uniformly
comfortable"* — so the number is the same by coincidence, not by inheritance.

### Pins this needs

- both immediates, by value and by offset relative to the flag-name anchor;
- the clamp's branch structure (`if-gt` / `if-lt` / `goto` / `move`) so a reshaped constructor
  fails loudly rather than silently writing the wrong register;
- `->m:I` is still the field the accepted value lands in;
- the gate `Lmjv;->a(II)I` still takes the capacity and still produces the count's return value,
  so that a future change to Gboard's own preference handling cannot quietly turn removals back
  into resurrections.

## Verification note

The disassembly above came from `tools/apk/dis.py`. Earlier passes over this code used
`dexlib.walk`, which silently omits `const-wide` and every `if-*` — it rendered this constructor's
clamp as three unrelated constants and no branches, which is how the ceiling was briefly mistaken
for unreachable. Use `dis.show()` for anything that reasons about control flow.
