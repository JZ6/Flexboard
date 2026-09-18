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
2. Was it built *after* the last commit touching the patch sources? Version is necessary and not
   sufficient: two bundles can both say `2.5.0-dev.3` and differ by every commit in between, which
   is the normal state of affairs while iterating on a release.
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

# Changing any of these changes what a bundle would contain, so a bundle older than the newest
# commit touching them is out of date. `patches-list.json` is deliberately absent: it is generated
# *by* the release, so it is always newer than the bundle it describes.
SOURCE_PATHS = ["patches/src", "extensions", "gradle/libs.versions.toml"]


def manifest_fields(bundle):
    """`Version` and `Timestamp` from the bundle's MANIFEST.MF, or `(None, None)`."""
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


def tree_version():
    text = Path("gradle.properties").read_text()
    found = re.search(r"^version\s*=\s*(.+)$", text, re.M)
    return found.group(1).strip() if found else None


def _git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True).stdout.strip()


def last_source_commit():
    """`(epoch, subject)` of the newest commit touching the patch sources."""
    out = _git("log", "-1", "--format=%ct%x09%s", "--", *SOURCE_PATHS)
    if not out:
        return None, None
    epoch, _, subject = out.partition("\t")
    return int(epoch), subject


def dirty_sources():
    out = _git("status", "--porcelain", "--", *SOURCE_PATHS)
    # Split on the status field rather than slicing a fixed width: `_git` strips, which eats the
    # leading space of an unstaged ` M path` and takes the first character of the path with it.
    return [line.split(maxsplit=1)[1] for line in out.splitlines() if len(line.split(maxsplit=1)) > 1]


def reasons(bundle):
    """Every reason this bundle cannot speak for the tree. Empty means it can."""
    found = []

    version, built = manifest_fields(bundle)
    if version is None:
        return [f"{bundle} has no readable MANIFEST.MF; it may not be a patch bundle"]

    want = tree_version()
    if want and version != want:
        found.append(f"bundle is {version} but gradle.properties says {want}")

    commit_at, subject = last_source_commit()
    if built is None:
        found.append("the bundle's manifest carries no Timestamp, so its age cannot be checked")
    elif commit_at is not None and commit_at > built:
        ago = (commit_at - built) // 60
        found.append(
            f"the patch sources moved {ago} minute(s) after this bundle was built "
            f'-- "{subject[:60]}"')

    dirty = dirty_sources()
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
