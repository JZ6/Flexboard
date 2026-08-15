#!/usr/bin/env bash
#
# Is this version releasable from this branch?
#
# Called by release.yml before it builds anything, and by tools/bump before it commits anything, so
# there is one definition of "valid" rather than two that drift apart.
#
#   check_version.sh 0.0.2 dev
#
# Exits non-zero with an explanation if not. Reads only; changes nothing.

set -euo pipefail

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
            NEWEST=$(printf '%s\n%s\n' "$VERSION" "$STABLE" | sort -V | tail -1)
            if [ "$VERSION" = "$STABLE" ] || [ "$NEWEST" != "$VERSION" ]; then
                fail "main is on $STABLE, so a dev release of $VERSION would never be offered — Morphe keeps the higher of the two channels."
            fi
        fi
    fi
fi

printf 'v%s is releasable from %s.\n' "$VERSION" "$BRANCH"
