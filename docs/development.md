# Development

Everything needed to build, test and release Flexboard.

Alongside it: [`design.md`](design.md) is why Flexboard behaves the way it does — the reasoning
behind the defaults and the choices the README only states — and [`roadmap.md`](roadmap.md) is what
might come next.

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

The pinned APK is `com.google.android.inputmethod.latin_18.0.3.954559732-release-arm64-v8a`, which
is what every finding above was read from.

## Layout

| | |
|---|---|
| `patches/` | Kotlin patches — the bytecode and resource changes applied to Gboard |
| `extensions/extension/` | Java code compiled to a DEX and merged into the patched APK |
| `stubs/` | Signatures of the Gboard and AndroidX types the extension compiles against |
| `extension-check/` | Compiles the extension's sources with javac against `stubs/`, so the SDK is not needed to typecheck them |
| `driver/` | Applies a built bundle to an APK locally, end to end, without Morphe Manager |
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

### Registers carry a type, and it is asserted

A register handed to an emitted `invoke` has to actually hold what the callee declares, and nothing
in the toolchain checks that for you: smali assembles any register number, D8 does not type-check
across an injection, and CI builds the bundle without ever applying it. `0.0.1-dev.1` passed the IME
where a `Context` was required. It assembled, failed ART's verifier on the device, and took the
whole event dispatcher — so the keyboard never appeared.

Patches therefore pair a register with the type it is *proven* to hold, as a `TypedRegister`, and
assert against the callee before emitting. `patches/src/main/kotlin/.../shared/Types.kt` has the
helpers and the full reasoning. Two limits are worth knowing up front:

- **It proves wrongness, never correctness.** Framework classes are not in the APK's dex, so a chain
  that escapes into `android.*` is unknowable and passes silently. Only a chain that resolves
  entirely to `Object` without reaching the target can fail — which is the case these patches keep
  landing in, and the one `dev.1` was in.
- **It fires when someone patches, not when CI builds.** Patches execute inside Morphe, so this
  turns a bricked keyboard into a refused patch with a precise message. It is emphatically *not* a
  release gate: a bundle carrying a broken patch still builds, tags and publishes clean.

## Upstream

This repository was created from
[`MorpheApp/morphe-patches-template`](https://github.com/MorpheApp/morphe-patches-template). GitHub's
template instantiation does **not** share git ancestry, so there is no merge base and never will be:
`git pull` from upstream is not available at any price. Picking up a template improvement means
comparing a file and porting the change by hand.

The history rewrite of 2026-08-17 made that doubly true: every commit here was re-hashed, so even
the root commit no longer matches upstream's by object id. File comparison is the only mechanism.

```bash
git remote add template https://github.com/MorpheApp/morphe-patches-template.git   # once
git fetch template

# Sort every template file into identical / differing / absent-here.
git ls-tree -r template/main --name-only | while read -r f; do
    if   [ ! -e "$f" ];                                       then echo "absent    $f"
    elif git diff --quiet template/main -- "$f" 2>/dev/null;  then echo "identical $f"
    else                                                           echo "differs   $f"; fi
done | sort
```

Which makes the four lists below the useful thing. Run the sweep, then read each name off them:
anything `identical` in the first group needs nothing; anything `differs` or `absent` should already
be accounted for by the second, third or fourth. **A name the lists do not mention is the only real
output** — that is a template change nobody has triaged yet.

As of the last sweep the counts were 13 identical, 13 differing and 9 absent, and every one of the
22 non-identical files is named below.

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

**Absent on purpose — do not restore.** These exist upstream and are missing here by decision, which
a sweep cannot distinguish from an oversight. That is exactly the trap this group exists to close: a
sync that "helpfully" puts them back would reintroduce the release pipeline this project removed.

| | |
|---|---|
| `.releaserc`, `package.json`, `package-lock.json` | semantic-release config. See [`releasing.md`](releasing.md) for why versions are chosen by hand |
| `.github/workflows/open_pull_request.yml` | Opens a dev→main PR. Stable is promoted by fast-forward so the two branches keep identical history; a merge commit would break that |
| `patches/.../app/template/patches/example/*.kt`, `extensions/.../app/template/.../ExamplePatch.java`, `patches/.../app/template/patches/shared/Constants.kt` | Template examples, replaced by the `dev/jz6/` tree |

One deletion is easy to miss because it is a *fragment* rather than a file: `patches/build.gradle.kts`
upstream ends with a `publish { dependsOn("generatePatchesList") }` block, and it is gone here.
Restoring it re-runs `jar` after `buildAndroid` has merged `classes.dex` into it, which silently
produces a bundle that offers zero patches on device — the bug that shipped in v0.0.0 through
v1.0.0.

### Sync log

Kept because "is this file behind, or did we change it on purpose?" is unanswerable from the diff
alone once a few months have passed.

| When | Taken | Notes |
|---|---|---|
| 2026-08-17 | Gradle wrapper 9.6.1 → 9.7.0 (`gradle-wrapper.jar`, `.properties`, `gradlew`, `gradlew.bat`) | The only genuine delta. `libs.versions.toml` was already byte-identical and the `app.morphe.patches` pin already matched at 1.3.3, so patcher and plugin needed nothing. All 13 differing files were confirmed deliberate. |

## Building

Credentials for the Morphe package registry are needed for anything at all — the
`app.morphe.patches` plugin resolves from GitHub Packages, so without them Gradle cannot even
configure the build:

```bash
printf 'gpr.user=<github-username>\ngpr.key=<PAT with read:packages>\n' >> ~/.gradle/gradle.properties
```

The PAT must be a **classic** token; GitHub Packages' Maven registry rejects fine-grained ones.
`read:packages` is the only scope it needs.

### Type-checking the patches — JDK 21, and nothing else

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :patches:compileKotlin
```

**This needs no Android SDK**, despite `:extensions` being in the build, and it is the check worth
running constantly: about half a second once warm.

Run it before every push. Two releases have been burned on things it catches in one line — a
`$_` inside a regex read as a string template, and a callable reference whose variance was being
taken on trust. Neither was visible to any other check in this project, and both cost a full
release cycle to discover.

### Building the bundle — needs the Android SDK

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew buildAndroid
```

The bundle lands at `patches/build/libs/patches-*.mpp`, and can be applied with
[Morphe Desktop](https://github.com/MorpheApp/morphe-desktop) like any other patch bundle. The
extension is an Android library, so this half does need an SDK — but note that the SDK buys
*packaging*, not verification: it still never applies the bundle to an APK. If you cannot install
one, `:patches:compileKotlin` plus [`../tools/apk/preflight.py`](../tools/apk/README.md) covers
everything CI would have told you, and CI builds the bundle anyway.

### What each check can and cannot see

| | catches | blind to |
|---|---|---|
| `:patches:compileKotlin` | syntax, types, unresolved references | anything about Gboard |
| `:extension-check:compileJava` | extension Java (syntax, arity, imports) with plain javac, no SDK — the extension's own sources plus hand-written android.* stubs | android.jar-only surface beyond the stubs; behaviour |
| `.github/scripts/check_shared_constants.py` | patch↔extension constant drift, settings-row coverage | values only referenced once |
| `.github/scripts/check_dex_pins.py` | an obfuscated Gboard class literal with no preflight anchor | members (preflight pins those structurally per anchored class) |
| `.github/scripts/check_emission_lint.py` | smali block structure (trailing/dangling labels, const width) | interpolated values — those are computed at patch time |
| `tools/apk/preflight.py` | bindings that moved or changed shape | Kotlin that does not compile; behaviour |
| `tools/apk/check_patch_resources.py` | resource write/merge/encode failures, with arsclib itself | dex; needs the target APK, so it is local-only |
| `./gradlew :driver:run --args="gboard.apk <bundle>.mpp out.apk"` | the whole pipeline, executed for real — the only gate that *runs* the patches. Needs a built bundle (any released/CI one); with an SDK installed, `patches/build/libs/*.mpp` works too | the artifact is unsigned and lacks the merged extension dex — it proves the pipeline, it is not for installing |
| Morphe + a device | everything else | nothing — but it is the slowest loop |

They are three different axes, and no two of them substitute for each other. `0.0.1-dev.1`
compiled and had correct bindings and still bricked the keyboard; `0.0.3-dev.1` compiled, had
correct bindings, applied cleanly, and silently called the wrong method.

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

`COMPATIBILITY_GBOARD` pins the bundle to one build, so a different Gboard is refused rather than
mispatched. Moving to a new version means re-deriving the obfuscated names and the resource ids
against that APK, then updating the pin.

The 17.7.7 → 18.0.3 move is the worked example, and the mapping it produced is in
[`gboard-bindings.md`](gboard-bindings.md). What it taught, in order of how much time it saves:

1. **Diff the two builds, do not re-derive from scratch.** Almost everything survives a version
   bump — same field letters, same method shapes, often identical instruction counts. Keep the old
   dex extracted alongside the new one and match classes structurally: a class whose field letters,
   types and method signatures all line up *is* the same class. Every rename in that table was
   found this way, and each one was unique.
2. **Run [`../tools/apk/preflight.py`](../tools/apk/README.md) early and often.** It re-checks every
   patch assertion against the dex, which is the only thing standing between a compiled bundle and
   a device. Point it at the *old* dex once too: if it does not fail there, it is not really
   checking anything.
3. **Never carry a field letter across a build.** Gboard 18 inserted a field into `AbstractIme`,
   shifting everything from `C` down one — so the suppression flag moved `N:Z` → `O:Z` while `N:Z`
   continued to exist as an unrelated boolean. That assembles, verifies, and silently tests the
   wrong field; no type assertion can catch it. Pin such fields by behaviour instead (this one by
   its read count in the dispatcher), and confirm the letter rather than assuming it.
4. **Re-derive register arguments, do not shift them.** `r()` gained a constructor argument, which
   moved the scratch registers *and* made two previously-dead ones live. A blind +1 would have
   written a String into a reference parameter.
5. **Resource ids always move.** Resolve them by value through `arsc.py`'s `find_value`, since the
   names are collapsed.

[`glide-detection.md`](glide-detection.md) documents the original derivation method for a binding
that has no structural twin to match against.
