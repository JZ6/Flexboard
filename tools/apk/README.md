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
