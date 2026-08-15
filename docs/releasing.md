# Releasing

Versions are chosen by hand. Nothing is computed from commit messages or tags, which means a
history rewrite cannot change what the next version will be.

## Cutting a release

**Bumping the version is the release.**

```bash
tools/bump 1.0.1-dev.1
```

That edits `version` in `gradle.properties`, shows you what will be published, and pushes once you
agree. CI does the rest: build, `patches-bundle.json`, tag, publish.

Or do it by hand — edit the line, commit, push. `tools/bump` only runs the same checks CI runs,
before the push rather than after it, because the push is the point of no return.

Ordinary pushes compile and stop. Only a version with no matching tag releases, so commit to `dev`
as often as you like.

### From a phone

Edit the `version` line in `gradle.properties` on github.com and commit to `dev`. Same trigger, no
laptop. The **Run workflow** button takes no input — it re-evaluates the same rule, which is for
re-running a run that failed partway.

### The rule

> `gradle.properties` says `1.0.1-dev.1` **and** no `v1.0.1-dev.1` tag exists → release it.

Deliberately not "the file changed in this commit". This version is idempotent: amends,
force-pushes, re-runs and several commits in one push all behave, and re-running on an
already-released version does nothing instead of publishing a duplicate.

### Why `gradle.properties`

Because it has to be right anyway — the built file is named `patches-<version>.mpp` and
`download_url` is built from the same string. Any other home for the version would still need
`gradle.properties` kept in step with it, which is the drift this design exists to remove.
`patches-bundle.json` is the obvious alternative and the wrong one: it is generated, its
`created_at` must be the release time, and hand-editing it invites exactly the silent failure
described below.

## The branch is the channel

| Run it from | Publishes | Morphe users see it when |
|---|---|---|
| `dev` | pre-release | they enable pre-releases on the source |
| `main` | stable | always |

This is not a convention, it is how the manager works. A custom source is a single URL —
`raw.githubusercontent.com/<owner>/<repo>/<branch>/patches-bundle.json` — and the pre-release toggle
**rewrites the branch segment** of it. `BRANCH_STABLE = "main"` and `BRANCH_DEV = "dev"` are
compile-time constants in `morphe-manager`, so no other branch name works, and the workflow refuses
to release from one.

With pre-releases enabled the manager fetches **both** branches and keeps whichever `version` is
higher, ties going to `dev`.

## Choosing the version

Pre-releases on `dev` are `MAJOR.MINOR.PATCH-dev.N` — `1.0.1-dev.1`, `1.0.1-dev.2`, and so on.
Stable releases on `main` drop the suffix entirely.

**The dot before the counter is load-bearing.** Morphe orders two pre-releases by parsing the final
dot-segment as a number, and falls back to a plain string comparison when it will not parse. So
`-dev.10` parses as ten and correctly beats `-dev.9`, while `-pr10` yields the string `"0-pr10"` —
under which `1.0.0-pr9` outranks `1.0.0-pr10` and the tenth pre-release is silently never offered.
`check_version.sh` rejects any suffix that is not `-<dev|beta|rc|alpha|preview>.<number>`.

**A pre-release always ranks below the stable version of the same base**: `1.0.0-dev.1 < 1.0.0`.
Two consequences worth internalising:

- Once `main` ships `1.0.0`, no `1.0.0-dev.N` can ever be seen again. Move the base up to
  `1.0.1-dev.1` rather than continuing the counter.
- Cutting a stable release is therefore just bumping `dev` to the bare version and fast-forwarding
  to `main`, which then outranks every pre-release that preceded it.

`1.0.0-pr0` predates this rule. It orders correctly against stable versions so it stays published,
but it gets no `-pr1`: the next pre-release is `1.0.1-dev.1`, because `1.0.0-dev.1` would rank
*below* the `1.0.0-pr0` already out there.

## What Morphe actually reads

Only `patches-bundle.json`. Not the tag, not the GitHub release, not `patches-list.json`, not the
`prerelease` checkbox — those exist for people. The GitHub release is merely where the `.mpp` file
happens to live; the JSON is what announces it.

```json
{
  "created_at": "2026-08-15T06:01:42",
  "description": "## 1.0.1-dev.1\n\n- feat: a thing",
  "download_url": "https://github.com/OWNER/REPO/releases/download/v1.0.1-dev.1/patches-1.0.1-dev.1.mpp",
  "signature_download_url": "",
  "version": "1.0.1-dev.1"
}
```

`download_url`, `version`, `description` and `created_at` are all required — a missing one fails
deserialisation in the manager with no useful error. **`created_at` must carry no timezone**: it is
parsed as a kotlinx `LocalDateTime`, so a trailing `Z` or an offset breaks it.

## The three failure modes this guards against

Each of these fails *silently*, which is why the workflow checks rather than trusts.

**A version that isn't higher than `main`'s.** Publish `0.0.1` from `dev` while `main` is on `0.0.2`
and nobody ever sees it — the manager fetches both and serves the higher one. The workflow compares
against `main`'s `patches-bundle.json` and refuses.

That comparison runs through [`compare_versions.py`](../.github/scripts/compare_versions.py), a port
of the manager's own `compareVersions`, because ordering pre-releases is exactly where an
approximation goes wrong. `sort -V` — the obvious choice, and what this used at first — ranks
`1.0.0-dev.1` *above* `1.0.0`, so it would have approved a dev release that Morphe then refuses to
serve: the guard failing at the one job it exists for. The port carries a self-test of cases checked
against the manager, and the guard runs it before trusting it.

**A version that doesn't agree with itself.** `download_url` is built from the version, so the tag
must be `v<version>`, the asset must be `patches-<version>.mpp`, and the JSON must say the same.
The workflow writes all three from one input and asserts the built file is named as expected, so
they cannot drift.

**A tag left behind by a rewrite.** Nothing reads tags to *decide* a version any more — they only
answer "have I already shipped this exact string" — so the worst a rewrite can do is make CI attempt
a version that already exists, which fails loudly. The commit and the tag are still pushed in one
command so they cannot separate.

None of this relies on you remembering to check. After publishing, the workflow re-fetches
`patches-bundle.json` **from the raw URL a phone would hit** (retrying, because the CDN lags),
asserts the four required fields and the timezone-less `created_at`, downloads `download_url`, and
compares the bytes against the bundle it just built. Any mismatch fails the run.

## The version rules live in one place

`.github/scripts/check_version.sh` — the branch check, the format, the pre-release shape, the
tag-already-exists check and the must-beat-`main` comparison, the last of which it delegates to
`.github/scripts/compare_versions.py`. Both `tools/bump` and the workflow call it, so there is a
single definition rather than two that drift. It reads only and changes nothing, so it is safe to
run whenever you want to know if a version is usable:

```bash
.github/scripts/check_version.sh 1.0.1-dev.1 dev
.github/scripts/compare_versions.py --selftest      # does the port still match the manager?
```

## Things worth knowing

- The raw CDN caches for a few minutes and the custom-source path does not cache-bust, so expect a
  short lag between the workflow finishing and Morphe noticing.
- `download_url` is fetched unauthenticated. A private repository fails at download even though the
  listing may appear to work.
- `gradle.properties`'s `version` line is owned by the workflow. Editing it by hand does nothing —
  it is overwritten from the input on every release.

## Why not semantic-release

The template ships it, and it is a good default: it computes the version from `feat:`/`fix:` commits
and tags the result. It was removed because it derives the previous version by walking **tags
reachable from HEAD**. Rewrite any commit — rebase, amend, redate, change an author — and the tags
after it point at SHAs no longer in the branch, so it concludes the project has never been released
and starts again at `1.0.0`. It does not warn.

That cost the previous repository three times in one afternoon, the last of which published a
`v1.0.0-dev.1` over a project whose previous release was `v0.1.0-dev.8`. Choosing versions by hand
removes the coupling entirely.

The template advises modifying `release.yml` and `.releaserc` rather than writing a new release
script. That advice assumes semantic-release is computing the version, which is the one thing being
removed here — so this is a deliberate departure, not an oversight.
