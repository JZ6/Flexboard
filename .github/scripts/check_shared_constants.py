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
    # The ordinals the patch hands the extension's constructor. The extension maps them to
    # android.R.id.* so the framework constants stay symbolic in the one language that can name
    # them -- which means the number crossing the boundary is meaningless on its own, and a drift
    # would silently wire Copy to Paste rather than failing.
    ("TEXT_ACTION_SELECT_ALL", "SELECT_ALL"),
    ("TEXT_ACTION_COPY", "COPY"),
    ("TEXT_ACTION_PASTE", "PASTE"),
    # Hotkeys: the slot fan-out moving with the extension. (No count slider by design —
    # placeholders ship; clearing a slot's text hides it.)
    ("HOTKEY_SLOTS", "SLOT_COUNT"),
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
        # Both directions. Requiring static-when-invoke-static caught a Java member losing its
        # modifier, but emitting invoke-virtual against a method that is still static went
        # through — an IncompatibleClassChangeError on the device, from a lane whose docstring
        # says the opcode matters.
        if ("static" in modifiers) != needs_static:
            continue
        return True
    return False


def _collect_body(name):
    """The raw initializer text of a named list/array, whichever of the patch or extension of it
    it lives in. Used to compare members *in order*, which the per-const maps can't express."""
    for path in sorted(PATCHES.rglob("*.kt")) + sorted(EXTENSION_ROOT.rglob("*.java")):
        m = re.search(rf"\b{name}\s*=\s*(?:listOf|new String\[\])\s*({{|\()"
                      r"(.*?)(\}|\))", path.read_text(), re.S)
        if m:
            return m.group(2)
    return ""


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


SETTINGS_SECTIONS_KT = ROOT / (
    "patches/src/main/kotlin/dev/jz6/flexboard/patches/shared/SettingsSections.kt"
)



def _java_string_constant(name):
    """The value of a `private static final String NAME = "...";` anywhere in the extension."""
    for source in EXTENSION_ROOT.rglob("*.java"):
        m = re.search(
            rf'static\s+final\s+String\s+{re.escape(name)}\s*=\s*"([^"]*)"',
            source.read_text(),
        )
        if m:
            return m.group(1)
    return None



def _check_stock_package_name(problems):
    """The extension's fallback package name matches Constants.GBOARD_PACKAGE_NAME.

    resources.arsc answers to the stock package regardless of what installAsGboardClonePatch
    renamed the manifest to, so ResourceIds.byName falls back to it when the app's own name
    misses. A drift here fails nothing loudly -- the fallback simply stops being one, and the
    settings screen and icon pack go back to depending on the encoder reconciling the two names.

    Not in PAIRS because these live inside `internal object Constants`, whose members are plain
    `const val`, and the collector's pattern is `internal const val` -- widening that would change
    what every other pair resolves against.
    """
    kt = re.search(
        r'const val GBOARD_PACKAGE_NAME\s*=\s*"([^"]+)"',
        (PATCHES / "dev/jz6/flexboard/patches/shared/Constants.kt").read_text(),
    )
    java = _java_string_constant("STOCK_PACKAGE_NAME")
    if not kt or not java:
        problems.append(
            f"  stock package name check parsed nothing: patches {kt and kt.group(1)!r}, "
            f"extension {java!r}"
        )
    elif kt.group(1) != java:
        problems.append(
            f"  Constants.GBOARD_PACKAGE_NAME is {kt.group(1)!r} but ResourceIds."
            f"STOCK_PACKAGE_NAME is {java!r} — the resource-name fallback points at a package "
            f"the table does not answer to, so it silently stops being a fallback"
        )



def _check_allowed_set_sentinel(problems):
    """ToolbarSlotsPatch and preflight agree on which stock id locates the allowed-set array.

    The array's own name is obfuscated per build, so both find it by looking for a known member.
    They each spell that member out separately -- SENTINEL_ID in the patch, and
    native_allowed_set_sentinel in preflight -- and if they drift, preflight goes on asserting an
    id the patch no longer looks for, while the patch fails at run time against an array preflight
    said was fine.
    """
    kt = re.search(
        r'SENTINEL_ID\s*=\s*"([^"]+)"',
        (PATCHES / "dev/jz6/flexboard/patches/features/toolbar/ToolbarSlotsPatch.kt").read_text(),
    )
    pf = re.search(
        r"'native_allowed_set_sentinel':\s*'([^']+)'",
        (ROOT / "tools/apk/preflight.py").read_text(),
    )
    if not kt or not pf:
        problems.append(
            f"  allowed-set sentinel check parsed nothing: patch {kt and kt.group(1)!r}, "
            f"preflight {pf and pf.group(1)!r}"
        )
    elif kt.group(1) != pf.group(1):
        problems.append(
            f"  ToolbarSlotsPatch locates the allowed-set array by {kt.group(1)!r} but preflight "
            f"pins {pf.group(1)!r} — one of them is checking an array the other cannot find"
        )


def _check_section_sentinels(problems):
    """The template's @SECTION_X@ vocabulary is exactly the SettingsSection enum.

    filterSettingsSections drops any block whose sentinel does not name an enum constant, and
    leaves alone any block whose sentinels do not pair. Both produce well-formed XML, so the
    patch succeeds and the APK links; the only symptom is a settings category that is silently
    absent from every build, or silently present in every build with its markers left in as
    comments. The patch asserts this too, but only once someone runs it -- this is the lane that
    fails on a push.
    """
    enum_body = re.search(
        r"enum class SettingsSection\s*\{(.*?)\}",
        SETTINGS_SECTIONS_KT.read_text(),
        re.S,
    )
    if not enum_body:
        problems.append("  SettingsSection enum could not be parsed — the sentinel check is blind")
        return
    declared = set(re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*,", enum_body.group(1), re.M))

    text = SETTINGS_XML.read_text()
    opened = re.findall(r"<!--\s*@SECTION_(\w+)@\s*-->", text)
    closed = re.findall(r"<!--\s*@END_SECTION_(\w+)@\s*-->", text)

    if not declared or not opened:
        problems.append(
            f"  section sentinel check parsed nothing: enum {sorted(declared)}, "
            f"template {sorted(set(opened))}"
        )
        return
    if sorted(opened) != sorted(closed):
        problems.append(
            f"  flexboard_settings.xml sentinels are unbalanced: opened {sorted(opened)}, "
            f"closed {sorted(closed)}"
        )
    if set(opened) != declared:
        problems.append(
            f"  flexboard_settings.xml marks {sorted(set(opened))} but SettingsSection declares "
            f"{sorted(declared)} — a section the enum does not know is dropped from every build, "
            f"and a section the template does not mark is emitted in every build"
        )


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


# A dotted extension class name (the settings fragment is referenced from a *resource* row, so no
# descriptor string exists for it). Existence is all that is checked: the host does
# Class.forName on this string, and a rename breaks only on a phone. The final component must
# start uppercase so that package mentions ("…extension.settings") are not mistaken for classes.
DOTTED_EXTENSION_CLASS = re.compile(r"dev\.jz6\.flexboard\.extension(?:\.\w+)*\.[A-Z]\w*")

# The emitted smali stages a key as `const-string v$x, "$SOME_KEY"` — the consumers of the
# screen's keys are the injected readers, not extension Java.
# Any register spelling, not just an interpolated one. `const-string v$key, "$SOME_KEY"` was
# matched and `const-string v3, "$SOME_KEY"` was not, so the ordinary form staged a key the screen
# never wrote and this rule said nothing.
EMITTED_KEY_READ = re.compile(r'const-string\s+[vp][\w${}]+\s*,\s*"\$\{?(\w+)\}?"')


def _check_screen_contract(problems, kotlin):
    """Coverage rules for every keyed row of flexboard_settings.xml, no per-row opt-out.

    XML_ROWS asserts the rows it knows about, attribute by attribute. That leaves a silent lane:
    adding a row nobody registered passes every check while the engine under it reads a key the
    screen never writes, or a row typoed `…_hol_ms` writes a key nothing reads. These rules close
    that: uniqueness, every key must be a declared patch constant, every key an emitted reader
    stages must have a row, and any row that carries bounds or a default must be registered in
    XML_ROWS so its attrs get asserted pair by pair.
    """
    keys = re.findall(r'android:key="([^"]+)"', SETTINGS_XML.read_text())
    entries = _xml_entries(SETTINGS_XML.read_text())
    for dup in {k for k in keys if keys.count(k) > 1}:
        problems.append(f"  flexboard_settings.xml carries the key {dup!r} twice")

    const_values = set(kotlin.values())
    # A constant whose value ends with "_" declares a key *family*: generated keys like
    # `flexboard_hotkey_7_text` are produced by code, so they can't all be literal const values —
    # but they must begin with a family prefix an author committed to somewhere.
    families = [v for v in const_values if isinstance(v, str) and v.endswith("_")]
    for key in set(keys):
        if key in const_values:
            continue
        if any(key.startswith(family) for family in families):
            continue
        problems.append(
            f"  flexboard_settings.xml row {key!r} is not the value of any patch constant — "
            f"either the key is typoed or the constant it feeds was renamed"
        )

    # Staged in smali with no settings row by design: HOLD_DELAY_KEY's row was dropped ("default
    # 0, nobody wants a delay") while the smali read stays, so blobless users get exactly 0.
    # Declared here so the rule still covers keys nobody thought about. STEP_SCALE_KEY was the
    # other entry, removed with the parked swipe-length scaling that was its only reader -- the
    # dead-exemption check below is what caught it still being listed.
    stage_only = {"HOLD_DELAY_KEY"}
    staged = set()
    for path in PATCHES.rglob("*.kt"):
        text = LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", path.read_text()))
        for const_name in EMITTED_KEY_READ.findall(text):
            # The register spelling is no longer part of the match, so filter on the name here
            # instead: this rule is about preference keys, not every interpolated constant.
            if "KEY" in const_name:
                staged.add((path.name, const_name))

    # An exemption for a key nothing stages any more is not neutral — it silently widens as the
    # code moves, exactly like the pins PIN_LESS is checked against in check_dex_pins.py.
    for name in sorted(stage_only - {const for _, const in staged}):
        problems.append(
            f"  {name} is exempted from the staged-key rule but no patch stages it — the "
            f"exemption now covers nothing and should go"
        )

    for file_name, const_name in sorted(staged):
        if const_name in stage_only:
            continue
        key = kotlin.get(const_name)
        if key and key not in entries:
            problems.append(
                f"  {file_name}'s smali stages {key!r} for {const_name}, but the screen "
                f"has no row that writes it — the slider would read the patch default forever"
            )
    registered = {kotlin.get(name) for name, _ in XML_ROWS}
    registered.discard(None)
    for key, attrs in entries.items():
        if key in registered:
            continue
        bounded = [a for a in attrs if a in ("android:defaultValue", "slider_min_value",
                                             "slider_max_value")]
        if bounded:
            problems.append(
                f"  the {key!r} row carries {sorted(bounded)} but is not registered in "
                f"XML_ROWS, so nothing asserts those literals against the engine's constants — "
                f"add the row or drop the attribute"
            )

    # The per-slot fan-out has a count, not just a family: the screen must carry exactly
    # HOTKEY_SLOTS merged text rows and no icon rows — the icon lives in the same row's dialog
    # now, so a flexboard_hotkey_N_icon row key is regression, not a feature. The family rule
    # above can't see either: a 13th row is a dead control, an 11th is a slot with no editor,
    # a revived icon row splits the edit surface in two.
    slot_count = int(kotlin.get("HOTKEY_SLOTS", "0"))

    # The key *format* is built at runtime from three Java constants -- prefix + slot + suffix in
    # Hotkeys.textKey -- and appears again as literal row keys in the settings XML, and a third
    # time as the pattern this checker matches with. Nothing compared them. Renaming the prefix
    # left every row dead (isRow compares against d(textKey(slot)), which then finds nothing),
    # every hotkey silently un-editable, and Hotkey.run reading a key nothing writes, with all
    # three lanes green. Hotkeys.java's own comment predicted exactly that.
    #
    # So the pattern is derived from the Java rather than spelled here.
    java_prefix = _java_string_constant("PREF_TEXT_PREFIX")
    java_suffix = _java_string_constant("PREF_TEXT_SUFFIX")
    if not java_prefix or not java_suffix:
        problems.append(
            "  Hotkeys.PREF_TEXT_PREFIX/PREF_TEXT_SUFFIX could not be parsed, so the hotkey row "
            "keys are being matched against a pattern nothing pins"
        )
        java_prefix, java_suffix = "flexboard_hotkey_", "_text"
    row_pattern = re.compile(
        re.escape(java_prefix) + r"\d+" + re.escape(java_suffix)
    )

    want = {f"{java_prefix}{n}{java_suffix}" for n in range(1, slot_count + 1)}
    got = {k for k in keys if row_pattern.fullmatch(k)}
    if got != want:
        problems.append(
            f"  flexboard_settings.xml should carry exactly the {slot_count} merged "
            f"hotkey rows ({java_prefix}1..{slot_count}{java_suffix}); "
            f"missing {sorted(want - got)}, extra {sorted(got - want)}"
        )
    icon_rows = sorted(k for k in keys if re.fullmatch(r"flexboard_hotkey_\d+_icon", k))
    if icon_rows:
        problems.append(
            f"  flexboard_settings.xml carries icon rows {icon_rows} — icon editing lives in "
            f"each hotkey row's composite dialog; icon keys are store keys, not row keys"
        )


def _check_dotted_extension_classes(problems):
    for path in PATCHES.rglob("*.kt"):
        text = LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", path.read_text()))
        for name in sorted(set(DOTTED_EXTENSION_CLASS.findall(text))):
            source = EXTENSION_ROOT / (name.replace(".", os.sep) + ".java")
            if not source.is_file():
                problems.append(
                    f"  {path.name} names the extension class {name}, but "
                    f"{source.relative_to(ROOT)} does not exist"
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
    for source in sorted(EXTENSION_ROOT.rglob("*.java")):
        for name, value in _collect(JAVA_CONST, source.read_text()).items():
            if name in java and java[name] != value:
                problems.append(
                    f"  {name} is declared twice in the extension with different values — "
                    f"{declared_in[name]} says {java[name]!r}, {source.name} says {value!r}"
                )
                continue
            java[name], declared_in[name] = value, source.name

    # The hotkey default-icon order, held in lockstep across the patch's drawable loop and the
    # extension's defaults table: Kotlin names the symbol, Java the flexboard_icon_<symbol>. An
    # empty parse on either side must fail too — renamed initializers otherwise compare [] != []
    # green while pinning nothing.
    kt_syms = re.findall(r'"([a-z_]+)"', _collect_body("HOTKEY_DEFAULT_SYMBOLS"))
    java_names = re.findall(r'"flexboard_icon_([a-z_]+)"', _collect_body("DEFAULT_ICON_NAMES"))
    if not kt_syms or not java_names or kt_syms != java_names:
        problems.append(
            f"  hotkey default icon order drifted or failed to parse: patch says {kt_syms}, "
            f"extension says {java_names}"
        )

    # The list is read as DEFAULT_ICON_NAMES[slot - 1] for every slot, so it may be longer than the
    # slot count (the surplus is the picker's front rows) but never shorter — that would be an
    # index-out-of-bounds the first time an unassigned high slot drew its default.
    declared_slots = int(kotlin.get("HOTKEY_SLOTS", "0"))
    if kt_syms and declared_slots and len(kt_syms) < declared_slots:
        problems.append(
            f"  HOTKEY_DEFAULT_SYMBOLS has {len(kt_syms)} entries but HOTKEY_SLOTS is "
            f"{declared_slots} — slot {len(kt_syms) + 1} has no default icon to resolve"
        )

    # Same rule for the picker's back half: the patch writes the extra drawables, the extension
    # lays them out past the defaults in the same order. (Digits appear in counter_*, so
    # this pattern admits them where the defaults' does not.)
    kt_extra = re.findall(r'"([a-z0-9_]+)"', _collect_body("HOTKEY_EXTRA_SYMBOLS"))
    java_extra = re.findall(r'"flexboard_icon_([a-z0-9_]+)"', _collect_body("EXTRA_ICON_NAMES"))
    if not kt_extra or not java_extra or kt_extra != java_extra:
        problems.append(
            f"  hotkey picker extra-icon order drifted or failed to parse: patch says {kt_extra}, "
            f"extension says {java_extra}"
        )

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
    _check_section_sentinels(problems)
    _check_stock_package_name(problems)
    _check_allowed_set_sentinel(problems)
    _check_settings_xml(problems, kotlin)
    _check_dotted_extension_classes(problems)
    _check_screen_contract(problems, kotlin)

    if problems:
        print("::error::The patches and the extension disagree about the preference contract:",
              file=sys.stderr)
        print("\n".join(problems), file=sys.stderr)
        return 1

    print(f"Patch, extension and settings XML agree on all {len(PAIRS)} shared constants, "
          f"{len(XML_ROWS)} slider rows, and every row-level coverage rule.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
