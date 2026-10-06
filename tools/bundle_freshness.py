#!/usr/bin/env python3
"""Is this patch bundle built from the code in front of you?

A bundle is a build artifact. Applying one proves something about the commit it was built from, and
nothing whatever about the working tree -- but the driver lane reports PASS either way, and a stale
bundle passing looks exactly like a current one passing.

That is not a hypothetical. The driver lane ran green for a whole session against the 2.5.0-dev.2
bundle while the tree carried a tightened guard that bundle knew nothing about. The guard's first
contact with a matching bundle failed immediately.

Three questions, in order of how badly they catch you:

1. Does the bundle's version match `gradle.properties`? Catches the obvious case -- last release's
   bundle against this release's tree.
2. Does its source commit have the same patch-source tree as HEAD? A build that finished after
   another local commit can still have been built *from* an older commit. Compare the source trees,
   not just the wall clocks. Bundles without a source commit cannot prove their provenance.
3. Are those sources clean? An uncommitted edit cannot be in any bundle, so the answer is no before
   it is asked.

Exit 0 when the bundle can speak for the tree, 1 with a reason when it cannot. The caller is
expected to skip rather than fail: a bundle that is merely old is not a broken build.
"""

import re
import subprocess
import sys
import zipfile
from pathlib import Path

# Changing any of these changes what a bundle would contain; compare their trees at HEAD and at
# the stamped build commit. `patches-list.json` is deliberately absent: it is generated
# *by* the release, so it is always newer than the bundle it describes.
SOURCE_PATHS = [
    "patches/src",
    "extensions",
    "stubs",
    "gradle/libs.versions.toml",
    # The bundle's MANIFEST is generated from the `about { }` block here, so a changed name or
    # description changes the artifact without touching a line of patch source.
    "patches/build.gradle.kts",
    # Pins the Morphe patches plugin, which decides how the bundle is assembled at all.
    "settings.gradle.kts",
]


def manifest_fields(bundle):
    """`Version` and informational `Timestamp`; only the stamped source SHA proves freshness."""
    try:
        with zipfile.ZipFile(bundle) as z:
            raw = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
    except (OSError, KeyError, zipfile.BadZipFile):
        return None, None
    # Manifest lines wrap at 72 bytes with a leading space on the continuation.
    unwrapped = raw.replace("\r\n", "\n").replace("\n ", "")
    version = re.search(r"^Version:\s*(.+)$", unwrapped, re.M)
    stamp = re.search(r"^Timestamp:\s*(\d+)$", unwrapped, re.M)
    return (version.group(1).strip() if version else None,
            int(stamp.group(1)) // 1000 if stamp else None)


def source_commit(bundle):
    """The source commit written into the bundle by CI, or None for an older bundle."""
    try:
        with zipfile.ZipFile(bundle) as z:
            return z.read("flexboard_source_commit.txt").decode("ascii").strip()
    except (OSError, KeyError, UnicodeError, zipfile.BadZipFile):
        return None


def tree_version():
    """The version `gradle.properties` declares, or `None` when it cannot be read.

    Missing counts as unreadable rather than as an exception, so that running this from the wrong
    directory produces a reason the caller can print instead of a traceback.
    """
    try:
        text = Path("gradle.properties").read_text()
    except OSError:
        return None
    found = re.search(r"^version\s*=\s*(.+)$", text, re.M)
    return found.group(1).strip() if found else None


class GitUnavailable(Exception):
    """git could not answer, which is not the same as git answering "nothing"."""


def _git(*args):
    """git's stdout, or [GitUnavailable] when it failed.

    The return code is checked, and that is the whole point of this function existing. Ignoring it
    turns every failure -- not a repository, a renamed path in [SOURCE_PATHS], git missing entirely
    -- into an empty string, which reads as "no commits touched the sources" and makes this script
    answer "current with the tree". A staleness check whose error path is `fresh` is worse than no
    staleness check, because a skipped lane is visible and a wrongly-passing one is not.
    """
    done = subprocess.run(["git", *args], capture_output=True, text=True)
    if done.returncode != 0:
        raise GitUnavailable((done.stderr.strip().splitlines() or [f"git {args[0]} failed"])[0])
    return done.stdout.strip()


def dirty_sources():
    out = _git("status", "--porcelain", "--", *SOURCE_PATHS)
    # Split on the status field rather than slicing a fixed width: `_git` strips, which eats the
    # leading space of an unstaged ` M path` and takes the first character of the path with it.
    return [line.split(maxsplit=1)[1] for line in out.splitlines() if len(line.split(maxsplit=1)) > 1]


def source_changes(commit):
    """Tracked patch-source paths changed since the bundle commit (does not include dirty edits)."""
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise GitUnavailable(f"invalid source commit '{commit}' in bundle")
    _git("cat-file", "-e", f"{commit}^{{commit}}")
    return _git("diff", "--name-only", commit, "HEAD", "--", *SOURCE_PATHS).splitlines()


def reasons(bundle):
    """Every reason this bundle cannot speak for the tree. Empty means it can."""
    found = []

    version, _built = manifest_fields(bundle)
    if version is None:
        return [f"{bundle} has no readable MANIFEST.MF; it may not be a patch bundle"]

    want = tree_version()
    if want is None:
        found.append("gradle.properties declares no version, so there is nothing to compare against")
    elif version != want:
        found.append(f"bundle is {version} but gradle.properties says {want}")

    stamped = source_commit(bundle)
    if stamped is None:
        found.append("the bundle has no source commit stamp; its build time cannot prove it contains this tree")
    try:
        dirty = dirty_sources()
        moved = source_changes(stamped) if stamped is not None else []
    except GitUnavailable as why:
        # Loudly unknown rather than quietly fine.
        found.append(f"git could not say whether this bundle is current ({why}), so it cannot be trusted")
        return found

    if moved:
        shown = ", ".join(moved[:3]) + (" and more" if len(moved) > 3 else "")
        found.append(f"patch sources differ from the bundle's commit {stamped[:12]}: {shown}")

    if dirty:
        shown = ", ".join(dirty[:3]) + (" and more" if len(dirty) > 3 else "")
        found.append(f"uncommitted changes under the patch sources, so no bundle has them: {shown}")

    return found


def main():
    if len(sys.argv) != 2:
        print("usage: bundle_freshness.py <bundle.mpp>", file=sys.stderr)
        return 2

    found = reasons(sys.argv[1])
    if not found:
        version, _ = manifest_fields(sys.argv[1])
        print(f"  bundle {version} is current with the tree")
        return 0

    for reason in found:
        print(f"  {reason}")
    print("  a bundle only speaks for the commit it was built from")
    return 1


if __name__ == "__main__":
    sys.exit(main())
