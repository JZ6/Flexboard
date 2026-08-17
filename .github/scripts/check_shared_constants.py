#!/usr/bin/env python3
"""Do the patch and the extension still agree about the preference contract?

The preference keys and their defaults exist twice: once in the Kotlin patches, which read them out
of Gboard's store at runtime, and once in `FlexboardSettingsActivity`, which writes them. They cannot
be shared — the Activity is compiled into the extension DEX, a separate Gradle module with no
dependency on the patches — so both sides carry a comment pointing at the other.

A comment is not a check. Change one side alone and everything still compiles, the settings screen
still renders, the slider still moves, and the value it writes is simply read back under a different
key or compared against a different default. Nothing fails until someone notices a setting doing
nothing on a phone.

    check_shared_constants.py        -> silent, or exits 1 listing every disagreement
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
PATCHES = ROOT / "patches/src/main/kotlin"
ACTIVITY = ROOT / (
    "extensions/extension/src/main/java/dev/jz6/flexboard/extension/settings"
    "/FlexboardSettingsActivity.java"
)

# (Kotlin name, Java name). The names differ where each side reads more naturally on its own terms;
# what has to match is the value.
PAIRS = [
    ("STEP_SCALE_KEY", "KEY_STEP_SCALE"),
    ("MAX_WORDS_KEY", "KEY_MAX_WORDS"),
    ("HOLD_DELAY_KEY", "KEY_HOLD_DELAY"),
    ("STEP_SCALE_DEFAULT", "STEP_SCALE_DEFAULT"),
    ("MAX_WORDS_DEFAULT", "MAX_WORDS_DEFAULT"),
    # The slider's top position, and the "no limit" sentinel the clamp tests against. Split from the
    # default when that moved to 1 — sharing them would have put the sentinel at 1 and disabled the
    # cap at every setting.
    ("MAX_WORDS_NO_LIMIT", "MAX_WORDS_MAX"),
    ("HOLD_DELAY_DEFAULT", "HOLD_DELAY_DEFAULT"),
]

KOTLIN_CONST = re.compile(r'internal const val (\w+) = (?:"([^"]*)"|(\d+))')
JAVA_CONST = re.compile(r'private static final (?:String|int) (\w+) = (?:"([^"]*)"|(\d+));')


def _collect(pattern, text):
    return {
        m.group(1): m.group(2) if m.group(2) is not None else m.group(3)
        for m in pattern.finditer(text)
    }


def main():
    kotlin = {}
    for path in PATCHES.rglob("*.kt"):
        kotlin.update(_collect(KOTLIN_CONST, path.read_text()))
    java = _collect(JAVA_CONST, ACTIVITY.read_text())

    problems = []
    for kt_name, java_name in PAIRS:
        kt_value, java_value = kotlin.get(kt_name), java.get(java_name)
        if kt_value is None:
            problems.append(f"  {kt_name} is not declared in any patch")
        elif java_value is None:
            problems.append(f"  {java_name} is not declared in FlexboardSettingsActivity")
        elif kt_value != java_value:
            problems.append(
                f"  {kt_name} = {kt_value!r} but {java_name} = {java_value!r} — "
                f"the patch and the settings screen disagree"
            )

    if problems:
        print("::error::The patches and the extension disagree about the preference contract:",
              file=sys.stderr)
        print("\n".join(problems), file=sys.stderr)
        return 1

    print(f"Patch and extension agree on all {len(PAIRS)} shared constants.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
