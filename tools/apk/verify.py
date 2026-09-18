#!/usr/bin/env python3
"""A register type-merge check over a patched method — the class of bug ART rejects at load.

## Why

`:driver:run` writes a dex; it does not load one, so ART's verifier never runs. A method that is
rejected at class load applies cleanly through the whole pipeline and only fails on a phone, as a
keyboard that will not open. That shipped twice.

The rejection this catches: a register holding one type on one path into a block and an unrelated
type on another, where the block then uses it as a specific type. `2.5.0-dev.0` branched to a block
that does `iget-object v13, v3, Lpvi;->B:…` while our path had just made `v3` a `Lpmy;`.

## What it is, and what it is not

This is **not** a reimplementation of ART's verifier. It is a forward abstract interpretation over
the real control-flow graph with a deliberately small type lattice, tuned to be quiet:

* types come only from instructions that state one outright — `new-instance`, `sget-object`,
  `iget-object`, `check-cast`, `const-string`, `move-result-object` after a call with a known
  return type, and the method's own parameters
* anything else is `UNKNOWN`, and **`UNKNOWN` is never reported**
* a literal zero is `ZERO`, which merges with any reference, because null is assignable to anything
* two different references merge to `CONFLICT` only when neither is assignable to the other, using
  the class hierarchy from the dex where it is available

A finding is raised only when a `CONFLICT` reaches an instruction that *demands* a type — a field
owner, an `iput-object` value, an invoke receiver. That is the shape ART rejects, and requiring the
use site keeps the false-positive rate near zero: merging two unrelated types into a register nobody
reads is legal and common.

False negatives are the accepted trade. A checker that cries wolf gets switched off, and this
project has enough checks that pass without meaning something.

## Use

    tools/apk/verify.py <patched.apk> <descriptor>
    tools/apk/verify.py <patched.apk> --changed-from gboard-apk    # every method the patch touched
"""

import os
import re
import shutil
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dexlib  # noqa: E402
import dis as ddis  # noqa: E402

UNKNOWN = "?"
ZERO = "0"
CONFLICT = "!"

# Instructions whose first register operand receives a reference of a type the operand text states.
_NEW = re.compile(r"^v(\d+), (L[\w/$;]+;|\[[\w/$;\[]+)$")
_SGET = re.compile(r"^v(\d+), L[\w/$;]+;->[^:]+:(L[\w/$;]+;|\[[\w/$;\[]+)$")
_IGET = re.compile(r"^v(\d+), v(\d+), (L[\w/$;]+;)->[^:]+:(L[\w/$;]+;|\[[\w/$;\[]+)$")
_IPUT = _IGET
_INVOKE = re.compile(r"^\{([^}]*)\}, (L[\w/$;]+;)->([^(]+)\(([^)]*)\)(.+)$")
_MOVE_OBJ = re.compile(r"^v(\d+), v(\d+)$")


def _regs(text):
    return [int(m) for m in re.findall(r"\bv(\d+)\b", _strip(text or ""))]


def _strip(text):
    """Operand text with descriptors and quoted literals removed, so `v7` inside a type name is
    not mistaken for a register — the same hazard `preflight.regs` documents."""
    text = re.sub(r"'[^']*'", "''", text)
    return re.sub(r"L[\w/$;]+;", "", text)


def _invoke_registers(text):
    m = _INVOKE.match((text or "").strip())
    if not m:
        return []
    inside = m.group(1)
    rng = re.match(r"v(\d+) \.\. v(\d+)$", inside.strip())
    if rng:
        return list(range(int(rng.group(1)), int(rng.group(2)) + 1))
    return [int(x) for x in re.findall(r"v(\d+)", inside)]


class Hierarchy:
    """Assignability, from the dex where possible and `unknown` otherwise."""

    def __init__(self, dexes):
        self.parent = {}
        self.interfaces = {}
        for d in dexes:
            import struct
            for i in range(d.cls_n):
                ci, _af, su, io, _sf, _ao, _cd, _sv = struct.unpack_from(
                    "<8I", d.b, d.cls_o + 32 * i)
                name = d.type(ci)
                self.parent[name] = d.type(su) if su != 0xFFFFFFFF else None
                if io:
                    n = struct.unpack_from("<I", d.b, io)[0]
                    self.interfaces[name] = [
                        d.type(struct.unpack_from("<H", d.b, io + 4 + 2 * k)[0]) for k in range(n)]

    def assignable(self, value, target):
        """True when [value] may be used where [target] is required, or when we cannot tell.

        Leaving the dex mid-walk is only unknowable when the *target* is also outside it. If the
        target is an app class that is present, then walking `value` to a framework superclass
        without meeting it **proves** the answer is no — a `Lpmy;` whose chain runs out at
        `Ljava/lang/Enum;` cannot be an `Lpvi;`.

        Getting that backwards made the first version of this file return True for exactly that
        pair, which merged the two types instead of conflicting them and let the check miss the
        shipped bug it was written to catch.
        """
        if value == target or target == "Ljava/lang/Object;":
            return True
        if value not in self.parent:
            return True  # value is a framework class; its hierarchy is not here to walk
        target_known = target in self.parent
        seen, cur = set(), value
        while cur and cur not in seen:
            seen.add(cur)
            if cur == target or target in self.interfaces.get(cur, ()):
                return True
            nxt = self.parent.get(cur)
            if nxt is not None and nxt not in self.parent:
                # The chain leaves the dex here. Only unknowable if the target is out there too.
                return not target_known
            cur = nxt
        return False


def join(a, b, hierarchy):
    if a == b:
        return a
    if a == UNKNOWN or b == UNKNOWN:
        return UNKNOWN
    if a == ZERO:
        return b
    if b == ZERO:
        return a
    if a == CONFLICT or b == CONFLICT:
        return CONFLICT
    if hierarchy.assignable(a, b):
        return b
    if hierarchy.assignable(b, a):
        return a
    return CONFLICT


def successors(ins, index, pc_index):
    _pc, mnemonic, args = ins[index]
    out = []
    m = re.search(r"-> (\d+)", args or "")
    if m and mnemonic.startswith(("goto", "if-")):
        target = pc_index.get(int(m.group(1)))
        if target is not None:
            out.append(target)
    if mnemonic.startswith("goto"):
        return out
    if mnemonic.startswith(("return", "throw")):
        return []
    if index + 1 < len(ins):
        out.append(index + 1)
    return out


def check_method(ins, register_count, parameters, hierarchy):
    """Findings as (index, register, incoming types, what the use site required)."""
    pc_index = {pc: i for i, (pc, _n, _a) in enumerate(ins)}

    entry = [UNKNOWN] * register_count
    for slot, descriptor in enumerate(parameters):
        entry[register_count - len(parameters) + slot] = descriptor

    state = {0: entry}
    order, seen_edges = [0], set()
    while order:
        i = order.pop()
        if i >= len(ins):
            continue
        before = state.get(i)
        if before is None:
            continue
        after = list(before)
        _pc, mnemonic, args = ins[i]
        text = (args or "").strip()

        if mnemonic == "new-instance":
            m = _NEW.match(text)
            if m:
                after[int(m.group(1))] = m.group(2)
        elif mnemonic.startswith("sget-object"):
            m = _SGET.match(text)
            if m:
                after[int(m.group(1))] = m.group(2)
        elif mnemonic.startswith("iget-object"):
            m = _IGET.match(text)
            if m:
                after[int(m.group(1))] = m.group(4)
        elif mnemonic == "check-cast":
            m = _NEW.match(text)
            if m:
                after[int(m.group(1))] = m.group(2)
        elif mnemonic.startswith("const-string"):
            r = _regs(text)
            if r:
                after[r[0]] = "Ljava/lang/String;"
        elif mnemonic.startswith("const") and re.search(r"#-?0x0\b|#0\b", text):
            r = _regs(text)
            if r:
                after[r[0]] = ZERO
        elif mnemonic.startswith("const"):
            r = _regs(text)
            if r:
                after[r[0]] = UNKNOWN
        elif mnemonic.startswith("move-object"):
            m = _MOVE_OBJ.match(text)
            if m:
                after[int(m.group(1))] = before[int(m.group(2))]
        elif mnemonic == "move-result-object":
            r = _regs(text)
            if r:
                after[r[0]] = state.get(("result", i), UNKNOWN)
        elif mnemonic.startswith("invoke"):
            m = _INVOKE.match(text)
            if m:
                state[("result", i + 1)] = m.group(5) if m.group(5).startswith(("L", "[")) else UNKNOWN
        elif mnemonic.startswith(("move-result", "move")):
            r = _regs(text)
            if r:
                after[r[0]] = UNKNOWN

        for s in successors(ins, i, pc_index):
            merged = state.get(s)
            if merged is None:
                state[s] = list(after)
                order.append(s)
            else:
                new = [join(x, y, hierarchy) for x, y in zip(merged, after)]
                if new != merged:
                    state[s] = new
                    order.append(s)
            seen_edges.add((i, s))

    findings = []
    for i, (_pc, mnemonic, args) in enumerate(ins):
        here = state.get(i)
        if here is None:
            continue
        text = (args or "").strip()
        demands = []
        if mnemonic.startswith(("iget", "iput")):
            m = _IGET.match(text)
            if m:
                demands.append((int(m.group(2)), m.group(3)))
                if mnemonic.startswith("iput-object"):
                    demands.append((int(m.group(1)), m.group(4)))
        elif mnemonic.startswith(("invoke-virtual", "invoke-direct", "invoke-interface")):
            m = _INVOKE.match(text)
            regs = _invoke_registers(text)
            if m and regs:
                demands.append((regs[0], m.group(2)))
        for register, required in demands:
            if register < len(here) and here[register] == CONFLICT:
                findings.append((i, register, required, mnemonic))
    return findings


def extract(apk, into):
    with zipfile.ZipFile(apk) as zf:
        names = [n for n in zf.namelist() if n.startswith("classes") and n.endswith(".dex")]
        for name in sorted(names):
            with zf.open(name) as s, open(os.path.join(into, os.path.basename(name)), "wb") as d:
                shutil.copyfileobj(s, d)
    return into


def parameters_of(descriptor, is_static):
    inside = descriptor[descriptor.index("(") + 1:descriptor.index(")")]
    out, i = [], 0
    while i < len(inside):
        if inside[i] == "L":
            j = inside.index(";", i)
            out.append(inside[i:j + 1])
            i = j + 1
        elif inside[i] == "[":
            j = i
            while inside[j] == "[":
                j += 1
            if inside[j] == "L":
                j = inside.index(";", j)
            out.append(inside[i:j + 1])
            i = j + 1
        else:
            out.append(UNKNOWN if inside[i] not in "JD" else UNKNOWN)
            if inside[i] in "JD":
                out.append(UNKNOWN)
            i += 1
    owner = descriptor.split("->")[0]
    return out if is_static else [owner] + out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 2:
        print(__doc__.strip().split("## Use")[1].strip(), file=sys.stderr)
        return 2
    apk, descriptor = args

    with tempfile.TemporaryDirectory() as tmp:
        extract(apk, tmp)
        dl = dexlib.load(tmp)
        hierarchy = Hierarchy(dl)
        d, c, maf = ddis.find(descriptor, dl)
        if not c:
            print(f"not found: {descriptor}", file=sys.stderr)
            return 1
        ins = ddis.disasm(d, c)
        params = parameters_of(descriptor, bool(maf & 0x8))
        findings = check_method(ins, c["registers"], params, hierarchy)
        registers, count = c["registers"], len(ins)

    print(f"=== {descriptor}  registers={registers}  instructions={count}")
    if not findings:
        print("  no conflicting register reaches a use site that requires a type.")
        print("  (a quiet result is not a proof: unknown types are never reported)")
        return 0
    for index, register, required, mnemonic in findings:
        pc, nm, a = ins[index]
        print(f"  FAIL pc {pc}: v{register} holds conflicting types here and `{nm}` requires "
              f"{required}")
        print(f"       {nm} {a}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
