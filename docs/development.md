# Development

Everything needed to build, test and release Flexboard.

## Reference

Findings about Gboard itself. All of it is derived by hand from one APK and all of it expires
when Gboard updates, so each document records how it was derived, not just what was found.

| | |
|---|---|
| [`gboard-bindings.md`](gboard-bindings.md) | Every obfuscated name this project depends on, and how to re-derive them |
| [`glide-detection.md`](glide-detection.md) | The glide typing preference, and why writing it beats intercepting the read |
| [`register-encoding.md`](register-encoding.md) | Why `pN` in emitted smali produces `Collection is empty`, and the rule that avoids it |
| [`motion-event-handlers.md`](motion-event-handlers.md) | How Gboard attaches gesture handlers, and the built-in scrub delete this rebuild is aiming at |
| [`gboard-settings-ui.md`](gboard-settings-ui.md) | Notes toward greying out Gboard's glide typing row. Not implemented |
| [`../tools/apk/`](../tools/apk/README.md) | The DEX and binary-XML readers everything above was found with |

The pinned APK is `com.google.android.inputmethod.latin_17.7.7.932364120-release-arm64-v8a`, which
is what every finding above was read from.

## Layout

| | |
|---|---|
| `patches/` | Kotlin patches — the bytecode and resource changes applied to Gboard |
| `extensions/extension/` | Java code compiled to a DEX and merged into the patched APK |
| `patches/src/main/kotlin/util/PatchListGenerator.kt` | Builds `patches-list.json` from the built bundle |
| `.releaserc`, `package.json` | The semantic-release pipeline |
| `.github/scripts/generate_patches_readme.py` | Injects the patches table into the README at release time |
| `patches-bundle.json` | Source metadata Morphe reads straight from the branch. **Generated** |
| `patches-list.json`, `CHANGELOG.md` | Published inventory and changelog. **Generated** |

Patches run at patch time and can only manipulate bytecode and resources. The extension runs on the
device inside Gboard. A patch reaches the extension by emitting an `invoke-static` to a descriptor —
get that descriptor wrong and the failure surfaces at patch time, far from the cause, which is what
[`register-encoding.md`](register-encoding.md) is about.

## Building

Needs JDK 21, the Android SDK, and credentials for the Morphe package registry:

```bash
printf 'gpr.user=<github-username>\ngpr.key=<PAT with read:packages>\n' >> ~/.gradle/gradle.properties

./gradlew buildAndroid
```

The bundle lands at `patches/build/libs/patches-*.mpp`, and can be applied with
[Morphe Desktop](https://github.com/MorpheApp/morphe-desktop) like any other patch bundle.

Put the credentials in `~/.gradle/gradle.properties`, **never** in the repository's own
`gradle.properties` — that file is tracked, because semantic-release commits the release version
into it. CI supplies the same values from `GITHUB_ACTOR` / `GITHUB_TOKEN`, which
`settings.gradle.kts` falls back to.

## The release model

Releases are cut by semantic-release from commit messages. Nothing is versioned, tagged or
published by hand.

### Branches

| | |
|---|---|
| `dev` | Where all work happens. Publishes **prereleases** (`0.1.0-dev.1`, `-dev.2`, …) |
| `main` | Publishes **stable** releases. Only ever receives a fast-forward from `dev` |

Any other branch publishes nothing; `release.yml` falls through to a compile check instead.

### Commit types

The version comes from the commits since the last tag:

| Prefix | Effect |
|---|---|
| `feat:` | minor bump, listed under ✨ New Features |
| `fix:` | patch bump, listed under 🐛 Bug Fixes |
| `bump:` | patch bump, listed under 🚀 Updated App Support |
| `perf:` | patch bump, listed under 🔧 Improvements |
| `chore:`, `docs:`, anything else | **no release** |
| `BREAKING CHANGE:` footer | major bump — jumps straight to 1.0.0 from any 0.x version |

Because `release-notes-generator` builds the release body from these, **commit subjects are the
user-facing changelog**. Write them for users, not for yourself.

### Promoting to a stable release

```bash
git push origin dev:main
```

`main` is always an ancestor of `dev`, so this is a fast-forward: no merge commit, no rewritten
SHAs, and `main` stays a literal prefix of `dev`. semantic-release then cuts the stable release and
commits to `main`, and the backmerge plugin fast-forwards that commit back into `dev`.

**Do not use the PR merge button.** GitHub offers merge-commit, squash and rebase — none of which is
a fast-forward. "Rebase and merge" rewrites every SHA, which orphans the tags semantic-release
placed on its own release commits and breaks the next version calculation. Squash is worse. The
auto-opened `dev`→`main` PR is a preview of what is queued, not a button to press; it closes itself
when you push.

Equally: never commit directly to `main`, and never force-push a semantic-release commit.

### What the pipeline writes

A release is not just a tag. In order, the plugins write `patches-bundle.json` (version and
`download_url`), `gradle.properties` (version), `patches-list.json` (regenerated inventory), and the
patches table in `README.md` — then commit all of it as `chore: Release vX [skip ci]` and attach the
`.mpp` to the GitHub release.

That is why none of those files is ever edited by hand. A stale `patches-bundle.json` is the one
release mistake that fails silently: Morphe keeps serving the previous `download_url` and every user
stays pinned to the old version, with no error anywhere.

`generate_patches_readme.py` replaces everything between `<!-- PATCHES_START -->` and
`<!-- PATCHES_END -->` in the README. Keep hand-written prose outside those markers, and do not
remove them — the script exits 1 when they are missing, which aborts the release.

### The version seed

semantic-release has no concept of a 0.x first release: with no tag it ignores the bump type and
emits `1.0.0` outright. A `v0.0.0` tag gives it a base to increment from, after which ordinary
`semver.inc` applies. That tag is the only reason versions start low, so do not delete it.

## Testing without a stable release

Push to `dev`. Any `feat:`/`fix:` there publishes a prerelease with a real `.mpp` attached, which
Morphe Manager will install once **pre-release** is enabled on the patch source. That is the
intended iteration loop — no throwaway tags, no downloading artifacts by hand.

A `chore:` commit publishes nothing but still runs `./gradlew :patches:buildAndroid clean` in CI, so
it is a free compile check.

## Supporting a new Gboard

`COMPATIBILITY_GBOARD` pins the bundle to one build, so a newer Gboard is refused rather than
mispatched. Moving to a new version means re-deriving the obfuscated names in the bindings and the
resource ids against that APK, then updating the pin. [`glide-detection.md`](glide-detection.md)
documents the method and is the worked example to copy. Commit the result as `bump:` so it lands
under 🚀 Updated App Support.
