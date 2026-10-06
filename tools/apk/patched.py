#!/usr/bin/env python3
"""Read a *patched* APK and compare a method against the stock one.

## Why this exists

`preflight.py` reads stock Gboard. The gate now applies a real bundle with the driver and checks
patched output using `verify.py`; this tool is its manual, instruction-by-instruction companion.

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

    tools/apk/patched.py flexboard.apk 'Lmm;->run()V' --stock gboard-apk

## What it will not tell you

It shows what was emitted. `verify.py` catches some ART rejection classes automatically, but only
running the patched app on a device proves runtime behaviour.
"""

import os
import re
import shutil
import sys
import tempfile
import zipfile
import difflib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dexlib  # noqa: E402
import dalvik_dis as ddis  # noqa: E402


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


def diff_text(stock, patched):
    """Align shifted branch PCs, but still show a branch retargeted to different code."""
    def normalized(rows):
        return [f"{nm} {re.sub(r'-> [0-9]+$', '-> <pc>', a or '')}"
                for _pc, nm, a in rows]

    old, new = normalized(stock), normalized(patched)
    blocks = difflib.SequenceMatcher(None, old, new).get_matching_blocks()
    mapped = {i: j for block in blocks
              for i, j in zip(range(block.a, block.a + block.size),
                              range(block.b, block.b + block.size))}
    old_pc = {pc: i for i, (pc, _n, _a) in enumerate(stock)}
    new_pc = {pc: i for i, (pc, _n, _a) in enumerate(patched)}
    for i, j in mapped.items():
        old_target = re.search(r"-> (\d+)$", stock[i][2] or "")
        new_target = re.search(r"-> (\d+)$", patched[j][2] or "")
        if not old_target or not new_target:
            continue
        old_index = old_pc.get(int(old_target.group(1)))
        new_index = new_pc.get(int(new_target.group(1)))
        if old_index is None or new_index is None:
            raise ValueError(f"branch at {stock[i][0]} or {patched[j][0]} has no instruction target")
        if mapped.get(old_index) != new_index:
            old[i] += f" (target stock instruction {old_index})"
            new[j] += f" (target patched instruction {new_index})"
    return old, new


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    stock_tree = None
    if "--stock" in sys.argv:
        at = sys.argv.index("--stock")
        if at + 1 >= len(sys.argv):
            print("--stock requires a directory containing extracted dex files", file=sys.stderr)
            return 2
        stock_tree = sys.argv[at + 1]
        args = [a for j, a in enumerate(sys.argv[1:], 1)
                if j not in (at, at + 1) and not a.startswith("--")]

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

    # Align by instruction rather than pc. A branch target's absolute pc also shifts after an
    # insertion, so normalize it before alignment/counting; otherwise an insertion in g() looked
    # like 28 removed instructions, all of which were actually unchanged branches.
    stock_text, patched_text = diff_text(stock, patched)
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
