# APK inspection tools

Read-only inspection of a Gboard APK in pure Python. No apktool, no aapt, no adb, no JDK — these
exist because every binding in this project has to be re-derived by hand whenever Gboard moves,
and the usual tooling is either unavailable or overkill for "what does this one method do".

The dex and resource research in `docs/` uses these tools (some worked examples predate 18.0.3).

| | |
|---|---|
| `dexlib.py` | DEX reader — strings, types, methods, fields, classes, code items, and a coarse instruction walk that decodes calls, field access and constants |
| `dalvik_dis.py` | Dalvik disassembler on top of `dexlib`, with registers and branch targets. Use this module name rather than stdlib `dis`; `dis.py` is a compatibility path. |
| `axml.py` | Binary XML (AXML) reader — walks elements and attributes of compiled `res/**.xml` |
| `arsc.py` | Resource table reader — resource id to name and value, and the reverse lookup from a packed path back to its id |
| `glyphs.py` | Matches the APK's stripped vector drawables against published Material Icons SVGs, by geometry rather than by name. |
| `preflight.py` | Runs every patch-time assertion against a dex, so a moved binding fails here instead of on a phone |
| `check_patch_resources.py` | Dress rehearsal: mirrors patch-side resource writes onto an arsclib-decoded tree, checks the minimal and maximal settings XML shapes, parses touched files and rebuilds the resource table. It does not rerun all Morphe processors. Cache: `~/.cache/flexboard`. |
| `patched.py` | Manual diff of a patched method; the driver/verify lanes also inspect patched output |
| `verify.py` | Type-merge, extension-reference and access checks over every method a patch changed — the bugs ART only reports on a phone |
| `ArsclibRoundTrip.java` | Java shim used by `check_patch_resources.py` (`decode`/`encode` modes); compiles on demand, needs only the pinned arsclib jar |

## Setup

Extract the DEX files once:

```python
import zipfile
z = zipfile.ZipFile('gboard.apk')
for n in z.namelist():
    if n.endswith('.dex'):
        z.extract(n, '/tmp/gb')
```

Then run from this directory, or add it to `sys.path`.

## Check the patches against a build

```
python3 preflight.py /tmp/gb
```

Re-implements patch assertions against the stock dex and exits non-zero if any fail. Run it first
after a Gboard bump. The driver subsequently applies the bundle and `verify.py` checks patched dex;
none of these substitutes for a device test.

It is a proof of *breakage*, not of correctness — it cannot tell you a patch works, only that every
binding it names is still there and still shaped the way the patch assumes. On a Gboard bump, edit
`BINDINGS` and `EXPECTED` at the top; if a *check* needs rewriting rather than a constant, that is
the signal a patch does too.

## Disassemble a method

```python
import dexlib, dalvik_dis as dis
dl = dexlib.load('/tmp/gb')
dis.show('Lmm;->run()V', dl)
```

```
=== Lmm;->run()V  regs=18 ins=1 outs=8 static=False ===
   492: invoke-virtual         {v0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
   496: invoke-static          {v0, v1}, Lrpv;->a(Landroid/content/Context;Ljava/lang/String;)Z
   499: move-result            v1
```

## List a class

```python
for d in dl:
    for cname, af, cd in d.classes():
        if cname != 'Lmm;':
            continue
        for m, maf, code_off in d.class_methods(cd):
            c = d.code(code_off)
            print(m, c and (c['registers'], c['ins'], c['outs']))
```

## Find callers of a method, or readers of a field

`d.walk(code)` yields `(pc, opcode, mnemonic, operand_text)` for every opcode (unknown operands
are `None`), so a full
sweep is a nested loop. It takes a couple of minutes across ~21,000 classes.

```python
for d in dl:
    for cname, af, cd in d.classes():
        for m, maf, co in d.class_methods(cd):
            c = d.code(co)
            if not c:
                continue
            for pc, op, mn, ref in d.walk(c):
                if ref == 'Lrpv;->a(Landroid/content/Context;Ljava/lang/String;)Z':
                    print(f'{mn} @{pc} in {m}')
```

## Read a compiled layout or preference screen

```python
import zipfile, axml
z = zipfile.ZipFile('gboard.apk')
for depth, tag, attrs in axml.parse(z.read('res/aDh.xml')):
    print('  ' * depth + f'<{tag} ' + ' '.join(f'{k}="{v}"' for k, v in attrs.items()) + '>')
```

Attribute values come back as strings where the file stored a string, and as `@0xRRRRRRRR` where
it stored a resource reference.

## Finding which resource declares something

Resource *names* are stripped from a release build but *values* are not, and references are
stored as little-endian ids. So to find every XML that mentions resource `0x7f140a05`:

```python
import struct
needle = struct.pack('<I', 0x7f140a05)
hits = [n for n in z.namelist()
        if n.startswith('res/') and n.endswith('.xml') and needle in z.read(n)]
```

## Find an icon when every drawable is called `0_resource_name_obfuscated`

Names are stripped, so there is nothing to grep. The geometry is not stripped, and Gboard imports
blocks of Google's own [Material Icons](https://fonts.google.com/icons) unchanged — so download the
reference SVG and match on shape:

```bash
curl -sO https://raw.githubusercontent.com/google/material-design-icons/master/src/content/select_all/materialicons/24px.svg
python3 glyphs.py gboard.apk 24px.svg
#   BUNDLED  24px   0x7f080218 (res/cmc.xml)
```

The two encodings never match as text — Gboard's `M3,5h2L5,3c-1.1,0 -2,0.9 -2,2z` against
Material's `M3 5h2V3c-1.1 0-2 .9-2 2z` — so each path is evaluated to the absolute points it
visits, which both agree on exactly.

The historical inventory matched 2,170 published Material Icons against 496 vector drawables;
29 shapes were found at 35 ids in that pass. Treat a miss as inconclusive: compact SVG arcs and
other shapes may escape `glyphs.py`. The table is in
[`../../docs/gboard-bindings.md`](../../docs/gboard-bindings.md#material-icons-gboard-bundles).
Read it before reaching for an icon — the hit rate is 1.3% and the misses are not the predictable
ones.

To rebuild it after a Gboard bump, fetch the reference set and match the lot:

```bash
curl -s https://api.github.com/repos/google/material-design-icons/contents/src \
  | python3 -c "import json,sys; print(' '.join(e['name'] for e in json.load(sys.stdin)))" \
  | tr ' ' '\n' | while read -r c; do
      curl -s "https://api.github.com/repos/google/material-design-icons/contents/src/$c" \
        | python3 -c "import json,sys; [print('$c/'+e['name']) for e in json.load(sys.stdin)]"
    done > /tmp/mi_index.txt        # ~2,200 icons, 18 category listings

mkdir -p /tmp/mi
(cd /tmp/mi && sed 's#/#|#' /tmp/mi_index.txt | xargs -P 24 -I{} bash -c \
  'IFS="|" read -r c n <<< "{}"
   curl -sf -o "$n.svg" \
     "https://raw.githubusercontent.com/google/material-design-icons/master/src/$c/$n/materialicons/24px.svg" || true')

python3 glyphs.py gboard.apk /tmp/mi/*.svg | grep BUNDLED
```

Three things about that output.

`glyphs.py` prints at most **three ids per icon**, so a glyph bundled four times — `close`, at
`0x7f080211`, `0x7f08058d`, `0x7f08061a` and `0x7f0806af` — reads as three. Group by id yourself if
you need every copy.

Only **496 of the 1,679** drawables are vectors at all; the rest are gradients, shapes and ripples,
which this method cannot see. And a miss is not proof of absence in the other direction either:
most of those 496 are Gboard's own icons, drawn by Google and matching no published set.

A handful of icons have no filled `materialicons` variant to download (39 last time, all battery
and signal-strength indicators), so `curl -f` skips them and they are silently untested. Harmless
for keyboard work, worth knowing before claiming coverage.

Pick the legacy **Material Icons** variant on the site, not Material **Symbols**: the newer set is
drawn to a different geometry and will not match.

## Resolve a resource id, name or packed path

```python
import zipfile, arsc
z = zipfile.ZipFile('gboard.apk')
t = arsc.load(z.read('resources.arsc'))

t.value(0x7f140a05)            # 'enable_gesture_input'
t.name(0x7f170f34)             # 'xml/settings'
t.value(0x7f170f34)            # 'res/B_o.xml'      — the packed path
t.find_value('res/aDh.xml')    # [(0x7f1706ec, 'xml/0_resource_name_obfuscated')]
t.find_name('setting_')        # every settings screen, by name
```

`values()` returns every configuration variant rather than guessing which one applies; `value()`
takes the first, which is right for ids with a single definition.

Most names come back as `0_resource_name_obfuscated` — Gboard is built with aapt2
`--collapse-resource-names`, and only 619 of 33,287 entries keep a real name. Which ones survive,
and why it matters for resource patches, is in
[`../../docs/gboard-bindings.md`](../../docs/gboard-bindings.md).

## Read what the patcher produced

Everything above reads the APK Gboard ships. These two read the output, which until recently
nothing did — and that gap let two releases of a keyboard that would not open get out, followed by
three confident diagnoses from the stock disassembly, at least two of them wrong.

Get a bundle without cutting a release, apply it, and read the result:

```bash
gh run download --name patches-bundle --dir /tmp/mpp        # any push
FLEXBOARD_BUNDLE=/tmp/mpp/patches-*.mpp tools/gate          # driver + verify lanes
```

Or by hand, against an APK Morphe Manager built:

```bash
tools/apk/patched.py flexboard.apk 'Lmm;->run()V' --stock gboard-apk
tools/apk/verify.py  flexboard.apk --changed-from gboard-apk
tools/apk/verify.py  flexboard.apk --unchanged-from gboard-apk 'Lrpv;->a(Landroid/content/Context;Ljava/lang/String;)Z'
```

`patched.py` diffs instructions with absolute branch targets normalized — inserting instructions
renumbers both pcs and their targets. Open the full patched listing for exact targets. It flags a frame that
changed size, and removed instructions, because these emissions insert and none should excise.

`verify.py` propagates register types over the real control-flow graph and reports a conflict that
reaches an instruction requiring a type. `--changed-from` finds every method the patch touched by
diffing against stock, so nothing has to be named. It is deliberately quiet: types come only from
instructions that state one, unknowns are never reported, and a conflict in a register nobody reads
is legal and ignored. On the APK that crashed it names one method; across 3,001 untouched Gboard
methods it finds nothing.

It also checks every class, field and method a changed method references against **the access
rights of the class the code was injected into**, using ART's rules. An emission is code of its host
class: `instance-of Lozi;` written into `ScrubMotionEventHandler` is a package-private class reached
from another package. ART does not refuse the class for that, because an access failure is a soft
verification failure. The keyboard opens and the instruction throws `IllegalAccessError` when it runs,
which is how `2.5.1-dev.7` and `dev.9` crashed on every swipe up. References that lead into the
framework cannot be judged without `android.jar` and are counted, not passed.

**A Kotlin-only patch change can be applied without a push.** `:patches:jar` needs the SDK because
it builds the extension, but `:patches:compileKotlin` does not, and a bundle is a plain jar: take any
CI bundle, replace every `.class` and `.kotlin_module` entry with `patches/build/classes/kotlin/main`,
keep its resources and `extensions/extension.mpe`, and hand it to `:driver:run`. The extension is
then the old one, so a call into an extension member added since will show in `verify` as
undeclared, which is correct for that hybrid and not a finding about the change.

## What these deliberately do not do

`arsc.py` does not chase references, resolve styles or bags, or decide which configuration variant
wins. Those were never needed; a caller that needs them should read `values()` and choose.

The instruction walk in `dexlib.walk` yields all instructions but decodes only selected operands.
`dalvik_dis.disasm`
decodes every format properly and is what to use when reading a method rather than scanning for
one.

**`dalvik_dis.py` does not print real names for every opcode.** Arithmetic and conversion opcodes come out
as family placeholders — `binop2addrbb` for `add-long/2addr`, `unop82`, `binop2addrc7` and so on.
The operands and control flow are correct; only those mnemonics are cosmetic. Do not write a patch
assertion against a mnemonic read out of `dis.py` without checking it against dexlib2's `Opcode`
enum first — asserting `ADD_LONG_2ADDR` from a dump that says `binop2addrbb` has already produced
one false failure. Anchoring on branches and field references instead avoids the question.

**`dexlib.walk` does report `const` literals** as hex operand strings. Compare with
`int(ref, 0)`, not the integer literal directly. It still leaves `const-wide` operands undecoded.
For a raw-byte sweep of a resource id across code, search instruction bytes:

```python
needle = struct.pack('<I', 0x7f140a05)
for d in dl:
    for cname, af, cd in d.classes():
        for m, maf, co in d.class_methods(cd):
            c = d.code(co)
            if c and needle in d.b[c['insns_off']:c['insns_off'] + c['insns_size'] * 2]:
                print(m)
```
