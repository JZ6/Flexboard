#!/usr/bin/env python3
"""Read a *patched* APK and compare a method against the stock one.

## Why this exists

Everything else in this directory reads the APK Gboard ships. `preflight.py` takes the stock dex
tree and the stock resources; `tools/gate` compiles the patches and pins Gboard. **No lane has ever
looked at a class the patcher produced.**

That gap shipped two releases of a keyboard that would not open, and then three confident diagnoses
of why, at least two of which were wrong. Each one was argued from the *stock* disassembly plus a
mental model of what the emission would become. Nobody looked at what it actually became, because
there was no way to.

There is no obstacle to looking. The disassembler is dex-generic: `dexlib.load()` takes any
directory of `.dex` and `dis.find` works on whatever is in it. Morphe Manager writes a patched APK
to the device and it can be pulled off. This turns that into two commands.

## Use

    tools/apk/patched.py <patched.apk> <descriptor>
    tools/apk/patched.py <patched.apk> <descriptor> --stock gboard-apk

`--stock` adds a side-by-side against the unpatched method, which is usually the point: the emitted
instructions are whatever is in the patched listing and not in the stock one.

    tools/apk/patched.py flexboard.apk 'Lpvf;->t(Lpvi;Landroid/view/MotionEvent;I)V' --stock gboard-apk

## What it will not tell you

It shows what was emitted. It does not run ART's verifier, so it cannot say "this is why the class
was rejected" -- it can only show you the code well enough to work that out. A real merge check is
the obvious next tool and belongs on top of this one.
"""

import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dexlib  # noqa: E402
import dis as ddis  # noqa: E402


def extract(apk, into):
    """Every classes*.dex out of [apk]. Returns the directory."""
    with zipfile.ZipFile(apk) as zf:
        names = [n for n in zf.namelist()
                 if n.startswith("classes") and n.endswith(".dex")]
        if not names:
            raise SystemExit(f"{apk} contains no classes*.dex — is it an APK?")
        for name in sorted(names):
            with zf.open(name) as src, open(os.path.join(into, os.path.basename(name)), "wb") as dst:
                shutil.copyfileobj(src, dst)
    return into


def listing(tree, descriptor):
    """(registers, [(pc, mnemonic, operands)]) for [descriptor], or (None, None)."""
    dl = dexlib.load(tree)
    d, c, _maf = ddis.find(descriptor, dl)
    if not c:
        return None, None
    return c, ddis.disasm(d, c)


def render(rows):
    return [f"{pc:>5}: {nm:<22} {(a or '')}" for pc, nm, a in rows]


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    stock_tree = None
    if "--stock" in sys.argv:
        stock_tree = sys.argv[sys.argv.index("--stock") + 1]
        args = [a for a in args if a != stock_tree]

    if len(args) != 2:
        print(__doc__.strip().split("## Use")[1].split("## What it")[0].strip(), file=sys.stderr)
        return 2
    apk, descriptor = args

    with tempfile.TemporaryDirectory() as tmp:
        extract(apk, tmp)
        patched_code, patched = listing(tmp, descriptor)

    if patched is None:
        print(f"not found in {apk}: {descriptor}", file=sys.stderr)
        print("A method the patcher *removed* looks identical to one that was never there, so "
              "check the descriptor before concluding anything.", file=sys.stderr)
        return 1

    if stock_tree is None:
        print(f"=== {descriptor}  registers={patched_code['registers']} ===")
        print("\n".join(render(patched)))
        return 0

    stock_code, stock = listing(stock_tree, descriptor)
    if stock is None:
        print(f"not found in {stock_tree}: {descriptor}", file=sys.stderr)
        return 1

    print(f"=== {descriptor}")
    print(f"    stock   registers={stock_code['registers']}  instructions={len(stock)}")
    print(f"    patched registers={patched_code['registers']}  instructions={len(patched)}")
    if patched_code["registers"] != stock_code["registers"]:
        print("    !! the frame changed size, which every scratch-register choice was measured "
              "against")
    print()

    # A plain sequence diff rather than pc alignment: inserting instructions renumbers every pc
    # after the seam, so aligning on pc would report the whole tail as changed.
    import difflib
    stock_text = [f"{nm} {(a or '')}" for _pc, nm, a in stock]
    patched_text = [f"{nm} {(a or '')}" for _pc, nm, a in patched]
    added = removed = 0
    for line in difflib.unified_diff(stock_text, patched_text,
                                     fromfile="stock", tofile="patched", lineterm="", n=3):
        if line.startswith("+") and not line.startswith("+++"):
            added += 1
        elif line.startswith("-") and not line.startswith("---"):
            removed += 1
        print("   ", line)

    print()
    print(f"    {added} instruction(s) added, {removed} removed")
    if removed:
        print("    Removed instructions are worth a second look: most emissions here insert and "
              "none are supposed to excise.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
