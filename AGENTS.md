# Working on Flexboard

Rules an agent needs before touching this repo. Each one is here because it was broken, and the
break cost real debugging. Narrative lives in `docs/`; this file is the short form, because the
narrative version already existed on 2026-09-02 and was not read.

## Verify

**Run `tools/gate`.** It is the gate — source tests/checks, compile, preflight, driver/verify and resource
replay. Do not assemble the list yourself; that is how a lane goes missing.

**A preflight pin count is a statement about stock Gboard, not the patched build.** Preflight reads
the stock APK; the `.github/scripts` lanes read source. The driver applies the bundle; `verify` reads
its patched dex, while `check_patch_resources.py` replays its resource edits. Never quote a pin
count as evidence the output is sound.

**A red lane guards nothing.** If a lane is failing for an unrelated reason, fix it or delete it.
Leaving it red means it is skipped, which means it stays red, which means the next real failure
goes unseen. That is exactly how the toolbar broke.

**Parse your own inputs by their own name.** An assertion that fires on merged output names the
wrong file and sends the reader into someone else's five thousand lines.

**There is no Android SDK here**, so `:patches:buildAndroid` and `generatePatchesList` cannot run.
**`:driver:run` can.** The SDK is only needed to *build* a bundle; applying one needs nothing but a
JVM, and CI uploads a bundle on **every push**, not only on releases:

```
gh run download --name patches-bundle --dir /tmp/mpp      # any push
FLEXBOARD_BUNDLE=/tmp/mpp/patches-*.mpp tools/gate        # applies it, then verifies it
```

That turns on the driver/verify/diff lanes and a signature guard. `driver` applies the bundle in two selections:
defaults and +crash. `verify` inspects every changed method (including same-size rewrites),
following switch cases for its type check and checking reference access rights. The diff lanes
ensure each opt-in patch actually emits something; the signature guard asserts that the exported
debug provider's shared verifier method stays byte-identical to stock.

**Test a patch change before shipping it, not by shipping it.** Until the artifact step existed the
only way to get a bundle was to cut a release, which is how two builds of a keyboard that would not
open reached people who had selected the patch.

That turns the `driver` lane on, which is the only lane that executes the patches rather than
inspecting them from the outside. It found a real shipped bug within minutes of first being run.

**Know what it covers.** It writes a dex; it does not load one, so ART's verifier never runs:

| Failure | Caught by the driver |
|---|---|
| A fingerprint that matches nothing | yes |
| A patch-time `check`/`require` firing | yes |
| An exception inside a patch | yes |
| Resource splicing that breaks the table | yes |
| **A method rejected at class load** | **no** |
| **Wrong behaviour on a device** | **no** |

The `dev.0`/`dev.1` crash applies cleanly to the driver, but the `verify` lane catches its typed
register merge; `tools/apk/patched.py` is for manual inspection beyond that. Static verification
is still not device verification, so say which one you did.

## Reading what the patcher produced

**`tools/apk/patched.py` reads a patched APK.** `verify.py` also reads patched dex automatically;
before those tools, the gate reasoned entirely from stock Gboard.

```
tools/apk/patched.py flexboard.apk 'Lmm;->run()V' --stock gboard-apk
```

**An emission that produces nothing looks exactly like one that worked.** A helper returning `""`
for its empty case, a `str.replace` that matched nothing, a guard whose condition is never true —
the patch applies, every lane passes, and the build ships unchanged. `2.5.0-dev.1` was a fix for
`dev.0` that emitted zero instructions and was byte-identical to it; three further diagnoses were
argued from the stock dex before anyone looked at the output. **If an emission is supposed to change
something, read the patched method and confirm it did.**

**Never write a fresh liveness walk.** `preflight.live_free` does backward liveness over the real
control-flow graph. A linear scan from an index is wrong in both directions and has now shipped
twice from this repo: once in `assertNotReadBeforeWritten`, once in `handoverFor`, days apart, in
the same file. The second reported every register as available and silently disabled the fix it was
part of.

**An emission runs with its host class's access rights.** Code written into
`ScrubMotionEventHandler` is that class's code, so it may not touch a package-private class or member
in another package, and most of Gboard's obfuscated classes are package-private in the unnamed
package. ART does not reject the class: it loads, the keyboard opens, and the instruction throws
`IllegalAccessError` when it first runs. `2.5.1-dev.7` and `dev.9` shipped that, crashing on every
swipe up. When an emission must reach such a class, widen it in the patch (`setAccessFlags`) and
let `verify` confirm the output.

## Reading Gboard's dex

**Use `dalvik_dis.show(descriptor, dexes)` from `tools/apk/dalvik_dis.py`.** The old `dis.py`
path remains a shim; `import dis` may instead load Python's standard library module. Some rare
opcodes still have family-placeholder names, so check exact opcodes before asserting one.

**Do not reason from `dexlib.walk`.** It yields every instruction, but undecoded operands come back
as `None`; filtering on operands can still silently drop a `const-wide` or an `if-*`. Use
`dalvik_dis.show()` to inspect the full method.

## Changing things

**Commit each fix as it lands**, not in batches. Real timestamps:
`D=$(date '+%Y-%m-%dT%H:%M:%S%z')` with `GIT_AUTHOR_DATE`/`GIT_COMMITTER_DATE`.

**Always `git commit -F <file>`.** Inline `-m` with backticks has been command-substituted and
silently ate commit text more than once.

**Never hand-edit `patches-list.json` or the README block between `PATCHES_START`/`PATCHES_END`.**
Both regenerate during release, and the json says so in its own first key. Editing them creates
churn the generator overwrites.

**Do write the `## <Patch Name>` prose section in the README.** The table only links a row when a
matching heading exists.

**Check the branch before diagnosing anything.** `git rev-parse --abbrev-ref HEAD`. Old branches
predate current `.gitignore` rules and tooling; a surprising `git status` is usually a checkout,
not a defect. Diagnosing the wrong branch has already produced one false alarm about lost hooks.

## Gboard facts that are easy to get backwards

**The toolbar count belongs to the user.** Gboard computes it as `min(pref, capacity)` and
expresses "remove this icon" as *lowering `pref`*. Anything that forces the count upward puts
removed buttons back. Two separate implementations broke this way. Raise the capacity, never the
count, and never write Gboard's own count preference. See `docs/toolbar-capacity.md`.

**Toolbar id admission can fail while Morphe continues.** Ids are spliced in as text; a bad fragment
throws inside the patch, Morphe catches it and continues, and the build ships with the allowed set
untouched. Every Flexboard button disappears; Gboard logs "Invalid access point <id> is added",
but does not explain the failed resource splice. Run the
resource lane after touching anything under `patches/src/main/resources/`.

**Morphe never gates on `compatibleWith`** — it is advisory metadata. A patch with `name == null`
is hidden from the patch list; that, not `internal`, is what makes a patch internal.

**A forced Phenotype flag opens a gate, it does not supply what is behind it.** Two of seven
hand-picked flags did anything; one stopped Gboard starting because it fronts a downloaded model and
a version allowlist that a resigned build never receives. Test each flag separately before grouping
it with other opt-in flags; only features seen working should ship default-on. See
`docs/phenotype-flags.md`.

**Morphe keys patch selection by name.** Renaming a user-facing patch resets anyone who had
deselected it back to the default.

**The resolution helpers take a `ClassLookup`, not a `BytecodePatchContext`, and that is on
purpose.** `ClassLookup` is `(String) -> ClassDef?` — the one thing `findField`, `checkAssignable`,
`checkInvokeKind` and the rest ever needed from the patcher. They used to take the context, and that
single parameter is why none of them had a test for a year: `BytecodePatchContext` is a final class
whose constructor wants a `PatcherConfig` and an APK, so asking "does this find an inherited static
field" first meant producing sixty thousand decoded Gboard classes. Two bugs shipped from that blind
spot — a field lookup that could not see a static, and one that reported "absent" when it had really
left the APK and could not tell.

Do not tidy them back into extension functions. Production reaches them through one-line delegating
overloads on `BytecodePatchContext` at the bottom of `Types.kt` and `Resolve.kt`; a test passes a
map. The delegates are the only uncovered part and `.github/scripts/check_delegates.py` checks their
argument forwarding structurally, because swapping `type` and `target` in one of them compiles, type
checks, and produces a confident wrong failure.

The emitters are a different matter: they need `mutableClassDefBy`, mutable proxies and a dex a
fingerprint can match, which is what `:driver:run` exists for instead.
