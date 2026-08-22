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

import os
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
PATCHES = ROOT / "patches/src/main/kotlin"
# The native settings screen. Rows in this XML are what persist the values the smali readers read,
# so the key/default/bounds literals here are one side of the same contract the PAIRS below
# covered when the screen was extension Java.
SETTINGS_XML = ROOT / "patches/src/main/resources/xml/flexboard_settings.xml"
# (Kotlin name, Java name). The names differ where each side reads more naturally on its own terms;
# what has to match is the value.
PAIRS = [
    ("STEP_SCALE_KEY", "KEY_STEP_SCALE"),
    ("STEP_SCALE_DEFAULT", "STEP_SCALE_DEFAULT"),
    ("HOTKEY_SLOTS", "SLOT_COUNT"),
    ("HOTKEY_COUNT_KEY", "PREF_COUNT"),
    ("HOTKEY_COUNT_DEFAULT", "COUNT_DEFAULT"),
    # The ordinals the patch hands the extension's constructor. The extension maps them to
    # android.R.id.* so the framework constants stay symbolic in the one language that can name
    # them -- which means the number crossing the boundary is meaningless on its own, and a drift
    # would silently wire Copy to Paste rather than failing.
    ("TEXT_ACTION_SELECT_ALL", "SELECT_ALL"),
    ("TEXT_ACTION_COPY", "COPY"),
    ("TEXT_ACTION_PASTE", "PASTE"),
]

# Multi-element contracts: Kotlin carries the canonical hex id list for the per-slot defaults,
# Java carries the same ids as int[] for array-indexed fallback. Same drift class as PAIRS but
# element-wise.
ARRAY_PAIRS = [
    ("HOTKEY_DEFAULT_ICONS", "DEFAULT_ICONS"),
]

# The slider contract between ScrubTuningPatch.kt and flexboard_settings.xml: the Kotlin name of
# the key a row stores under, then the Kotlin names of the values the row's attributes have to
# carry exactly. A change that moves one side — a key, a bound, a default — while leaving the
# other silently decouples the slider from the number the engine uses.
#
# ("<kotlin const of key>", {"<xml attribute>": "<kotlin const>"})
XML_ROWS = [
    ("MAX_WORDS_KEY", {
        "android:defaultValue": "MAX_WORDS_DEFAULT",
        "slider_min_value": "MAX_WORDS_MIN",
        "slider_max_value": "MAX_WORDS_NO_LIMIT",
    }),
    ("HOLD_DELAY_KEY", {
        "android:defaultValue": "HOLD_DELAY_DEFAULT",
        "slider_min_value": "HOLD_DELAY_MIN",
        "slider_max_value": "HOLD_DELAY_MAX",
    }),
    ("HOTKEY_COUNT_KEY", {
        "android:defaultValue": "HOTKEY_COUNT_DEFAULT",
        "slider_min_value": "HOTKEY_COUNT_MIN",
        "slider_max_value": "HOTKEY_SLOTS",
    }),
]

# Hex is accepted because resource ids are written that way on both sides -- and on the Kotlin side
# they are *strings*, since a patch emits them into smali as text rather than using them as numbers.
NUMBER = r"0[xX][0-9a-fA-F]+|\d+"
KOTLIN_CONST = re.compile(rf'internal const val (\w+) = (?:"([^"]*)"|({NUMBER}))')
JAVA_CONST = re.compile(rf'private static final (?:String|int) (\w+) = (?:"([^"]*)"|({NUMBER}));')

# Kotlin `internal val X = listOf("0x7f080239", "0x7f0806fc", …)`; Java
# `private static final int[] X = new int[] { 2131231289, … }`. Comments are stripped before these
# run, so per-element "// star" notes don't need to be part of the grammar (and trying to keep
# them made the regex backtrack forever — see the 120s-timeout that prompted this shape).
KOTLIN_LIST = re.compile(r'internal val (\w+) = listOf\(([^)]*)\)')
KOTLIN_LIST_ELEMENT = re.compile(r'"(0x[0-9a-fA-F]+)"')
JAVA_INT_ARRAY = re.compile(r'private static final int\[\] (\w+) = new int\[\] \{(.*?)\};', re.S)
JAVA_INT_ARRAY_ELEMENT = re.compile(r'(0x[0-9a-fA-F]+|\d+)')


def _collect_arrays(pattern, element_pattern, text):
    out = {}
    for name, body in pattern.findall(text):
        out[name] = [int(v, 0) for v in element_pattern.findall(body)]
    return out


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

# A second helper shape: `emitNativeToolbarButtons(builder, listOf(NativeToolbarButton(...)))`.
# There is no single call-site descriptor to extract, because the button is a data-class spec —
# the opcode is one `new-instance` + `invoke-direct` pair per NativeToolbarButton, and the action
# comes from its `actionCtor = X` named argument. Constructors may take Int args (the helper
# emits one `const/4`/`const/16` per arg) so the member may be either `<init>()V` or
# `<init>(I…)V`. Accept both a const-val name and a direct string literal; either way the
# resulting string must be a full `<init>(…)V` descriptor.
NATIVE_TOOLBAR_HELPER = "emitNativeToolbarButtons"
NATIVE_TOOLBAR_ARG = re.compile(
    r'\bactionCtor\s*=\s*([A-Z_][A-Z0-9_]*|"(?:' + EXTENSION_TYPE + r')-><init>\([^)]*\)V")'
)

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
        # Comments first: KDoc talking about a member descriptor is not an emission of it, and
        # the "silently stopped checking anything" guard has false-fired on patches that
        # referenced an extension class only to explain it in prose.
        text = _expand(LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", path.read_text())))

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

        # The button helper has no one-call descriptor to parse; each NativeToolbarButton's
        # `actionCtor = X` named arg declares what gets emitted as `invoke-direct X` (a
        # constructor). Checked the same way whether `X` is a const name or a direct string —
        # escaping the helper boundary is the whole point of declaring it as a const.
        if NATIVE_TOOLBAR_HELPER in text:
            for arg in NATIVE_TOOLBAR_ARG.findall(text):
                descriptor = (
                    arg[1:-1] if arg.startswith('"') else constants.get(arg)
                )
                if descriptor is None:
                    problems.append(
                        f"  {path.name} calls {NATIVE_TOOLBAR_HELPER}, whose actionCtor {arg} is "
                        f"not a string constant in that file, so what it emits cannot be checked"
                    )
                    continue
                match = MEMBER.match(descriptor)
                if match is None:
                    problems.append(
                        f"  {path.name} calls {NATIVE_TOOLBAR_HELPER}, whose actionCtor value "
                        f"{descriptor!r} is not a complete extension member descriptor"
                    )
                    continue
                emitted.append(("direct", *match.groups()))

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

        # Every class the patch route puts into an `(Ljava/lang/Runnable;)V`-typed slot has to
        # actually implement Runnable; Gboard's toolbar-builder setter happily stores whatever it
        # is given and the failure surfaces as an ART class-verification crash at keyboard start.
        #
        # Two lanes in: the legacy `new-instance <T>` line at the use site (none today, but keep it
        # — future authors will reach for it first), and the helper lane's `actionCtor` descriptor,
        # which is a `<init>(...)V` on a class the helper turns into a Runnable.
        runnable_distinct = set()
        for descriptor, (_source, _body) in sources.items():
            constructed = re.search(rf"new-instance\s+\w+\s*,\s*{re.escape(descriptor)}", text)
            if constructed and "(Ljava/lang/Runnable;)V" in text:
                runnable_distinct.add(descriptor)
        for arg in NATIVE_TOOLBAR_ARG.findall(text):
            descriptor = arg[1:-1] if arg.startswith('"') else constants.get(arg)
            if descriptor is not None:
                # NATIVE_TOOLBAR_ARG is a full member descriptor; strip "-><init>(…)V" to get the
                # class it belongs to.
                runnable_distinct.add(descriptor.split("->")[0])
        for descriptor in runnable_distinct:
            if descriptor not in sources:
                continue
            source, body = sources[descriptor]
            if not re.search(r"\bimplements\b[^{]*\bRunnable\b", body):
                problems.append(
                    f"  {path.name} hands {descriptor} to a Runnable action slot, but "
                    f"{source.name} does not declare `implements Runnable`"
                )


def _xml_entries(text):
    """{android:key: {attribute: value}} for each element in a settings XML resource."""
    entries = {}
    for element in re.findall(r"<([\w$.]+)\s+([^>]+?)/?>", text):
        attrs = dict(re.findall(r'([\w:]+)="([^"]*)"', element[1]))
        key = attrs.get("android:key")
        if key:
            entries[key] = attrs
    return entries


def _check_settings_xml(problems, kotlin):
    """The native settings rows agree with the smali readers about keys, defaults and bounds.

    Same silent-drift class as the preference keys this file started with: the XML compiles, the
    smali assembles, the slider moves, and the number it writes is read back under a different key
    or clamped by a different bound. Nothing fails until someone notices a setting doing nothing.
    """
    text = SETTINGS_XML.read_text()
    entries = _xml_entries(text)
    for key_const, attributes in XML_ROWS:
        key = kotlin.get(key_const)
        if key is None:
            problems.append(f"  {key_const} is not declared in any patch")
            continue
        row = entries.get(key)
        if row is None:
            problems.append(
                f"  {key_const} = {key!r} has no row in flexboard_settings.xml — the engine reads "
                f"a key the screen never writes"
            )
            continue
        for attribute, value_const in attributes.items():
            wanted = kotlin.get(value_const)
            if wanted is None:
                problems.append(f"  {value_const} is not declared in any patch")
                continue
            actual = row.get(attribute)
            if actual is None:
                problems.append(
                    f"  the {key!r} row in flexboard_settings.xml has no {attribute} attribute"
                )
            elif _normalised(actual) != _normalised(wanted):
                problems.append(
                    f"  the {key!r} row's {attribute} = {actual!r} but {value_const} = "
                    f"{wanted!r} — the screen and the engine disagree"
                )


# A dotted extension class name (the settings fragments are referenced from *resource* rows, so
# no descriptor string exists for them). Existence is all that is checked: the host does
# Class.forName on this string, and a rename breaks only on a phone. The final component must
# start uppercase so that package mentions ("…extension.settings") are not mistaken for classes.
DOTTED_EXTENSION_CLASS = re.compile(r"dev\.jz6\.flexboard\.extension(?:\.\w+)*\.[A-Z]\w*(?:\$[A-Z]\w*)*")

# The fragment names land in two places: Kotlin (interpolated into XML) and the patch resources
# themselves. Both lanes are scanned — a rename that only updates one is the same silent break
# the descriptor lane above was built for.
EXTENSION_RESOURCES = ROOT / "patches/src/main/resources"


def _check_dotted_extension_classes(problems):
    sources = []
    for path in PATCHES.rglob("*.kt"):
        sources.append(path)
    for path in EXTENSION_RESOURCES.rglob("*.xml"):
        sources.append(path)
    for path in sources:
        text = LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", path.read_text()))
        for name in sorted(set(DOTTED_EXTENSION_CLASS.findall(text))):
            outer = name.split("$")[0]
            source = EXTENSION_ROOT / (outer.replace(".", os.sep) + ".java")
            if not source.is_file():
                problems.append(
                    f"  {path.name} names the extension class {name}, but "
                    f"{source.relative_to(ROOT)} does not exist"
                )
                continue
            body = source.read_text()
            for inner in name.split("$")[1:]:
                if not re.search(rf'\bclass {re.escape(inner)}\b', body):
                    problems.append(
                        f"  {path.name} names {name}, but {source.name} declares no nested "
                        f"{inner} — the host will instantiate nothing on that row"
                    )

def main():
    problems = []
    kotlin, kotlin_from = {}, {}
    for path in PATCHES.rglob("*.kt"):
        # Comments carry prose shaped like constants ("`internal const val X = 0`" in a KDoc
        # paragraph) and would poison the same-name lookup on the Java side. Strip them here the
        # same way the reference check does it.
        text = LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", path.read_text()))
        for name, value in _collect(KOTLIN_CONST, text).items():
            if name in kotlin and kotlin[name] != value:
                problems.append(
                    f"  {name} is declared in multiple patch files with different values — "
                    f"{kotlin_from[name].name} says {kotlin[name]!r}, {path.name} says {value!r}"
                )
                continue
            kotlin[name], kotlin_from[name] = value, path

    # Every Java file in the extension, not just the settings screen. The screen was the only side
    # of the contract until the toolbar buttons arrived: their action ordinals are shared with
    # TextAction, which is not a settings class at all. Collecting one file silently reported those
    # as undeclared.
    java, declared_in = {}, {}
    java_arrays = {}
    for source in sorted(EXTENSION_ROOT.rglob("*.java")):
        text_stripped = LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", source.read_text()))
        for name, value in _collect_arrays(JAVA_INT_ARRAY, JAVA_INT_ARRAY_ELEMENT, text_stripped).items():
            if name in java_arrays and java_arrays[name] != value:
                problems.append(
                    f"  {name} is declared twice in the extension with different values"
                )
                continue
            java_arrays[name] = value
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

    # Array contracts. Kotlin's lists are collected on the fly since only hotkeys carry one today.
    kotlin_arrays = {}
    for path in PATCHES.rglob("*.kt"):
        text = LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", path.read_text()))
        kotlin_arrays.update(_collect_arrays(KOTLIN_LIST, KOTLIN_LIST_ELEMENT, text))
    for kt_name, java_name in ARRAY_PAIRS:
        kt_value, java_value = kotlin_arrays.get(kt_name), java_arrays.get(java_name)
        if kt_value is None:
            problems.append(f"  {kt_name} list is not declared in any patch")
        elif java_value is None:
            problems.append(f"  {java_name} array is not declared anywhere in the extension")
        elif kt_value != java_value:
            problems.append(
                f"  {kt_name} has {len(kt_value)} ids but {java_name} has {len(java_value)}, "
                f"or their contents differ ({kt_value!r} != {java_value!r})"
            )

    _check_extension_references(problems)
    _check_settings_xml(problems, kotlin)
    _check_dotted_extension_classes(problems)

    if problems:
        print("::error::The patches and the extension disagree about the preference contract:",
              file=sys.stderr)
        print("\n".join(problems), file=sys.stderr)
        return 1

    print(f"Patch, extension and settings XML agree on all {len(PAIRS)} shared constants "
          f"and {len(XML_ROWS)} slider rows.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
