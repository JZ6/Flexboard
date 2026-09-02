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
    """Yield (line_no, variant, arg_string, end_offset) for each addInstructions* call site."""
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
        yield start_line, m.group(1), text[m.end():i - 1], m.start()


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
        # A colon alone is not a label: every field descriptor carries one, and rejecting
        # `iget-object v0, p0, Lqhy;->a:Landroid/content/Context;` pushed authors toward
        # addInstructionsWithLabels — or toward composing the payload elsewhere, out of this
        # linter's reach. Only a definition or a branch that references one counts.
        stray = [
            ln.strip() for ln in useful
            if LABEL_DEF.match(ln) or (BRANCH.match(ln) and LABEL_REF.search(ln))
        ]
        if stray:
            problems.append(
                f"  {name}:{line_no} carries label {stray[0]!r} in an addInstructions payload — "
                f"labels need addInstructionsWithLabels or they are silently dead text (R2)"
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

    lint_widths(problems, name, line_no, payload)


def lint_widths(problems, name, line_no, payload):
    """R4 on its own, so fragments composed into a payload get it too.

    Operand width is a property of the instruction alone — unlike the label rules it does not
    depend on where the fragment ends up — so it is the one check worth running on a block this
    linter can see but cannot place.
    """
    for ln in payload.split("\n"):
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


PLAIN_STRING = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
IDENTIFIER_ARG = re.compile(r",\s*([A-Za-z_]\w*)\s*$", re.S)


def initializer_of(text, name, before):
    """Source of `val <name> = ...`, the last one declared before offset [before].

    Parentheses and braces are balanced from the `=`, then the capture runs to the end of that
    line so a trailing `.trimIndent()` comes along. Good enough for the one shape this codebase
    builds payloads with, and anything it cannot parse is reported rather than skipped.
    """
    best = None
    for m in re.finditer(rf"\bval\s+{re.escape(name)}\s*=", text):
        if m.end() < before:
            best = m
    if not best:
        return None
    i, depth = best.end(), 0
    while i < len(text):
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "\n" and depth <= 0:
            break
        i += 1
    return text[best.end():text.find("\n", i) if text.find("\n", i) > 0 else len(text)]


def terminator_is_instruction(initializer):
    """Whether a composed payload provably ends on something other than a label.

    Morphe reads a block's terminal label as external and dies indexing an empty array, so a
    composition whose last literal is a separator — or a fragment that ends on a label — is the
    751b0d0 crash waiting to be reassembled. The last string literal in source order is the
    composition's tail; require its final non-blank line to be an instruction.
    """
    literals = [m.group(1) for m in re.finditer(r'"""(.*?)"""', initializer, re.S)]
    literals += [m.group(1) for m in PLAIN_STRING.finditer(re.sub(r'""".*?"""', "", initializer, flags=re.S))]
    if not literals:
        return False
    tail = literals[-1].encode().decode("unicode_escape")
    useful = [ln.strip() for ln in tail.split("\n") if ln.strip()]
    return bool(useful) and not useful[-1].startswith(":")


def main():
    consts = {}
    texts = {}
    for path in PATCHES.rglob("*.kt"):
        # Block comments collapse to their own newlines rather than vanishing, so every line
        # number this linter reports still refers to the real file. Reporting a finding against
        # a line that moved is worse than reporting no line at all.
        text = LINE_COMMENT.sub("", BLOCK_COMMENT.sub(
            lambda m: "\n" * m.group(0).count("\n"), path.read_text()))
        texts[path] = text
        consts.update(KOTLIN_CONST.findall(text))

    problems = []
    for path, text in sorted(texts.items(), key=lambda kv: kv[0].name):
        for line_no, variant, args, end in collect_calls(text):
            labeled = variant == "addInstructionsWithLabels"
            externals = {consts.get(t, t) for t in EXTERNAL_LABEL.findall(args)}

            raw = RAW_STRING.search(args)
            if raw:
                lint_block(problems, path.name, line_no, raw.group(1), externals, consts, labeled)
                continue

            # A payload handed over as an ordinary string is still a payload. These used to fall
            # through the raw-string search and go unlinted entirely.
            plain = PLAIN_STRING.search(args)
            if plain:
                lint_block(problems, path.name, line_no, plain.group(1), externals, consts, labeled)
                continue

            # Composed elsewhere and passed by name. The fragments cannot be assembled here, but
            # the property that actually crashes Morphe — how the composition ends — can be.
            named = IDENTIFIER_ARG.search(args)
            initializer = initializer_of(text, named.group(1), end) if named else None
            if initializer is None:
                problems.append(
                    f"  {path.name}:{line_no} passes a payload this linter cannot analyse (R7); "
                    f"an unanalysed emission is an unguarded one"
                )
                continue
            for fragment in re.finditer(r'"""(.*?)"""', initializer, re.S):
                lint_widths(problems, path.name, line_no, fragment.group(1))
            if labeled and not terminator_is_instruction(initializer):
                problems.append(
                    f"  {path.name}:{line_no} builds `{named.group(1)}` with no demonstrable "
                    f"instruction at the end (R7) — Morphe resolves a terminal label as external "
                    f"and dies at `length=0; index=0`"
                )

    if problems:
        print("Smali emission lint:")
        print("\n".join(problems))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
