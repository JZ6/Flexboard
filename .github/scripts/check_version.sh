#!/usr/bin/env bash
#
# Is this version releasable from this branch?
#
# Called by release.yml before it builds anything, and by tools/bump before it commits anything, so
# there is one definition of "valid" rather than two that drift apart.
#
#   check_version.sh 1.0.1-dev.1 dev
#
# Exits non-zero with an explanation if not. Reads only; changes nothing.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

VERSION="${1:-}"
BRANCH="${2:-}"

fail() {
    # Recognised as an annotation under Actions, and readable anywhere else.
    printf '::error::%s\n' "$*" >&2
    exit 1
}

[ -n "$VERSION" ] || fail "No version given. Usage: check_version.sh <version> <branch>"
[ -n "$BRANCH" ] || fail "No branch given. Usage: check_version.sh <version> <branch>"

# Morphe resolves a custom source by rewriting the branch segment of the patches-bundle.json URL,
# and BRANCH_STABLE = "main" / BRANCH_DEV = "dev" are compile-time constants in the manager. A
# release from any other branch is invisible to it, so it is refused rather than published.
if [ "$BRANCH" != "dev" ] && [ "$BRANCH" != "main" ]; then
    fail "Releasing from '$BRANCH'. Morphe only reads patches-bundle.json from 'dev' (pre-release) or 'main' (stable)."
fi

if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$'; then
    fail "'$VERSION' is not a version. Expected MAJOR.MINOR.PATCH, optionally with a -suffix, and no leading v."
fi

# A pre-release counter must be its own dot-separated segment. The manager orders two
# pre-releases by parsing the final dot-segment as a number, and falls back to a plain string
# compare when it will not parse — under which "1.0.0-pr9" outranks "1.0.0-pr10". The dot is
# load-bearing, and getting it wrong inverts the order silently. See compare_versions.py.
if printf '%s' "$VERSION" | grep -q -- '-'; then
    if ! printf '%s' "$VERSION" | grep -Eq -- '-(dev|beta|rc|alpha|preview)\.[0-9]+$'; then
        fail "Pre-release '$VERSION' must end in -<dev|beta|rc|alpha|preview>.<number>, e.g. ${VERSION%%-*}-dev.1. Without the dot before the number, Morphe orders the tenth pre-release below the ninth."
    fi
fi

if git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null 2>&1; then
    fail "Tag v$VERSION already exists. Versions are chosen by hand, so this is a typo or a repeat."
fi

# With pre-releases enabled Morphe fetches *both* channels and keeps the higher version, ties going
# to dev. So a dev release that does not beat main is never offered to anyone — silently. Fetched
# explicitly rather than trusting a remote-tracking ref to be present; a guard that quietly skips
# itself is worse than no guard.
if [ "$BRANCH" = "dev" ]; then
    git fetch --quiet --no-tags origin main 2>/dev/null || true
    if git cat-file -e "origin/main:patches-bundle.json" 2>/dev/null; then
        STABLE=$(git show origin/main:patches-bundle.json | jq -r '.version // ""')
        if [ -n "$STABLE" ]; then
            # Ordered by a port of the manager's own comparator, not by `sort -V`, which
            # disagrees on exactly the case that matters: sort -V puts 1.0.0-dev.1 above
            # 1.0.0, Morphe puts it below. A guard that disagrees with the thing it guards
            # against is worse than no guard. Its self-test runs first, so a port that has
            # drifted fails loudly instead of quietly approving the wrong version.
            "$SCRIPT_DIR/compare_versions.py" --selftest
            # Morphe breaks an exact tie toward dev, so a tie is not invisible — but shipping
            # one version string on both channels is a mistake regardless, so -le rather than -lt.
            if [ "$("$SCRIPT_DIR/compare_versions.py" "$VERSION" "$STABLE")" -le 0 ]; then
                fail "main is on $STABLE, and Morphe ranks $VERSION at or below it — a dev release of $VERSION would never be offered, because the manager fetches both channels and serves the higher."
            fi
        fi
    fi
fi

printf 'v%s is releasable from %s.\n' "$VERSION" "$BRANCH"
