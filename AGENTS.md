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

**Morphe keys patch selection by name.** Renaming a user-facing patch resets anyone who had
deselected it back to the default.
