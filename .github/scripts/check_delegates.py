#!/usr/bin/env python3
"""The patcher-side delegates forward their arguments unchanged.

Eleven resolution helpers take a `ClassLookup` so they can be tested without a patcher, and each has
a one-line overload on `BytecodePatchContext` that supplies the context's own lookup. Those eleven
lines are the only part of the arrangement no test covers, and they cannot be covered: constructing
a `BytecodePatchContext` needs a `PatcherConfig` and an APK, which is the whole reason the helpers
were refactored away from it.

So they get checked structurally instead. What can go wrong in a one-line delegate is argument
order, and the damage is quiet:

    checkAssignable(classLookup, target, type, what)   // type and target swapped

still compiles, still type-checks -- both are `String` -- and produces a confident, precise, wrong
failure telling a patch author their `Context` is not a `Context`. Every test in the suite would
still pass, because they all call the pure function directly and never go through the delegate.

Checked here:

  1. every delegate calls the function it is named after, not a neighbour;
  2. it passes `classLookup` first;
  3. the remaining arguments are exactly its own parameters, in its own order;
  4. its parameter list matches the pure function's, minus the lookup, in name and type;
  5. no delegate silently stops being a delegate by acquiring a body.

An overload that deliberately transforms its arguments is not a delegate and is not checked, but it
has to say so in ALLOWED_TRANSFORMS -- so a new one is a deliberate act rather than a silent escape.
"""

import re
import sys
from pathlib import Path

SOURCES = [
    Path("patches/src/main/kotlin/dev/jz6/flexboard/patches/shared/Types.kt"),
    Path("patches/src/main/kotlin/dev/jz6/flexboard/patches/shared/Resolve.kt"),
]

# Context overloads that legitimately reshape their arguments rather than forwarding them.
ALLOWED_TRANSFORMS = {
    # Takes a register carrying its own proven type and unpacks it into the string form, adding the
    # register number to the description. Forwarding unchanged would defeat the point of it.
    ("checkAssignable", "register, target, what"),
}

PURE = re.compile(
    r"internal fun (\w+)\(\s*lookup: ClassLookup\s*,\s*(.*?)\s*\)\s*[:{=]",
    re.S,
)
DELEGATE_START = "internal fun BytecodePatchContext."


def delegates(text):
    """`(name, params, body)` for each context overload with an expression body.

    Scanned rather than matched with one regular expression, because a parameter list can contain
    parentheses -- `methodsMatching(predicate: (Method) -> Boolean)` -- and a non-greedy `\(.*?\)`
    stops at the wrong one. The first version of this did exactly that, ran off the end of a
    block-bodied function, and reported that `methodsMatching` delegates to `checkMethodExists`.
    Wrong, and worse, wrong in a way that looked like a real finding.
    """
    found = []
    at = text.find(DELEGATE_START)
    while at != -1:
        cursor = at + len(DELEGATE_START)
        name = ""
        while cursor < len(text) and (text[cursor].isalnum() or text[cursor] == "_"):
            name += text[cursor]
            cursor += 1

        if cursor >= len(text) or text[cursor] != "(":
            at = text.find(DELEGATE_START, cursor)
            continue

        depth, start = 0, cursor
        while cursor < len(text):
            if text[cursor] == "(":
                depth += 1
            elif text[cursor] == ")":
                depth -= 1
                if depth == 0:
                    break
            cursor += 1
        params = text[start + 1:cursor]
        cursor += 1

        # Past an optional return type to whatever introduces the body.
        tail = text[cursor:cursor + 200]
        equals, brace = tail.find("="), tail.find("{")
        if equals == -1 or (brace != -1 and brace < equals):
            # A block body. Not a delegate, and not this script's business.
            at = text.find(DELEGATE_START, cursor)
            continue

        body_at = cursor + equals + 1
        stop = len(text)
        for marker in ("\n\n", "\n/**", "\ninternal ", "\nprivate "):
            here = text.find(marker, body_at)
            if here != -1:
                stop = min(stop, here)
        found.append((name, params, text[body_at:stop]))
        at = text.find(DELEGATE_START, stop)
    return found


def parameters(text):
    """`[(name, type)]` from a Kotlin parameter list, tolerating defaults and trailing commas."""
    out, depth, current = [], 0, ""
    for ch in text:
        if ch in "<(":
            depth += 1
        elif ch in ">)":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(current)
            current = ""
        else:
            current += ch
    out.append(current)
    found = []
    for raw in out:
        raw = raw.strip()
        if not raw:
            continue
        name, _, rest = raw.partition(":")
        found.append((name.strip(), rest.split("=")[0].strip()))
    return found


def arguments(text):
    """Argument names from a call's argument list, at depth zero."""
    inner = text[text.index("(") + 1:text.rindex(")")]
    out, depth, current = [], 0, ""
    for ch in inner:
        if ch in "<({":
            depth += 1
        elif ch in ">)}":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(current)
            current = ""
        else:
            current += ch
    out.append(current)
    return [a.strip() for a in out if a.strip()]


def check(path):
    text = path.read_text()
    pure = {name: parameters(params) for name, params in PURE.findall(text)}
    problems = []
    seen = 0

    for name, params, body in delegates(text):
        body = " ".join(body.split())
        own = parameters(params)
        signature = ", ".join(n for n, _ in own)

        if (name, signature) in ALLOWED_TRANSFORMS:
            continue
        if "classLookup" not in body:
            # Not a lookup delegate at all. Left alone deliberately -- but it must be declared, so
            # that a delegate losing its `classLookup` cannot pass by looking like something else.
            problems.append(
                f"{path.name}: {name}({signature}) is a BytecodePatchContext overload that neither "
                f"passes classLookup nor appears in ALLOWED_TRANSFORMS")
            continue

        seen += 1
        called = re.match(r"(\w+)\s*\(", body)
        if not called:
            problems.append(f"{path.name}: {name} has a body rather than a single call: {body[:60]}")
            continue
        if called.group(1) != name:
            problems.append(
                f"{path.name}: {name} delegates to {called.group(1)}, which is not the function it "
                f"is named after")
            continue

        args = arguments(body)
        if args[:1] != ["classLookup"]:
            problems.append(f"{path.name}: {name} passes {args[:1]} first, expected classLookup")
            continue

        expected = [n for n, _ in own]
        if args[1:] != expected:
            problems.append(
                f"{path.name}: {name} forwards ({', '.join(args[1:])}) but its parameters are "
                f"({', '.join(expected)}) — order or names differ, which compiles and misbehaves")
            continue

        target = pure.get(name)
        if target is None:
            problems.append(f"{path.name}: {name} has no ClassLookup-taking counterpart to delegate to")
        elif target != own:
            problems.append(
                f"{path.name}: {name}'s parameters {own} do not match the pure function's {target}")

    return seen, problems


def main():
    total, problems = 0, []
    for path in SOURCES:
        if not path.exists():
            problems.append(f"{path} is missing")
            continue
        seen, found = check(path)
        total += seen
        problems += found

    # A parser that stops matching reports nothing and looks like success, which is the failure this
    # whole file exists to prevent elsewhere. There are eleven delegates; require most of them.
    minimum = 9
    if total < minimum:
        problems.append(
            f"only {total} delegates were recognised, fewer than the {minimum} expected — the "
            f"patterns in this script have probably stopped matching, which would make it pass "
            f"by checking nothing")

    for problem in problems:
        print(f"  {problem}")
    print(f"  {total} delegating overloads checked, {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
