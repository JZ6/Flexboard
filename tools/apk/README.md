# APK inspection tools

Read-only inspection of a Gboard APK in pure Python. No apktool, no aapt, no adb, no JDK — these
exist because every binding in this project has to be re-derived by hand whenever Gboard moves,
and the usual tooling is either unavailable or overkill for "what does this one method do".

Everything the `docs/` findings rest on was produced with these three files.

| | |
|---|---|
| `dexlib.py` | DEX reader — strings, types, methods, fields, classes, code items, and a coarse instruction walk that decodes calls, field access and constants |
| `dis.py` | Full-format Dalvik disassembler on top of `dexlib`, with registers and branch targets |
| `axml.py` | Binary XML (AXML) reader — walks elements and attributes of compiled `res/**.xml` |
| `arsc.py` | Resource table reader — resource id to name and value, and the reverse lookup from a packed path back to its id |
| `glyphs.py` | Matches the APK's stripped vector drawables against published Material Icons SVGs, by geometry rather than by name. |
| `preflight.py` | Runs every patch-time assertion against a dex, so a moved binding fails here instead of on a phone |

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

Re-implements each patch's `check(...)`/`error(...)` against the real dex and exits non-zero if any
fails. Worth running before every release and *first* after a Gboard bump: nothing else in the
pipeline ever applies the bundle to an APK, so this is the only step between "the Kotlin compiles"
and a device.

It is a proof of *breakage*, not of correctness — it cannot tell you a patch works, only that every
binding it names is still there and still shaped the way the patch assumes. On a Gboard bump, edit
`BINDINGS` and `EXPECTED` at the top; if a *check* needs rewriting rather than a constant, that is
the signal a patch does too.

## Disassemble a method

```python
import dexlib, dis
dl = dexlib.load('/tmp/gb')
dis.show('Lpnp;->at(I)Z', dl)
```

```
=== Lpnp;->at(I)Z  regs=3 ins=2 outs=3 static=False ===
     0: const/4                v0, #0
     1: invoke-virtual         {v1, v2, v0}, Lcbv;->x(IZ)Z
     4: move-result            v1
     5: return                 v1
```

## List a class

```python
for d in dl:
    for cname, af, cd in d.classes():
        if cname != 'Lgmb;':
            continue
        for m, maf, code_off in d.class_methods(cd):
            c = d.code(code_off)
            print(m, c and (c['registers'], c['ins'], c['outs']))
```

## Find callers of a method, or readers of a field

`d.walk(code)` yields `(pc, opcode, mnemonic, reference)` for the interesting opcodes, so a full
sweep is a nested loop. It takes a couple of minutes across ~21,000 classes.

```python
for d in dl:
    for cname, af, cd in d.classes():
        for m, maf, co in d.class_methods(cd):
            c = d.code(co)
            if not c:
                continue
            for pc, op, mn, ref in d.walk(c):
                if ref == 'Lgmb;->c()V':
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
stored as little-endian ids. So to find every XML that mentions resource `0x7f14097b`:

```python
import struct
needle = struct.pack('<I', 0x7f14097b)
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

**The bundled set is known exhaustively**, so there is nothing left to guess at: all 2,170
published Material Icons were matched against the APK's 496 vector drawables, and 29 shapes are
present at 35 ids. The table is in
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

mkdir -p /tmp/mi && cd /tmp/mi
sed 's#/#|#' /tmp/mi_index.txt | xargs -P 24 -I{} bash -c \
  'IFS="|" read -r c n <<< "{}"
   curl -sf -o "$n.svg" \
     "https://raw.githubusercontent.com/google/material-design-icons/master/src/$c/$n/materialicons/24px.svg" || true'

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

t.value(0x7f14097b)            # 'enable_gesture_input'
t.name(0x7f170e7e)             # 'xml/settings'
t.value(0x7f170e7e)            # 'res/B_o.xml'      — the packed path
t.find_value('res/aDh.xml')    # [(0x7f170779, 'xml/0_resource_name_obfuscated')]
t.find_name('setting_')        # every settings screen, by name
```

`values()` returns every configuration variant rather than guessing which one applies; `value()`
takes the first, which is right for ids with a single definition.

Most names come back as `0_resource_name_obfuscated` — Gboard is built with aapt2
`--collapse-resource-names`, and only 619 of 33,287 entries keep a real name. Which ones survive,
and why it matters for resource patches, is in
[`../../docs/gboard-bindings.md`](../../docs/gboard-bindings.md).

## What these deliberately do not do

`arsc.py` does not chase references, resolve styles or bags, or decide which configuration variant
wins. Those were never needed; a caller that needs them should read `values()` and choose.

The instruction walk in `dexlib.walk` only decodes opcodes worth searching for. `dis.disasm`
decodes every format properly and is what to use when reading a method rather than scanning for
one.

**`dis.py` does not print real names for every opcode.** Arithmetic and conversion opcodes come out
as family placeholders — `binop2addrbb` for `add-long/2addr`, `unop82`, `binop2addrc7` and so on.
The operands and control flow are correct; only those mnemonics are cosmetic. Do not write a patch
assertion against a mnemonic read out of `dis.py` without checking it against dexlib2's `Opcode`
enum first — asserting `ADD_LONG_2ADDR` from a dump that says `binop2addrbb` has already produced
one false failure. Anchoring on branches and field references instead avoids the question.

**`dexlib.walk` does not report `const` literals.** Scanning it for a resource id returns nothing
whether or not the id is used — verified against `0x7f0c00ef`, which is `const v1, 0x7f0c00ef` at
offset 4 of `ScrubMotionEventHandler.<init>` and yields zero matches. To find where a resource id is
used in code, search the raw instruction bytes for it, exactly as you would for `res/**.xml`:

```python
needle = struct.pack('<I', 0x7f140977)
for d in dl:
    for cname, af, cd in d.classes():
        for m, maf, co in d.class_methods(cd):
            c = d.code(co)
            if c and needle in d.b[c['insns_off']:c['insns_off'] + c['insns_size'] * 2]:
                print(m)
```
