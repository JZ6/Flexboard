#!/usr/bin/env python3
"""Every obfuscated Gboard reference in the patches must also be a preflight pin.

A patch can reach for a new dex seam with nothing but a string literal. If nobody adds the
matching anchor to tools/apk/preflight.py, all gates stay green: the Kotlin compiles, the other
checkers run their own lanes, and the first machine to learn that the seam moved is a phone
running Morphe. This lane makes the omission loud instead.

Rule: any string literal in patches/**/*.kt that *names an obfuscated Gboard class or member*
(short single-segment lowercase name like `Lmlh;` or `Lbhs;->q(...)`) must have its **class**
appear verbatim anywhere in tools/apk/preflight.py. Preflight anchors classes in its literal
anchor table (`'sigcheck': 'Lrpv;'`) and then pins members structurally — building descriptors
from constants and asserting register/return shapes — so a member-level verbatim rule would
false-alarm on well-pinned seams; the class anchor is the level where an omission can truly be
silent. Framework (`Ljava/...`, `Landroid/...`), Morphe (`Lapp/morphe/...`), and our own
(`Ldev/jz6/...`) literals are out of scope by construction — their names are stable or ours. A
literal that is *derived at patch time* (string concatenation or a fingerprint shape) never
appears here statically; those seams belong to the runtime `check(...) error(...)` messages
preflight re-implements by hand.

    check_dex_pins.py   -> silent, or exits 1 listing every unpinned reference

If a reference is deliberately pin-less (a heuristic probe rather than an anchor), list it in
PIN_LESS with the reason; the lane then fails if an exemption stops being used.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
PATCHES = ROOT / "patches/src/main/kotlin"
PREFLIGHT = ROOT / "tools/apk/preflight.py"

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")
STRING_LITERAL = re.compile(r'"([^"\n]*)"')

# Obfuscated-Gboard-shaped literals: a single package segment, R8's output shape. Longer chains
# (Ljava/util/List;, Lapp/morphe/...;) and our own classes (Ldev/jz6/...) are excluded by the
# segment rule itself.
#
# The bound used to be [a-z]{2,6}, which missed three real shapes. A single letter: R8 emits
# La;..Lz; and this dex declares all 26 of them. A digit anywhere after the first character: R8
# uses them once the letters run out, and 20,216 top-level obfuscated classes here means they do.
# And a nested class, whose `$` never matched at all. 49 classes in this build sit outside the old
# pattern, every one of them a literal this lane would have waved through unpinned.
OBFUSCATED = r"L[a-z][a-z0-9]{0,7}(?:\$[\w$]+)?;"
CLASS_REF = re.compile(rf"^{OBFUSCATED}$")
MEMBER_REF = re.compile(rf"^{OBFUSCATED}->")

# A floor, because this lane's whole output is driven by what it happens to find. If the patches
# move to derived descriptors and stop spelling literals, seen_refs empties, every loop below
# iterates nothing, and the check passes while guarding nothing at all -- the failure mode this
# file's own docstring warns about for PIN_LESS. Raise it when the real count rises.
MINIMUM_REFS = 20

# Deliberately unpinned literals, each with its reason. Add, don't argue — but write the reason,
# because this list is the document of which dex seams ride unguarded.
PIN_LESS = {
}

pins_text = PREFLIGHT.read_text()
problems = []
seen_refs = set()

for path in sorted(PATCHES.rglob("*.kt")):
    text = LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", path.read_text()))
    for line_no, line in enumerate(text.splitlines(), 1):
        for literal in STRING_LITERAL.findall(line):
            if not (CLASS_REF.match(literal) or MEMBER_REF.match(literal)):
                continue
            seen_refs.add(literal)
            owner = literal.split("->", 1)[0]
            if owner in PIN_LESS:
                continue
            if owner not in pins_text:
                problems.append(
                    f"  {path.name}:{line_no} references {literal} — its class {owner} is "
                    f"not an anchor in tools/apk/preflight.py"
                )

for exempt in PIN_LESS:
    if exempt not in seen_refs:
        problems.append(f"  PIN_LESS exemption {exempt} is no longer referenced anywhere")

if len(seen_refs) < MINIMUM_REFS:
    problems.append(
        f"  only {len(seen_refs)} obfuscated literals found, expected at least {MINIMUM_REFS} — "
        f"either the literals moved out of the patch sources or the pattern stopped matching "
        f"them, and this lane is now checking nothing"
    )

if problems:
    print("Dex references without a preflight pin:")
    print("\n".join(problems))
    sys.exit(1)
