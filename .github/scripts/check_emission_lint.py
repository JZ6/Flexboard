#!/usr/bin/env python3
"""Structural lint over the smali text the patches emit.

The emission sites hand Morphe raw instruction text at patch time. Morphe's own parser then
turns it into instructions — and it is *inside that parser* that a structurally broken block
explodes, on a phone, two thousand log lines from its author. The known failure shapes this
lane catches:

  R1  trailing label   — a label definition on the last line of a block. Morphe treats a label
                         with no following instruction as external and indexes an empty array:
                         `length=0; index=0` on the device (the 751b0d0 crash).
  R2  dangling label   — a branch targets a name the block neither defines nor declares as an
                         ExternalLabel. Morphe's parser dies the same way.
  R3  duplicate label  — two `:name` definitions in one block; the second silently re-points.
  R4  const width      — a literal that overflows its opcode (`const/4` is 4-bit signed,
                         `const/16` 16-bit). Interpolated values are skipped here; the patch
                         runtime owns those with its own check(...)s.
  R5  empty block      — a payload with no real instructions inserts nothing while looking
                         like it does.

Interpolated label names (`:$NOT_RIGHTWARD_LABEL`) are expanded from the const vals declared
anywhere in the patch sources, the same values the Kotlin compiler would splice. Values that
cannot be resolved statically are ignored by name-checks (R1/R2/R3) rather than guessed.

    check_emission_lint.py   -> silent, or exits 1 listing every breakpoint
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
PATCHES = ROOT / "patches/src/main/kotlin"

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")
RAW_STRING = re.compile(r'"""(.*?)"""', re.S)
KOTLIN_CONST = re.compile(r'const\s+val\s+(\w+)\s*=\s*"([^"]+)"')
CALL = re.compile(r"\b(addInstructions(?:WithLabels)?)\s*\(")
EXTERNAL_LABEL = re.compile(r"ExternalLabel\(\s*(\w+)")
DOLLAR_REF = re.compile(r"\$\{?(\w+)\}?")

LABEL_DEF = re.compile(r"^\s*:(\w+)\s*$")
LABEL_REF = re.compile(r":(\w+)\b")
BRANCH = re.compile(r"^\s*(if-\w+|goto(?:/\w+)?)\b")
CONST_INSTR = re.compile(r"^\s*(const/4|const/16)\s+v[\w${}]+\s*,\s*(#?-?0x[0-9a-fA-F]+|#?-?\d+)")

WIDTHS = {"const/4": (-8, 7), "const/16": (-32768, 32767)}


def collect_calls(text):
    """Yield (line_no, variant, arg_string) for each addInstructions* call site."""
    for m in CALL.finditer(text):
        i = m.end()
        depth = 1
        start_line = text.count("\n", 0, m.start()) + 1
        while depth and i < len(text):
            c = text[i]
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
            elif text.startswith('"""', i):
                end = text.find('"""', i + 3)
                if end < 0:
                    break
                i = end + 2
            i += 1
        yield start_line, m.group(1), text[m.end():i - 1]


def expand(token, consts):
    return DOLLAR_REF.sub(lambda m: consts.get(m.group(1), m.group(0)), token)


def lint_block(problems, name, line_no, payload, externals, consts, labeled):
    lines = [ln for ln in payload.split("\n")]
    useful = [ln for ln in lines if ln.strip() and not ln.strip().startswith("#")]
    if not any(LABEL_DEF.match(ln) or re.match(r"^\s*[a-z/{}-]+", ln) for ln in useful):
        problems.append(f"  {name}:{line_no} emits an empty block (R5)")
        return

    # R1: the trailing-label crash. The definition has to be a label, not a jump.
    last = useful[-1].strip()
    if last.startswith(":") and labeled:
        problems.append(
            f"  {name}:{line_no} ends on a label ({last!r}) — Morphe resolves a terminal "
            f"label as external and dies at `length=0; index=0` (R1)"
        )

    if not labeled:
        if ":" in payload:
            problems.append(
                f"  {name}:{line_no} carries ':' in an addInstructions payload — labels "
                f"need addInstructionsWithLabels or they are silently dead text (R2)"
            )
    else:
        defs, refs = set(), set()
        for ln in useful:
            d = LABEL_DEF.match(ln)
            if d:
                label = expand(d.group(1), consts)
                if label in defs:
                    problems.append(
                        f"  {name}:{line_no} defines label :{label} twice in one block (R3)")
                defs.add(label)
            if BRANCH.match(ln):
                for r in LABEL_REF.findall(ln):
                    refs.add(expand(r, consts))
        for r in sorted(refs - defs - externals):
            problems.append(
                f"  {name}:{line_no} branches to :{r}, defined neither in the block nor in "
                f"its ExternalLabel declarations (R2)"
            )

    for ln in useful:
        cm = CONST_INSTR.match(ln)
        if cm:
            op, raw = cm.group(1), cm.group(2).lstrip("#")
            value = int(raw, 16) if raw.lower().startswith(("0x", "-0x")) else int(raw)
            if raw.startswith("-0x"):
                value = -int(raw[1:], 16)
            lo, hi = WIDTHS[op]
            if not (lo <= value <= hi):
                problems.append(
                    f"  {name}:{line_no} `{op}` carries literal {value} outside "
                    f"{lo}..{hi} (R4)"
                )


def main():
    consts = {}
    texts = {}
    for path in PATCHES.rglob("*.kt"):
        text = LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", path.read_text()))
        texts[path] = text
        consts.update(KOTLIN_CONST.findall(text))

    problems = []
    for path, text in sorted(texts.items(), key=lambda kv: kv[0].name):
        for line_no, variant, args in collect_calls(text):
            m = RAW_STRING.search(args)
            if not m:
                continue
            externals = set()
            for tok in EXTERNAL_LABEL.findall(args):
                externals.add(consts.get(tok, tok))
            lint_block(problems, path.name, line_no, m.group(1), externals, consts,
                       labeled=(variant == "addInstructionsWithLabels"))

    if problems:
        print("Smali emission lint:")
        print("\n".join(problems))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
