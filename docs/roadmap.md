# Roadmap

Ideas, in no particular order and with no promises. Kept verbatim as written.

update settings to match rest of gboard

some settings disabled like grammer check and ai writing tools, rambler mode etc


max tool icon slider isnt working, i dont see amount of tools changing


can we make the backspace swipe work as before without being limited to max 1 word delete

flick up to undo autocorrect 

Hot keys as new tool bar objects

gesture down on a to select all

add select all copy paste hotkeys

increased tool bar size fit more buttons

## Shipped

The list above is kept as written; this notes which of it has landed, rather than pruning it.

- **update settings to match rest of gboard** — the screen inherits Gboard's own settings theme, so
  the colours follow it including Material You, and the metrics match androidx preference rows.
- **can we make the backspace swipe work as before without being limited to max 1 word delete** — a
  swipe starting on the backspace key keeps Gboard's distance per word and is not capped.
- **increased tool bar size fit more buttons** — *Bigger Toolbar*, a 3–10 slider for the number of
  icons on the access points bar. **Shipped in `1.1.0-dev.1`, withheld again in `1.1.0-dev.2`**: it
  did not work on device. Commented out rather than deleted; the diagnosis and the better insertion
  point are at the top of `ToolbarCountPatch.kt`.

- **add select all copy paste hotkeys** — partly. *Select All Button* puts a one-tap **Select all**
  on the toolbar. Copy, cut and paste are not built; the mechanism now exists for them, so they are
  a repeat of the same shape rather than new research.

On **Hot keys as new tool bar objects**: the toolbar slider was assumed to be a prerequisite, on the
grounds that adding buttons only helps if there is room. That turned out to be wrong, which is
lucky, because the slider does not work. A new button is prepended to the ordered list the bar is
built from, so it takes the first slot and pushes whatever used to be last into the overflow panel
— no extra room needed. The slider would still make it nicer.

On **gesture down on a to select all**: not built, and deliberately not. It is the same action
reached a different way, and the toolbar button was the cheaper half. Gboard's own long-press
popups are defined in compiled keyboard metadata rather than in code, so putting an action there
means hooking the soft-key bind path and rewriting that metadata at runtime — a much larger change
than the one access-point insertion this took.
