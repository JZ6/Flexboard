# Roadmap

Ideas, in no particular order and with no promises. Kept verbatim as written.

update settings to match rest of gboard

some settings disabled like grammer check and ai writing tools


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

On **Hot keys as new tool bar objects** / **add select all copy paste hotkeys**: the two are the same
item, and the toolbar slider above is its prerequisite — adding buttons only helps if there is room
for them. The 17.7.7 derivation of the access-point builder is written up but needs redoing against
18 before anything is built.
