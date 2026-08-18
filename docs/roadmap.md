# Roadmap

Ideas, in no particular order and with no promises. Kept verbatim as written.

update settings to match rest of gboard

some settings disabled like grammer check and ai writing tools, rambler mode etc

max tool icon slider isnt working, i dont see amount of tools changing

flick up to undo autocorrect 

Hot keys as new tool bar objects

gesture down on a to select all

tool bar amount used to be different between inner and outer screen of a fold

use graph 6 material icon for fleksy settings

add select all copy paste hotkeys

increased tool bar size fit more buttons

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

On **Hot keys as new tool bar objects**: the toolbar slider was assumed to be a prerequisite, on the
grounds that adding buttons only helps if there is room. That turned out to be wrong, which was
lucky at the time, because the slider did not work. A new button is prepended to the ordered list
the bar is built from, so it takes the first slot and pushes whatever used to be last into the
overflow panel — no extra room needed. With the slider working the two now compose properly: raise
the count and the button costs nothing that used to be visible.

On **gesture down on a to select all**: not built, and deliberately not. It is the same action
reached a different way, and the toolbar button was the cheaper half. Gboard's own long-press
popups are defined in compiled keyboard metadata rather than in code, so putting an action there
means hooking the soft-key bind path and rewriting that metadata at runtime — a much larger change
than the one access-point insertion this took.
