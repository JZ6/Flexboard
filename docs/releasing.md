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

Read out of the manager rather than inferred, in
`app/src/main/java/app/morphe/manager/domain/bundles/RemotePatchBundle.kt`:

| | |
|---|---|
| `BRANCH_STABLE = "main"`, `BRANCH_DEV = "dev"` | the two constants, no configuration path to either |
| `resolveBranchUrl` | `switchBranchInUrl(url, if (usePrerelease) BRANCH_DEV else BRANCH_STABLE)` — a string swap of the branch segment |
| `supportsPrerelease` | true **only** when the endpoint's branch is literally `main` or `dev`. A source pointed at any other branch loses the toggle entirely rather than failing loudly |
| `getLatestInfo()` | pre-releases on: fetches both in parallel and takes `compareVersions(dev, stable) >= 0 ? dev : stable`. Off: **one** request, to the resolved branch |

### A suffix on `main` is served to everyone

The manager never inspects the version string to decide *whether* to offer a build — only to rank
it. With pre-releases off, `getLatestInfo()` makes a single request to `main` and serves whatever
`version` that JSON carries. `patches-bundle.json` has no `prerelease` field for it to consult;
the branch is the only channel signal that exists.

So `3.0.0-dev.0` on `main` reaches every user who never opted into pre-releases, and the GitHub
release is labelled stable besides, because `release.yml` sets `prerelease` from the branch and
not from the version. It corrects itself at the next stable — `3.0.0` outranks `3.0.0-dev.0` — but
until then the stable channel is serving a dev build.

Nothing upstream prevents this. `tools/promote` refuses a suffixed version, but that is a
convenience wrapper; `check_version.sh`, which is what CI always runs, currently permits it, and
the documented "edit the version line on github.com" path bypasses `promote` entirely. If that
gap matters, close it in `check_version.sh`, not in `promote`.

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

`patches-bundle.json`, and `CHANGELOG.md`. Not the tag, not the GitHub release, not
`patches-list.json`, not the `prerelease` checkbox — those exist for people. The GitHub release is
merely where the `.mpp` file happens to live; the JSON is what announces it.

The bundle JSON carries five fields — `created_at`, `description`, `download_url`,
`signature_download_url`, `version` — and **none of them is a channel flag**. That is the whole
reason the branch has to be the channel: it is the only signal the manager has. Nor does it read
git history, which is why rewriting this branch is safe here in a way the template's warnings
imply it is not (see *Why not semantic-release*).

`CHANGELOG.md` is optional but not inert. The manager derives its URL from the source endpoint by
swapping the filename, parses it for in-app changelog entries, and uses it to *refine* the
"re-patch this app" badge. Two traps follow from that. Its headings must match what
conventional-changelog emits — `# [VERSION](url) (YYYY-MM-DD)`, or a bare `# VERSION (YYYY-MM-DD)`
for a first release — and anything else is silently skipped. And its bullets must be **scoped**,
`* **Gboard:** text`, because the badge check only ever looks at scoped bullets: an unscoped
changelog parses cleanly and then reports no changes for any app, which *suppresses* a badge that
would have shown had there been no changelog at all. The scope has to equal the app display name the
bundle declares, so the workflow reads it back out of `patches-list.json` rather than writing it
twice.

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

## The four failure modes this guards against

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

**A bundle with no `classes.dex`.** The manager loads patches on Android through a DEX class
loader. `buildAndroid` compiles the patches with D8 and merges `classes.dex` **into the jar `jar`
already wrote**, mutating it in place — so any later Gradle run that re-runs `jar` sees a changed
output, rebuilds the archive, and drops the DEX. `generatePatchesList` does exactly that, via
`build`.

A bundle in that state is convincing: it downloads, parses, reports the right name and version, and
contains every patch as a JVM `.class`, so `loadPatchesFromJar` on the JVM enumerates them all and
`patches-list.json` comes out correct. On device it offers **zero patches**. It shipped that way in
v0.0.0 through v1.0.0.

The template only avoided this by ordering: semantic-release ran `generatePatchesList` in its
`prepare` step and `./gradlew publish` in its `publish` step, and the Morphe Gradle plugin hangs
`buildAndroid` off `publish` — so the merge happened last by luck of the lifecycle rather than by
design. The workflow now re-merges after the inventory and asserts `classes.dex` is present before
publishing. **Any new Gradle invocation added after the build must come before that re-merge**, or
it will silently undo it again.

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
