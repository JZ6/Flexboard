#!/usr/bin/env python3
"""Do the patch and the extension still agree about the preference contract?

Some values exist twice: once in the Kotlin patches and once in the extension's Java. Preference keys
and their defaults, because the patches read Gboard's store at runtime and `FlexboardSettingsActivity`
writes it; and the action ordinals the patches hand `TextAction`, which maps them to framework
context-menu ids. They cannot be shared — the extension is a separate Gradle module with no
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
    # The toolbar count has no shared *default*: the patch reads the preference with whatever Gboard
    # itself computed as the fallback, so an unset value is stock behaviour rather than a number
    # either side had to agree on. The bounds are shared, because the patch rejects anything outside
    # them and the slider is what has to stay inside them.
    ("TOOLBAR_COUNT_KEY", "KEY_TOOLBAR_COUNT"),
    ("TOOLBAR_COUNT_UNFOLDED_KEY", "KEY_TOOLBAR_COUNT_UNFOLDED"),
    ("TOOLBAR_COUNT_MIN", "TOOLBAR_COUNT_MIN"),
    ("TOOLBAR_COUNT_MAX", "TOOLBAR_COUNT_MAX"),
    # The ordinals the patch hands the extension's constructor. The extension maps them to
    # android.R.id.* so the framework constants stay symbolic in the one language that can name
    # them -- which means the number crossing the boundary is meaningless on its own, and a drift
    # would silently wire Copy to Paste rather than failing.
    ("TEXT_ACTION_SELECT_ALL", "SELECT_ALL"),
    ("TEXT_ACTION_COPY", "COPY"),
    ("TEXT_ACTION_PASTE", "PASTE"),
    # How many hotkey slots exist, and the drawable each one wears. The count decides how many
    # blocks the patch emits and how many rows the screen draws; the icons are what the patch puts
    # on the button and what the screen previews beside the field that fills it. A drifted icon
    # would show the user one shape and put another on the toolbar -- the single thing that makes
    # six otherwise identical buttons tellable apart, quietly wrong.
    ("HOTKEY_SLOT_COUNT", "HOTKEY_SLOT_COUNT"),
    ("HOTKEY_ICON_1", "HOTKEY_ICON_1"),
    ("HOTKEY_ICON_2", "HOTKEY_ICON_2"),
    ("HOTKEY_ICON_3", "HOTKEY_ICON_3"),
    ("HOTKEY_ICON_4", "HOTKEY_ICON_4"),
    ("HOTKEY_ICON_5", "HOTKEY_ICON_5"),
    ("HOTKEY_ICON_6", "HOTKEY_ICON_6"),
    ("HOTKEY_ICON_7", "HOTKEY_ICON_7"),
    ("HOTKEY_ICON_8", "HOTKEY_ICON_8"),
    ("HOTKEY_ICON_9", "HOTKEY_ICON_9"),
    ("HOTKEY_ICON_10", "HOTKEY_ICON_10"),
    ("HOTKEY_ICON_11", "HOTKEY_ICON_11"),
    ("HOTKEY_ICON_12", "HOTKEY_ICON_12"),
]

# Hex is accepted because resource ids are written that way on both sides -- and on the Kotlin side
# they are *strings*, since a patch emits them into smali as text rather than using them as numbers.
NUMBER = r"0[xX][0-9a-fA-F]+|\d+"
KOTLIN_CONST = re.compile(rf'internal const val (\w+) = (?:"([^"]*)"|({NUMBER}))')
JAVA_CONST = re.compile(rf'private static final (?:String|int) (\w+) = (?:"([^"]*)"|({NUMBER}));')


def _collect(pattern, text):
    return {
        m.group(1): m.group(2) if m.group(2) is not None else m.group(3)
        for m in pattern.finditer(text)
    }


def _normalised(value):
    """Two spellings of one number compare equal; everything else compares as written.

    `0x7f080239` on one side and `2130903609` on the other are the same resource id, and a check
    that called them different would be noise. Preference keys and other strings fall through
    unchanged, because `int` refuses them.
    """
    try:
        return str(int(value, 0))
    except (TypeError, ValueError):
        return value


EXTENSION_ROOT = ROOT / "extensions/extension/src/main/java"

# A patch reaches into the extension by emitting a descriptor as a *string*. Renaming or moving the
# Java class leaves that string pointing at nothing: the Kotlin compiles, the smali assembles, and
# the button does nothing on a phone. Same class of silent break as the preference keys above, so it
# is checked the same way.
EXTENSION_DESCRIPTOR = re.compile(r'const val (\w+) =\s*\n?\s*"(Ldev/jz6/flexboard/extension/[\w/$]+;)"')


# Descriptors are assembled from constants, and often from constants built out of other constants
# -- the smali reads `invoke-static { p0 }, $SET_SERVICE`, where SET_SERVICE is itself
# "$EXTENSION_CLASS->setService(...)V". Matching the use site alone sees `$SET_SERVICE` and finds
# no member, which silently checks nothing. So the constants are expanded to a fixpoint first.
CONST_STRING = re.compile(r'const val (\w+)\s*=\s*\n?\s*"((?:[^"\\]|\\.)*)"')

EXTENSION_TYPE = r"Ldev/jz6/flexboard/extension/[\w/$]+;"

# Every emitted invocation of an extension member, with the opcode that reaches it. The opcode
# matters: invoke-static against an instance method resolves to nothing at run time.
EMITTED_CALL = re.compile(
    r"invoke-(static|virtual|direct|interface)\s*\{[^}]*\}\s*,\s*"
    rf"({EXTENSION_TYPE})->(<init>|\w+)\(([^)]*)\)(\[*(?:L[\w/$;]+;|[VZBSCIJFD]))"
)

# Calls emitted by a shared helper rather than written out at the use site.
#
# `shared/AppStart.kt` emits `invoke-static { p0 }, $descriptor` for whatever descriptor it is
# handed, so three patches now name an extension member without any `invoke-` beside it. The
# pattern above cannot see those, and the "silently stopped checking anything" guard below is what
# noticed -- the check would otherwise have gone quiet on three of its five call sites.
#
# Each entry maps a helper to the opcode it emits, which is the part that has to be known rather
# than inferred: a helper hardcoding invoke-static against a member someone later made non-static
# is exactly the failure this file exists to catch.
HELPER_CALLS = {"callAtAppStart": "static"}

HELPER_CALL = re.compile(rf"\b({'|'.join(HELPER_CALLS)})\(\s*([A-Z_][A-Z0-9_]*)\s*\)")

MEMBER = re.compile(
    rf"^({EXTENSION_TYPE})->(<init>|\w+)\(([^)]*)\)(\[*(?:L[\w/$;]+;|[VZBSCIJFD]))$"
)


def _expand(text):
    """Substitute Kotlin string constants into the source, to a fixpoint."""
    constants = dict(CONST_STRING.findall(text))
    for _ in range(5):
        changed = False
        for key in list(constants):
            value = constants[key]
            for other, replacement in constants.items():
                if f"${other}" in value:
                    value = value.replace(f"${other}", replacement)
                    changed = True
            constants[key] = value
        if not changed:
            break
    for key, value in constants.items():
        text = text.replace(f"${key}", value)
    return text

PRIMITIVES = {"V": "void", "Z": "boolean", "B": "byte", "S": "short", "C": "char",
              "I": "int", "J": "long", "F": "float", "D": "double"}

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")


def _java_types(descriptor):
    """Turn a JVM parameter descriptor string into simple Java type names, in order."""
    out, i = [], 0
    while i < len(descriptor):
        suffix = ""
        while descriptor[i] == "[":
            suffix += "[]"
            i += 1
        if descriptor[i] == "L":
            end = descriptor.index(";", i)
            out.append(descriptor[i + 1:end].split("/")[-1].replace("$", ".") + suffix)
            i = end + 1
        else:
            out.append(PRIMITIVES[descriptor[i]] + suffix)
            i += 1
    return out


def _declares(body, class_name, member, params, returns, needs_static):
    """Is `member` declared on this class with a matching signature?

    Deliberately strict about the things that make a reference resolve or not: the parameter
    types, the return type, and staticness. A name match alone is what the previous version of
    this check did, and a Javadoc sentence satisfied it.
    """
    if member == "<init>":
        pattern = rf"(?:^|\s)((?:public|protected|private)\s+)?{class_name}\s*\(([^)]*)\)\s*\{{"
    else:
        pattern = rf"(?:^|\s)((?:public|protected|private|static|final|synchronized|\s)*)" \
                  rf"{re.escape(returns)}\s+{re.escape(member)}\s*\(([^)]*)\)"
    for match in re.finditer(pattern, body):
        modifiers, arguments = match.group(1) or "", match.group(2).strip()
        declared = []
        if arguments:
            for argument in arguments.split(","):
                tokens = argument.replace("final ", "").strip().split()
                if len(tokens) >= 2:
                    declared.append(tokens[-2].split(".")[-1])
        if declared != params:
            continue
        if needs_static and "static" not in modifiers:
            continue
        return True
    return False


def _check_extension_references(problems):
    for path in PATCHES.rglob("*.kt"):
        text = _expand(path.read_text())

        sources = {}
        for descriptor in sorted(set(re.findall(EXTENSION_TYPE, text))):
            source = EXTENSION_ROOT / (descriptor[1:-1] + ".java")
            if not source.is_file():
                problems.append(
                    f"  {path.name} names the extension class {descriptor}, but "
                    f"{source.relative_to(ROOT)} does not exist"
                )
                continue
            # Comments are stripped first. The whole point of this check is that a member is
            # *declared*, and an unstripped file lets a sentence describing the member stand in
            # for the member.
            sources[descriptor] = (
                source, LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", source.read_text()))
            )

        # A helper's call site names the member through a constant, so resolve it back to the same
        # shape the pattern above produces. `text` is already expanded to a fixpoint, so the
        # declarations in it carry their final values.
        constants = dict(CONST_STRING.findall(text))
        emitted = list(EMITTED_CALL.findall(text))
        for helper, name in HELPER_CALL.findall(text):
            descriptor = constants.get(name)
            if descriptor is None:
                problems.append(
                    f"  {path.name} calls {helper}({name}), but {name} is not a string constant "
                    f"in that file, so what it emits cannot be checked"
                )
                continue
            match = MEMBER.match(descriptor)
            if match is None:
                problems.append(
                    f"  {path.name} calls {helper}({name}), whose value {descriptor!r} is not a "
                    f"complete extension member descriptor"
                )
                continue
            emitted.append((HELPER_CALLS[helper], *match.groups()))

        checked = 0
        for opcode, descriptor, member, parameters, returns in emitted:
            if descriptor not in sources:
                continue
            source, body = sources[descriptor]
            class_name = descriptor[1:-1].split("/")[-1]
            params = _java_types(parameters)
            checked += 1
            if not _declares(body, class_name, member, params,
                             _java_types(returns)[0], opcode == "static"):
                problems.append(
                    f"  {path.name} emits invoke-{opcode} {descriptor}->{member}"
                    f"({', '.join(params)}){returns}, which {source.name} does not declare "
                    f"with that signature"
                )

        # A guard that checks nothing is the failure this whole function exists to prevent, and
        # it has already happened once here: the descriptors are built from nested constants, so
        # a change in how they are assembled can leave the matcher finding zero members while the
        # script still reports success.
        if sources and not checked:
            problems.append(
                f"  {path.name} references an extension class but no emitted member could be "
                f"parsed from it — this check has silently stopped checking anything"
            )

        # A class handed to a (Ljava/lang/Runnable;)V setter has to actually be a Runnable;
        # nothing else in this pipeline would notice if it stopped being one.
        for descriptor, (source, body) in sources.items():
            constructed = re.search(rf"new-instance\s+\w+\s*,\s*{re.escape(descriptor)}", text)
            if constructed and "(Ljava/lang/Runnable;)V" in text:
                if not re.search(r"\bimplements\b[^{]*\bRunnable\b", body):
                    problems.append(
                        f"  {path.name} hands {descriptor} to a Runnable setter, but "
                        f"{source.name} does not declare `implements Runnable`"
                    )


def main():
    kotlin = {}
    for path in PATCHES.rglob("*.kt"):
        kotlin.update(_collect(KOTLIN_CONST, path.read_text()))
    problems = []

    # Every Java file in the extension, not just the settings screen. The screen was the only side
    # of the contract until the toolbar buttons arrived: their action ordinals are shared with
    # TextAction, which is not a settings class at all. Collecting one file silently reported those
    # as undeclared.
    java, declared_in = {}, {}
    for source in sorted(EXTENSION_ROOT.rglob("*.java")):
        for name, value in _collect(JAVA_CONST, source.read_text()).items():
            if name in java and java[name] != value:
                problems.append(
                    f"  {name} is declared twice in the extension with different values — "
                    f"{declared_in[name]} says {java[name]!r}, {source.name} says {value!r}"
                )
                continue
            java[name], declared_in[name] = value, source.name

    for kt_name, java_name in PAIRS:
        kt_value, java_value = kotlin.get(kt_name), java.get(java_name)
        if kt_value is None:
            problems.append(f"  {kt_name} is not declared in any patch")
        elif java_value is None:
            problems.append(f"  {java_name} is not declared anywhere in the extension")
        elif _normalised(kt_value) != _normalised(java_value):
            problems.append(
                f"  {kt_name} = {kt_value!r} but {java_name} = {java_value!r} in "
                f"{declared_in[java_name]} — the patch and the extension disagree"
            )

    _check_extension_references(problems)

    if problems:
        print("::error::The patches and the extension disagree about the preference contract:",
              file=sys.stderr)
        print("\n".join(problems), file=sys.stderr)
        return 1

    print(f"Patch and extension agree on all {len(PAIRS)} shared constants.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
