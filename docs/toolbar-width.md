# Toolbar width decoupled from keyboard resize — research and plan

## The problem

Gboard's toolbar (`AccessPointsBar`) has no width of its own — it's `match_parent` inside
`ScaledKeyboardViewInner`, and `onMeasure` sets its width to whatever the parent's MeasureSpec
says:

```
0: invoke-static {v8}, View$MeasureSpec;->getSize(I)I
3: move-result v8
4: invoke-static {v9}, View$MeasureSpec;->getSize(I)I
7: move-result v9
8: invoke-virtual {v7, v8, v9}, AccessPointsBar;->setMeasuredDimension(II)V
```

So when the keyboard narrows, the toolbar narrows with it. `onLayout` then **centers** the
buttons within whatever width the bar reports (`x:I = (getWidth() - itemsWidth) / 2`), and
`K(barWidth, count)` computes per-item width from the bar's width — both follow automatically.

## Three things that "resize the keyboard" can mean

1. **Height resize** (the drag handle / settings slider). Scales key *content* (text/icon size)
   via the `Lstr;->t(FF)V` interface, which writes to the bar's `s:F` and the layout helper's
   `c:F`. **Does not change the bar's measured width** — `onMeasure` still reads the parent.

2. **One-handed / floating mode**. Narrows the keyboard *visually* via `View.setScaleX/Y` on
   `ScaledKeyboardViewInner` (the parent of the bar). The bar's **layout width stays full** —
   only its drawing is scaled. Hit-testing also stays in the full-width coordinate space.

3. **Foldable / tablet body width**. Selects a different keyboard body width (a different one
   of 10 mode layouts, each `<include>`-ing the same `res/HNz.xml` that declares the bar). The bar
   narrows because the whole keyboard body is a different width, not because the bar is told a
   different number.

## Which resize are you trying to defeat?

The answer determines the seam.

### If the complaint is "toolbar shrinks in one-handed mode" — seam C (cheapest)

One-handed mode narrows via `setScaleX` on `ScaledKeyboardViewInner`. The bar's layout width is
already full; only the drawing is shrunk. A single counter-scale on the bar undoes it:

```kotlin
// In the extension, when one-handed mode activates:
bar.scaleX = 1f / parentScaleX
bar.pivotX = ...  // fix the pivot to keep it centered or edge-anchored
```

One setter, no measure surgery. The bar already measures full-width; this just un-does the
visual shrink the parent applies. The toolbar buttons stay full-size while the keys narrow.

**Caveat:** the toolbar's touch target also un-scales (it would extend beyond the visible
keyboard edge), so this needs either a clip or a pivot that keeps it within the keyboard window.
On a one-handed-narrowed keyboard the toolbar would be wider than the keys — which is probably
what the user wants, but needs to be anchored to one edge, not centered, or it would overflow
the IME window on one side.

### If the complaint is "toolbar should be a fixed width regardless of keyboard body" — seam A+B

This is the harder case. Two steps:

**Step A — `onMeasure` (necessary):** replace `setMeasuredDimension(getSize(widthMS), …)` with
a Flexboard-supplied width:

```
invoke-static { p1 }, ToolbarWidth;->for(Landroid/content/Context;I)I
move-result v8
invoke-virtual { v7, v8, v9 }, AccessPointsBar;->setMeasuredDimension(II)V
```

`onLayout`'s centering and `K`'s item-width both follow from the measured width, so no second
patch for geometry.

**Step B — the parent must honour it (sufficient):** `onMeasure` alone is not enough — the
parent (`ManagedFrameLayout` inside `ScaledKeyboardViewInner`) lays the bar out at the parent's
width via `View.getWidth()`, not the bar's measured width. If the parent measures EXACTLY at the
narrowed keyboard width, the bar cannot become wider than its parent through `onMeasure` alone.

Two approaches:
- **Re-parent** the bar above `ScaledKeyboardViewInner` (into the outer `SoftKeyboardView` or a
  full-width overlay) so its parent is the full-width IME window, not the scaled/narrowed body.
  Cleanest conceptually — the bar's parent is then always the full IME width — but it's a
  structural change to the inflate hierarchy (10 mode layouts), a large and fragile patch
  surface.
- **Patch the parent's `onLayout`** to give the bar a wider frame than its own width. Simpler
  than re-parenting but still touches a generic `ManagedFrameLayout` — and would need to clip
  the bar to the IME window if the fixed width exceeds it.

### If the complaint is "toolbar should be wider than the keyboard on foldables/tablets" — seam A+B

Same as above, but the width source is the device class (foldable inner/outer, tablet). The
`ToolbarWidth.for(context, parentWidth)` extension would read a Flexboard slider and return
the larger of the slider and the parent width — the same raise-only semantics as the capacity
patch.

## Recommended approach

Start with **seam C** (counter-scale) if the user's actual complaint is one-handed mode — it's
one extension call, no dex patch, no layout surgery. If that's insufficient or the complaint is
about foldable/tablet body width, move to **seam A** (patch `onMeasure`) as the necessary first
step and evaluate whether the parent's layout needs step B.

## Preflight pins (for any approach)

- `AccessPointsBar;->onMeasure(Landroid/view/View$MeasureSpec;I)V` — register count, the
  `setMeasuredDimension` call at pc 8, and the `getSize` calls that feed it.
- `AccessPointsBar;->onLayout(ZIIII)V` — register count, the centering math at pc 45-52.
- `AccessPointsBar;->K(II)I` — register count, the `Math.min` return.
- The 10 mode layouts that `<include>` `res/HNz.xml` (resource id `0x7f0e0225`) — pin the include
  count so a mode layout being added or removed is caught.
