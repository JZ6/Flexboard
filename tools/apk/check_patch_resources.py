#!/usr/bin/env python3
"""
Lane B: resource dress rehearsal.

Replays, locally and with no Android SDK, the exact resource writes the patch bundle performs
inside Morphe Manager, then asks arsclib to rebuild the whole resource table — the same call
stack that rejected 1.4.0-dev.3 (filename-derived bogus type) and 1.4.0-dev.4 (spliced second
`<resources>` tag, SAX death at arrays.xml:7141). A red lane here means the phone would have
failed; a green lane here means the resource half of a release is safe to tag.

Nothing here patches dex — bytecode anchors are preflight's job (`tools/apk/preflight.py`).

What runs, in order:

  1. jar    resolve the pinned arsclib jar (env → cache → gradle cache → jitpack).
  2. decode full XML decode of the target APK — cached by the APK's sha256, so this costs
            minutes exactly once per Gboard version, seconds afterwards.
  3. replay re-apply every patch-side resource write against a copy of the decode:
              - `writePatchResource` copies   (COPY_WRITES, mirroring SettingsScreenPatch)
              - `@FLEXBOARD_VERSION@` fill    (same rule as :patches:processResources)
              - the settings.xml row insert   (same attrs/order as addFlexboardEntry)
              - *values merges*               (VALUE_MERGES — see the comment there)
  4. parse  every file the replay touched is DOM-parsed and named on failure — the python
            twin of the in-patch assert added in `test: parse every patch-written resource
            file before handing it to Morphe`.
  5. encode full arsclib table rebuild of the modified tree. Type-name and structure bugs
            die here with arsclib's own message.

Local-only, deliberately: the target APK is not in git, so CI cannot run this lane. Usage:

    python3 tools/apk/check_patch_resources.py [gboard.apk]

Cache: $FLEXBOARD_CACHE, default ~/.cache/flexboard. Set $ARSCLIB_JAR to skip jar resolution.
"""
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import xml.dom.minidom
import xml.etree.ElementTree as ET
from hashlib import sha256
from glob import glob
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent

# arsclib, as pulled in transitively by morphe-patcher (gradle/libs.versions.toml → jitpack
# commit-hash version). Cross-check after bumping the patcher:
#   ./gradlew :patches:dependencies --configuration runtimeClasspath | grep arsclib
ARSCLIB_VERSION = "a28c6fb2a7"

CACHE = Path(os.environ.get("FLEXBOARD_CACHE", Path.home() / ".cache" / "flexboard"))
SHIM_SOURCE = REPO / "tools" / "apk" / "ArsclibRoundTrip.java"

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID_NS)

# -------------------------------------------------------------------------------------------
# The replay lists — mirrors of patch code. When SettingsScreenPatch changes what it writes,
# this list changes with it. That coupling is deliberate: a missed entry is a missed blind spot.
# -------------------------------------------------------------------------------------------

# writePatchResource(name, target[, placeholders]): (source under patches/src/main/resources,
# destination inside the decoded `resources/` dir). Order matches the patch's finalize block.
COPY_WRITES = [
    ("drawable/flexboard_settings_icon.xml", "res/drawable"),
    ("xml/flexboard_settings.xml", "res/xml"),
] + [
    # The hotkey icon pack the settings screen writes wholesale into res/drawable:
    # twelve per-slot symbol defaults plus the candidates set.
    (f"drawable/{name}.xml", "res/drawable")
    for name in (
        ["flexboard_icon_" + s for s in (
            "alternate_email", "password", "phone_enabled", "local_post_office",
            "home_pin", "work", "favorite", "kid_star", "credit_card",
            "hexagon", "hive", "sports_soccer",
        )]
        + ["flexboard_icon_snowflake", "flexboard_icon_token"]
        # 0..9, not 1..9. The counters start at zero, and the missing one was never DOM-parsed
        # and never went through the arsclib encode — the exact lane that killed dev.3/dev.4.
        + [f"flexboard_icon_counter_{n}" for n in range(0, 10)]
    )
]

# Write sets that splice an existing decoded values file. Policy history: values-file surgery
# burned dev.3/dev.4 and stayed banned through the hotkey rollback; the toolbar-slot widening
# (docs/toolbar-access-points.md) is the first consumer back, mapped here explicitly. An
# unmapped patch values file still fails the lane — the mapping is the review.
VALUE_MERGES = [
    # ToolbarIdAdmission: every admitted id becomes a strings entry, then an array item.
    ("values/flexboard_toolbar_slots.xml", "res/values/strings.xml"),
]

# Arrays the patch widens, replayed on the real decoded array: (sentinel id value whose
# *referencing* string locates the holder array, how many ids get appended). Mirrors
# widenAllowedIdSet() in ToolbarIdAdmission.kt — the sentinel is a content pin precisely
# because the array's name is obfuscated per build. The appended count is read off the same
# values fragment the patch splices in, so trimming/expanding the slot count never has to be
# restated here (the patch's own require() holds fragment and HOTKEY_SLOTS in step).
ARRAY_WIDENINGS = [
    ("editor_info",
     len(re.findall(r'name="flexboard_hotkey_\d+"',
                    (REPO / "patches/src/main/resources/values/"
                     "flexboard_toolbar_slots.xml").read_text()))),
]

# Any patch resource under values/ that is NOT covered by VALUE_MERGES. Values files are
# banned on this branch; surfacing one as an error is the lane doing its job.
BANNED_UNMAPPED_VALUES = True

# addFlexboardEntry(): the row into res/xml/settings.xml, attrs in the patch's set-order.
SETTINGS_ROW_TAG = "com.google.android.libraries.inputmethod.settings.widget.HeaderPreference"
SETTINGS_ROW_ATTRS = [
    ("key", "flexboard_settings"),
    ("title", "Flexboard"),
    ("summary", "Gesture settings"),
    ("persistent", "false"),
    ("icon", "@drawable/flexboard_settings_icon"),
    ("fragment", "dev.jz6.flexboard.extension.settings.FlexboardSettingsFragment"),
]
SETTINGS_SCREEN_FILES = ["res/xml/settings.xml", "res/xml/settings_legacy.xml"]
PREFERENCE_CATEGORY_TAG = "androidx.preference.PreferenceCategory"


def fail(stage, message):
    print(f"FAIL [{stage}]: {message}")
    sys.exit(1)


def run(cmd, stage, **kw):
    proc = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if proc.returncode != 0:
        tail = (proc.stderr or proc.stdout).strip().splitlines()[-15:]
        fail(stage, " ".join(tail) or f"exit {proc.returncode}")
    return proc


# -------------------------------------------------------------------------------------------
# Stage 1: the pinned arsclib jar
# -------------------------------------------------------------------------------------------

def resolve_arsclib():
    if os.environ.get("ARSCLIB_JAR"):
        p = Path(os.environ["ARSCLIB_JAR"])
        if p.exists():
            return p
        fail("jar", f"ARSCLIB_JAR points at missing file {p}")
    cached = CACHE / f"arsclib-{ARSCLIB_VERSION}.jar"
    if cached.exists():
        return cached
    hits = glob(
        str(Path.home() / ".gradle/caches/modules-2/files-2.1/com.github.REAndroid/arsclib"
            f"/{ARSCLIB_VERSION}/*/arsclib-{ARSCLIB_VERSION}.jar")
    )
    if hits:
        cached.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(hits[0], cached)
        return cached
    url = ("https://jitpack.io/com/github/REAndroid/arsclib"
           f"/{ARSCLIB_VERSION}/arsclib-{ARSCLIB_VERSION}.jar")
    cached.parent.mkdir(parents=True, exist_ok=True)
    print(f"jar: downloading {url}")
    try:
        urllib.request.urlretrieve(url, cached)
    except Exception as e:
        fail("jar", f"download from jitpack failed: {e}")
    if cached.stat().st_size < 1_000_000:
        cached.unlink(missing_ok=True)
        fail("jar", f"download looked wrong (too small): {cached}")
    return cached


def compile_shim(classpath):
    classes = CACHE / "classes"
    cls = classes / "ArsclibRoundTrip.class"
    if cls.exists() and cls.stat().st_mtime > SHIM_SOURCE.stat().st_mtime:
        return classes
    if shutil.which("javac") is None:
        fail("shim", "javac not on PATH — any JDK suffices, this is not the Android SDK")
    classes.mkdir(parents=True, exist_ok=True)
    run(["javac", "-cp", str(classpath), "-d", str(classes), str(SHIM_SOURCE)], "shim")
    return classes


# -------------------------------------------------------------------------------------------
# Stage 2: decode, cached by APK hash
# -------------------------------------------------------------------------------------------

def decode_tree(jar, classes, apk):
    digest = sha256(apk.read_bytes()).hexdigest()[:16]
    out = CACHE / "decode" / digest
    marker = out / ".complete"
    if marker.exists():
        print(f"decode: cache hit ({digest})")
        return out
    print(f"decode: full XML decode of {apk.name} (once for this apk)")
    tmp = out.with_suffix(".tmp")
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True)
    run(["java", "-cp", f"{jar}:{classes}", "ArsclibRoundTrip", "decode", str(apk), str(tmp)],
        "decode", timeout=1800)
    package_dir = tmp / "resources"
    if not package_dir.is_dir():
        fail("decode", "decoder produced no resources/ dir")
    shutil.rmtree(out, ignore_errors=True)
    tmp.rename(out)
    (out / ".complete").touch()
    return out


# -------------------------------------------------------------------------------------------
# Stage 3: replay the patch's writes on a hardlinked copy
# -------------------------------------------------------------------------------------------

def link_or_copy(src, dst):
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def reset_scratch(decoded):
    scratch = CACHE / "scratch"
    shutil.rmtree(scratch, ignore_errors=True)
    print("replay: hardlinking decode into scratch")
    shutil.copytree(decoded, scratch, copy_function=link_or_copy)
    (scratch / ".complete").unlink(missing_ok=True)
    return scratch


def bundle_version():
    text = (REPO / "gradle.properties").read_text()
    m = re.search(r"^version\s*=\s*(\S+)", text, re.M)
    if not m:
        fail("replay", "no version= in gradle.properties")
    return m.group(1)


def dom_parse(path, stage="parse"):
    try:
        xml.dom.minidom.parse(str(path))
    except Exception as e:
        fail(stage, f"{path.name} is not well-formed XML: {e}")


def define_public_id(pkg_dir, type_name, res_name):
    # Python twin of PublicXmlManager.createPublicId: append <public> entries for names the
    # patch introduces, with the next free id in that type's range, above the closing tag.
    public_xml = pkg_dir / "res" / "values" / "public.xml"
    text = public_xml.read_text()
    if f'type="{type_name}" name="{res_name}"' in text:
        return
    ids = [int(m.group(1), 16) for m in re.finditer(
        rf'<public id="0x([0-9a-f]+)" type="{type_name}" ', text)]
    if not ids:
        fail("replay", f"public.xml has no {type_name} entries to derive an id from")
    entry = f'    <public id="0x{max(ids) + 1:08x}" type="{type_name}" name="{res_name}"/>\n'
    text = text.replace("</resources>", entry + "</resources>", 1)
    write_fresh(public_xml, text)
    print(f"replay: defined @{type_name}/{res_name} in public.xml")


def write_fresh(dest, text):
    # Scratch files are hardlinks into the decode cache; truncating one in place would edit the
    # cache. Unlink first so the write lands on a brand-new inode.
    dest.unlink(missing_ok=True)
    dest.write_text(text)


def replay(scratch):
    touched = []
    # The decoder nests the package dir one level below resources/ (PackageInfo-suffixed or
    # package_1); find the dir that actually holds res/.
    candidates = [d for d in (scratch / "resources").iterdir() if (d / "res").is_dir()]
    if len(candidates) != 1:
        fail("replay", f"expected exactly one package dir under resources/, got {candidates}")
    pkg_dir = candidates[0]

    # COPY_WRITES is a hand-maintained mirror of SettingsScreenPatch's writes, and a drawable
    # missing from it is simply never replayed — not DOM-parsed, not put through the arsclib
    # encode. That is the lane dev.3/dev.4 died in, and the counter_0 omission sat here unnoticed
    # because nothing compared the list against the tree. BANNED_UNMAPPED_VALUES does this job
    # for values/; this does it for the rest.
    on_disk = {
        f"{p.parent.name}/{p.name}"
        for p in (REPO / "patches" / "src" / "main" / "resources").rglob("*.xml")
        if p.parent.name != "values"
    }
    unreplayed = on_disk - {src for src, _ in COPY_WRITES}
    if unreplayed:
        fail("replay", f"patch resources never replayed through the encode: {sorted(unreplayed)}")

    for source_rel, target in COPY_WRITES:
        source = REPO / "patches" / "src" / "main" / "resources" / source_rel
        if not source.exists():
            fail("replay", f"missing patch resource {source_rel}")
        xml = source.read_text()
        # Same substitution :patches:processResources performs for the settings screen.
        if "@FLEXBOARD_VERSION@" in xml:
            xml = xml.replace("@FLEXBOARD_VERSION@", bundle_version())
        leftover = re.search(r"@[A-Z_]+@", xml)
        if leftover:
            fail("replay", f"unsubstituted placeholder {leftover.group(0)} in {source_rel}")
        dest = pkg_dir / target / source.name
        dest.parent.mkdir(parents=True, exist_ok=True)
        existed = dest.exists()
        write_fresh(dest, xml)
        dom_parse(dest)
        touched.append(dest)
        if not existed:
            # Morphe's ResourceIdProcessor defines every new resource name in public.xml before
            # arsclib sees it; without a <public> entry the encoder rejects the reference.
            define_public_id(pkg_dir, target.removeprefix("res/"), source.name.split(".")[0])

    # The settings row insert, mirroring Document.addFlexboardEntry() — onto both the modern and
    # the legacy top-level screen, because Gboard picks between them per device.
    a = lambda k: f"{{{ANDROID_NS}}}{k}"
    for screen_file in SETTINGS_SCREEN_FILES:
        settings = pkg_dir / screen_file
        tree = ET.parse(settings)
        root = tree.getroot()
        if root.tag != "PreferenceScreen":
            fail("replay", f"{screen_file} root <{root.tag}> is not <PreferenceScreen>")
        have = any(e.get(a("key")) == "flexboard_settings" for e in root.iter())
        if not have:
            row = ET.Element(SETTINGS_ROW_TAG)
            for k, v in SETTINGS_ROW_ATTRS:
                row.set(a(k), v)
            category = next((c for c in root if c.tag == PREFERENCE_CATEGORY_TAG), None)
            (category if category is not None else root).insert(0, row)
            write_fresh(settings, "<?xml version='1.0' encoding='utf-8'?>\n"
                                  + ET.tostring(root, encoding="unicode"))
            dom_parse(settings)
            touched.append(settings)

    # Values merges (empty by policy today; see VALUE_MERGES).
    values_dir = REPO / "patches" / "src" / "main" / "resources" / "values"
    patched = {src for src, _ in VALUE_MERGES}
    if values_dir.is_dir():
        for f in values_dir.iterdir():
            if f"values/{f.name}" not in patched:
                if BANNED_UNMAPPED_VALUES:
                    fail("replay", f"unmapped patch values file {f.name}: values writes are "
                                   "banned on this branch (dev.3/dev.4); map it in VALUE_MERGES "
                                   "with its arsclib proof, or delete it")
    for source_rel, target in VALUE_MERGES:
        source = REPO / "patches" / "src" / "main" / "resources" / source_rel
        our = source.read_text()
        inner = our.split("<resources>", 1)[1].rsplit("</resources>", 1)[0].strip()
        new_names = re.findall(r'name="(flexboard_[a-z_0-9]+)"', inner)
        dest = pkg_dir / target
        existing = dest.read_text() if dest.exists() else None
        if existing is None or "</resources>" not in existing:
            write_fresh(dest, '<?xml version="1.0" encoding="utf-8"?>\n'
                              f"<resources>\n{inner}\n</resources>\n")
        else:
            marker = new_names[0] if new_names else ""
            if marker and f'name="{marker}"' in existing:
                continue
            write_fresh(dest, existing.rstrip()[: -len("</resources>")].rstrip()
                              + "\n" + inner + "\n</resources>\n")
        dom_parse(dest)
        touched.append(dest)
        # PublicXmlManager.createPublicIdsFromValuesXml runs over every modified values file,
        # handing each new name a public id; mirror it or the reference check below lies.
        for new_name in new_names:
            define_public_id(pkg_dir, "string", new_name)

    # Array widening: mirror widenAllowedIdSet — resolve the sentinel id's string, find the one
    # array referencing it, append the new ids as @string items.
    arrays_xml = pkg_dir / "res" / "values" / "arrays.xml"
    strings_xml = pkg_dir / "res" / "values" / "strings.xml"
    if ARRAY_WIDENINGS and arrays_xml.exists():
        arrays = arrays_xml.read_text()
        strings = strings_xml.read_text() if strings_xml.exists() else ""
        new_ids = re.findall(
            r'name="(flexboard_hotkey_\d+)"',
            (REPO / "patches" / "src" / "main" / "resources" / VALUE_MERGES[0][0]).read_text(),
        )
        for sentinel, count in ARRAY_WIDENINGS:
            assert len(new_ids) == count, \
                f"ARRAY_WIDENINGS wants {count} ids, the mapped values file carries {len(new_ids)}"
            m = re.search(rf'<string name="([\w.]+)"[^>]*>{sentinel}</string>', strings)
            if not m:
                fail("replay", f"sentinel id {sentinel!r} resolves to no string — seam moved")
            ref = f"@string/{m.group(1)}"
            holders = [b for b in re.findall(r'(<array name="[^"]+">.*?</array>)', arrays, re.S)
                       if ref in b]
            if len(holders) != 1:
                fail("replay", f"expected exactly one array referencing {ref}, got {len(holders)}")
            holder = holders[0]
            items = "\n".join(f"    <item>@string/{i}</item>" for i in new_ids)
            widened = holder.replace("</array>", f"{items}\n  </array>")
            old_count = holder.count("<item>")
            arrays = arrays.replace(holder, widened)
            delta = widened.count("<item>") - old_count
            if delta != count:
                fail("replay", f"widening added {delta} items, expected {count}")
        write_fresh(arrays_xml, arrays)
        dom_parse(arrays_xml)
        touched.append(arrays_xml)

    for t in touched:
        print(f"replay: wrote {t.relative_to(pkg_dir)}")
    return pkg_dir


# -------------------------------------------------------------------------------------------
# Stage 5: full-table encode proof
# -------------------------------------------------------------------------------------------

def encode_proof(jar, classes, scratch):
    out = CACHE / "scratch_resources.apk"
    out.unlink(missing_ok=True)
    print("encode: rebuilding the full resource table (this is the stage that killed dev.3/4)")
    proc = subprocess.run(
        ["java", "-cp", f"{jar}:{classes}", "ArsclibRoundTrip", "encode", str(scratch), str(out)],
        capture_output=True, text=True, timeout=1800)
    if proc.returncode != 0 or not out.exists():
        tail = "\n".join((proc.stderr or proc.stdout).strip().splitlines()[-20:])
        fail("encode", f"\n{tail}")
    print(f"encode: OK ({out.stat().st_size // 1024} KB resources.apk)")
    out.unlink(missing_ok=True)


def main():
    apk = Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "gboard.apk"
    apk = apk.resolve()
    if not apk.exists():
        fail("input", f"target apk not found: {apk}")
    jar = resolve_arsclib()
    classes = compile_shim(jar)
    decoded = decode_tree(jar, classes, apk)
    scratch = reset_scratch(decoded)
    replay(scratch)
    encode_proof(jar, classes, scratch)
    print("\npass: the bundle's resource writes survive a full arsclib table rebuild")


if __name__ == "__main__":
    main()
