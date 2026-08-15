#!/usr/bin/env python3
"""Compare two version strings the way Morphe Manager does.

Prints -1, 0 or 1 — v1 is lower than, equal to, or higher than v2. That ordering
is the only one that matters: it is what the manager uses to pick between the
`dev` and `main` bundles when a user has pre-releases enabled, so a version this
says is lower is a version nobody will ever be offered.

This is a port of `compareVersions` in morphe-manager's
`app/src/main/java/app/morphe/manager/util/VersionUtils.kt`. **Keep it a port.**
The standing temptation is to correct the parts that are not semver; the whole
value here is agreeing with the manager, including where it is odd.

`sort -V` is not a substitute. It ranks `1.0.0-dev.1` *above* `1.0.0`, where the
manager ranks it below — so it would wave through precisely the dev release that
Morphe then refuses to serve, which is the silent failure this guards.

    compare_versions.py 1.0.1-dev.1 1.0.0   ->  1
    compare_versions.py --selftest          ->  silent, or exits 1 on drift
"""

import re
import sys

_KEYWORD = re.compile(r"^([\d.]+)[-._]?(dev|beta|rc|alpha|preview)", re.IGNORECASE)
_NUMERIC = re.compile(r"^([\d.]+)(.*)$")
_INTEGER = re.compile(r"^[+-]?\d+$")


def _normalize(version):
    """Strip a leading `v` and surrounding whitespace, as the manager does."""
    version = version.strip()
    return version[1:] if version.startswith("v") else version


def _parts(version):
    """Split into (base, pre_release), where pre_release is None for a stable version."""
    match = _KEYWORD.match(version)
    if match:
        return match.group(1), match.group(2)

    # No recognised keyword: anything trailing the numeric part still counts as a
    # pre-release marker, which is why `1.0.0-pr0` sorts below `1.0.0` correctly
    # even though `pr` is not one of the five keywords.
    match = _NUMERIC.match(version)
    if match and match.group(2):
        return match.group(1), match.group(2)

    return version, None


def compare(v1, v2):
    if v1 is None and v2 is None:
        return 0
    if v1 is None:
        return -1
    if v2 is None:
        return 1

    v1, v2 = _normalize(v1), _normalize(v2)
    if v1 == v2:
        return 0

    base1, pre1 = _parts(v1)
    base2, pre2 = _parts(v2)

    nums1 = [int(p) if p.isdigit() else 0 for p in base1.split(".")]
    nums2 = [int(p) if p.isdigit() else 0 for p in base2.split(".")]
    for i in range(max(len(nums1), len(nums2))):
        a = nums1[i] if i < len(nums1) else 0
        b = nums2[i] if i < len(nums2) else 0
        if a != b:
            return -1 if a < b else 1

    # Same base. A stable version outranks any pre-release of it.
    if pre1 is None and pre2 is None:
        return 0
    if pre1 is None:
        return 1
    if pre2 is None:
        return -1

    # Both pre-releases. The manager compares the final dot-segment numerically so
    # that dev.9 < dev.10 — but only when that segment parses as a number, which
    # needs the counter to be its own segment. `-dev.10` yields "10"; `-pr10`
    # yields "0-pr10", which falls back to a string compare where "9" > "1".
    tail1, tail2 = v1.rsplit(".", 1)[-1], v2.rsplit(".", 1)[-1]
    if _INTEGER.match(tail1) and _INTEGER.match(tail2):
        n1, n2 = int(tail1), int(tail2)
        return 0 if n1 == n2 else (-1 if n1 < n2 else 1)
    return 0 if v1 == v2 else (-1 if v1 < v2 else 1)


# Each case was confirmed against the manager's own algorithm. The inverted pair is
# recorded deliberately: it is the reason the dot in `-dev.N` is not cosmetic.
_CASES = [
    ("1.0.0-dev.1", "1.0.0", -1),      # a pre-release never outranks its stable
    ("1.0.0", "1.0.0-dev.1", 1),
    ("1.0.0-dev.9", "1.0.0-dev.10", -1),  # works *because* of the dot
    ("1.0.0-pr9", "1.0.0-pr10", 1),    # INVERTED — no dot, so a string compare
    ("1.0.0-dev.1", "1.0.0-pr0", -1),  # why 1.0.1-dev.1 had to follow 1.0.0-pr0
    ("1.0.1-dev.1", "1.0.0-pr0", 1),
    ("0.0.1", "1.0.0-pr0", -1),
    ("1.0.0", "1.0.0", 0),
    ("v1.2.0", "1.2.0", 0),            # a leading v is stripped
    ("1.10.0", "1.9.0", 1),            # numeric, not lexical
]


def _selftest():
    failures = [
        f"  compare({a!r}, {b!r}) = {compare(a, b)}, expected {want}"
        for a, b, want in _CASES
        if compare(a, b) != want
    ]
    if failures:
        print("::error::compare_versions.py no longer matches Morphe Manager:", file=sys.stderr)
        print("\n".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--selftest":
        sys.exit(_selftest())
    if len(sys.argv) != 3:
        print(__doc__.strip().splitlines()[0], file=sys.stderr)
        print("usage: compare_versions.py <v1> <v2> | --selftest", file=sys.stderr)
        sys.exit(2)
    print(compare(sys.argv[1], sys.argv[2]))
