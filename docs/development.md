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
| [`gboard-settings-ui.md`](gboard-settings-ui.md) | How rows are added to Gboard's settings screens, and how its own glide rows are greyed out |
| [`../tools/apk/`](../tools/apk/README.md) | The DEX and binary-XML readers everything above was found with |

The pinned APK is `com.google.android.inputmethod.latin_17.7.7.932364120-release-arm64-v8a`, which
is what every finding above was read from.

## Layout

| | |
|---|---|
| `patches/` | Kotlin patches — the bytecode and resource changes applied to Gboard |
| `extensions/extension/` | Java code compiled to a DEX and merged into the patched APK |
| `patches/src/main/kotlin/util/PatchListGenerator.kt` | Builds `patches-list.json` from the built bundle |
| `.github/workflows/release.yml`, `.github/scripts/check_version.sh`, `tools/bump` | The release pipeline — see [`releasing.md`](releasing.md) |
| `.github/scripts/generate_patches_readme.py` | Injects the patches table into the README at release time |
| `patches-bundle.json` | Source metadata Morphe reads straight from the branch. **Generated** |
| `patches-list.json` | Published inventory. **Generated** |
| `CHANGELOG.md` | In-app changelog Morphe fetches from the branch root. **Generated** |

Patches run at patch time and can only manipulate bytecode and resources. The extension runs on the
device inside Gboard. A patch reaches the extension by emitting an `invoke-static` to a descriptor —
get that descriptor wrong and the failure surfaces at patch time, far from the cause, which is what
[`register-encoding.md`](register-encoding.md) is about.

## Upstream

This repository was created from
[`MorpheApp/morphe-patches-template`](https://github.com/MorpheApp/morphe-patches-template). GitHub's
template instantiation does **not** share git ancestry, so there is no merge base and never will be:
`git pull` from upstream is not available at any price. Picking up a template improvement means
comparing a file and porting the change by hand.

```bash
git remote add template https://github.com/MorpheApp/morphe-patches-template.git   # once
git fetch template
git diff template/main -- gradle/libs.versions.toml
```

Which makes the list below the useful thing: an empty diff for anything in the first group means
there is nothing to port.

**Upstream's — keep byte-identical.** These are deliberately untouched so a future comparison is a
clean yes/no. `gradle/libs.versions.toml` is the busiest file in the template's history, and
`PatchListGenerator.kt` had a two-line comment reworded once and reverted for exactly this reason.

| | |
|---|---|
| `gradle/libs.versions.toml`, `gradle/wrapper/`, `gradlew`, `gradlew.bat` | Build tooling and dependency versions |
| `.github/scripts/generate_patches_readme.py` | README patches table |
| `patches/src/main/kotlin/util/PatchListGenerator.kt` | Builds `patches-list.json` |
| `NOTICE`, `LICENSE`, `.editorconfig`, `.gitattributes` | Legal and formatting |
| `.github/ISSUE_TEMPLATE/config.yml`, `extensions/extension/src/main/AndroidManifest.xml` | Untouched by chance, worth keeping so |

**Ours — never take upstream's.** The release pipeline is a deliberate departure (see
[`releasing.md`](releasing.md)); no upstream change makes semantic-release acceptable here, so its
version of `release.yml` is never the answer, and neither is its README, which is setup instructions
for a fresh template.

`release.yml` · `.github/scripts/check_version.sh` · `compare_versions.py` ·
`check_shared_constants.py` · `tools/bump` · `tools/apk/` · `docs/` · `README.md` ·
everything under `patches/src/main/kotlin/dev/jz6/` and `extensions/.../dev/jz6/`

**Shared — the template expects these to be edited**, so a diff against upstream is signal, not
noise: `gradle.properties` (version), `settings.gradle.kts` (project name, plugin pin),
`patches/build.gradle.kts` (group, `about`), `extensions/extension/build.gradle.kts` (namespace),
the two issue templates (links), `.github/dependabot.yml`, `.gitignore`.

Of those, `settings.gradle.kts` is the one to watch: it carries the `app.morphe.patches` plugin
version, which upstream bumps and this project should follow.

**Generated, never hand-edited:** `patches-bundle.json`, `patches-list.json`, `CHANGELOG.md`, and
the `README.md` block between the `PATCHES_START` and `PATCHES_END` markers.

## Building

Needs JDK 21, the Android SDK, and credentials for the Morphe package registry:

```bash
printf 'gpr.user=<github-username>\ngpr.key=<PAT with read:packages>\n' >> ~/.gradle/gradle.properties

./gradlew buildAndroid
```

The bundle lands at `patches/build/libs/patches-*.mpp`, and can be applied with
[Morphe Desktop](https://github.com/MorpheApp/morphe-desktop) like any other patch bundle.

Put the credentials in `~/.gradle/gradle.properties`, **never** in the repository's own
`gradle.properties` — that file is tracked, because its `version` line is what triggers a release.
CI supplies the same values from `GITHUB_ACTOR` / `GITHUB_TOKEN`, which `settings.gradle.kts`
falls back to.

## Releasing

Bumping `version` in `gradle.properties` **is** the release. CI sees a version with no matching tag,
builds the bundle, writes `patches-bundle.json`, tags, and publishes. `tools/bump 1.0.1-dev.1` does the
same thing with the checks run before the push rather than after it.

The branch is the channel: `dev` publishes a pre-release, `main` a stable one. That is not a
convention — Morphe resolves a custom source by rewriting the branch segment of the
`patches-bundle.json` URL, and those two branch names are compile-time constants in the manager.

[`releasing.md`](releasing.md) is the full account, including the three ways a release fails
*silently*. Worth reading once before cutting one, because none of the three reports an error
anywhere.

Commit subjects are copied verbatim into the release notes, so write them for users.

## Testing without a stable release

Push to `dev` as often as you like: an ordinary push compiles and publishes nothing, so it is a free
compile check. When you want something installable, bump to a new version — Morphe Manager will
offer the `.mpp` once **pre-release** is enabled on the patch source. No throwaway tags, no
downloading artifacts by hand.

## Which Gboard resources a patch can address

`ResourcePatchContext.document(path)` returns a decoded W3C DOM, and the path is the resource's
*decoded* name — `res/xml/settings.xml`, never the packed `res/B_o.xml`. That only works for
resources whose name survived: Gboard is built with aapt2 `--collapse-resource-names`, and 32,668
of its 33,287 entries report `0_resource_name_obfuscated`.

The survivors are the ones Android itself resolves by name at runtime. For `xml` that is 33
resources — the settings screens plus `method`, `file_provider_paths` and `spell_checker`. The
keyboard layouts are **not** among them, so `res/aDh.xml` has no clean name to address it by.

The practical consequence: patches that touch the settings screens or the manifest are
straightforward, and anything wanting to change a keyboard layout should prefer a bytecode patch
over a resource one. The id ↔ name ↔ path table is in
[`gboard-bindings.md`](gboard-bindings.md), and [`../tools/apk/arsc.py`](../tools/apk/README.md)
regenerates it.

## Supporting a new Gboard

`COMPATIBILITY_GBOARD` pins the bundle to one build, so a newer Gboard is refused rather than
mispatched. Moving to a new version means re-deriving the obfuscated names in the bindings and the
resource ids against that APK, then updating the pin. [`glide-detection.md`](glide-detection.md)
documents the method and is the worked example to copy.
