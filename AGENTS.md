# Working on Flexboard

Rules an agent needs before touching this repo. Each one is here because it was broken, and the
break cost real debugging. Narrative lives in `docs/`; this file is the short form, because the
narrative version already existed on 2026-09-02 and was not read.

## Verify

**Run `tools/gate`.** It is the gate — compile, the three CI scripts, preflight, and the resource
replay. Do not assemble the list yourself; that is how a lane goes missing.

**`263/263 passed` is a statement about Gboard, not about the build.** Preflight and the three
scripts read the *stock* APK to confirm Gboard's bindings have not moved. Only
`check_patch_resources.py` looks at what the bundle produces, and it is the slowest lane, so it is
the one that gets skipped. Never quote a pin count as evidence the output is sound.

**A red lane guards nothing.** If a lane is failing for an unrelated reason, fix it or delete it.
Leaving it red means it is skipped, which means it stays red, which means the next real failure
goes unseen. That is exactly how the toolbar broke.

**Parse your own inputs by their own name.** An assertion that fires on merged output names the
wrong file and sends the reader into someone else's five thousand lines.

**There is no Android SDK here.** `:patches:buildAndroid`, `generatePatchesList` and `:driver:run`
against a locally built bundle cannot run. Static verification is not device verification — say
which one you did.

## Reading what the patcher produced

**`tools/apk/patched.py` reads a patched APK.** Everything else here reads the APK Gboard ships, so
until this existed nothing had ever looked at a class the patcher wrote.

```
tools/apk/patched.py flexboard.apk 'Lpvf;->t(Lpvi;Landroid/view/MotionEvent;I)V' --stock gboard-apk
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

## Reading Gboard's dex

**Use `dis.show(descriptor, dexes)` from `tools/apk/dis.py`.** It is a complete disassembler.

**Do not reason from `dexlib.walk`.** It renders a partial instruction stream. It silently dropped
a `const-wide` and every `if-*` from a method and produced a confident, wrong conclusion about a
clamp. It now refuses rather than dropping, but prefer `dis.show()` regardless.

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

**Toolbar id admission fails silently.** Ids are spliced in as text; a bad fragment throws inside
the patch, Morphe catches it and continues, and the build ships with the allowed set untouched.
Every Flexboard button then disappears at once with nothing in the log naming the cause. Run the
resource lane after touching anything under `patches/src/main/resources/`.

**Morphe never gates on `compatibleWith`** — it is advisory metadata. A patch with `name == null`
is hidden from the patch list; that, not `internal`, is what makes a patch internal.

**A forced Phenotype flag opens a gate, it does not supply what is behind it.** Two of seven
hand-picked flags did anything; one stopped Gboard starting because it fronts a downloaded model and
a version allowlist that a resigned build never receives. Flags ship opt-in until watched working on
a device, and an unproven set ships one patch per flag so a bisect costs installs rather than
releases. See `docs/phenotype-flags.md`.

**Morphe keys patch selection by name.** Renaming a user-facing patch resets anyone who had
deselected it back to the default.
