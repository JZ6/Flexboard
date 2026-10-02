#!/usr/bin/env python3
"""Checks over every method a patch changed, for the bugs ART only reports on a phone.

Three of them: a register type-merge conflict (rejected at class load), a call into the extension
that nothing declares (throws when the call runs), and a reference the patched class is not allowed
to make (throws when the instruction runs). The first is most of this file; the other two are
sections at the end.

## Why

`:driver:run` writes a dex; it does not load one, so ART's verifier never runs. A method that is
rejected at class load applies cleanly through the whole pipeline and only fails on a phone, as a
keyboard that will not open. That shipped twice.

The rejection this catches: a register holding one type on one path into a block and an unrelated
type on another, where the block then uses it as a specific type. `2.5.0-dev.0` branched to a block
that does `iget-object v13, v3, Lpvi;->B:…` while our path had just made `v3` a `Lpmy;`.

## What it is, and what it is not

This is **not** a reimplementation of ART's verifier. It is a forward abstract interpretation over
the real control-flow graph with a deliberately small type lattice, tuned to be quiet:

* types come only from instructions that state one outright — `new-instance`, `sget-object`,
  `iget-object`, `check-cast`, `const-string`, `move-result-object` after a call with a known
  return type, and the method's own parameters
* anything else is `UNKNOWN`, and **`UNKNOWN` is never reported**
* a literal zero is `ZERO`, which merges with any reference, because null is assignable to anything
* two different references merge to `CONFLICT` only when neither is assignable to the other, using
  the class hierarchy from the dex where it is available

A finding is raised only when a `CONFLICT` reaches an instruction that *demands* a type — a field
owner, an `iput-object` value, an invoke receiver. That is the shape ART rejects, and requiring the
use site keeps the false-positive rate near zero: merging two unrelated types into a register nobody
reads is legal and common.

False negatives are the accepted trade. A checker that cries wolf gets switched off, and this
project has enough checks that pass without meaning something.

## Access

An emission runs as code *of the class it is injected into*, and ART holds it to that class's
access rights. `2.5.1-dev.9` emitted `instance-of Lozi;` and `iget Lozi;->b` into
`ScrubMotionEventHandler`, in `com.google...scrubmove`; `Lozi;` is package-private and lives in the
unnamed package. ART does not reject the class for that — an access failure is a *soft* verification
failure — so the keyboard opened, and the instruction threw `IllegalAccessError` the first time it
ran, on every swipe up. Nothing here could see it, and the device had no logcat to say so.

The rule checked is ART's: a class is accessible when it is public or in the host's package; a
member when it is public, private to the host itself, in the host's package, or protected and the
host is a subclass. The class named in the reference is checked, then the member where resolution
finds it, which is the order ART uses. A reference whose class or member is outside the APK —
anything in the framework — cannot be judged and is counted as unchecked, never as passed.

## Use

    tools/apk/verify.py <patched.apk> <descriptor>
    tools/apk/verify.py <patched.apk> --changed-from gboard-apk    # every method the patch touched
"""

import os
import re
import shutil
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dexlib  # noqa: E402
import dis as ddis  # noqa: E402

EXTENSION = "Ldev/jz6/flexboard/extension/"

OBJECT = "Ljava/lang/Object;"

# What reaching the root still resolves. Short by design: treating Object as opaque would make the
# whole walk vacuous, since Object is not in the APK and every chain ends there.
OBJECT_MEMBERS = ("toString", "hashCode", "equals", "getClass", "clone", "finalize",
                  "wait", "notify", "notifyAll")

UNKNOWN = "?"
ZERO = "0"
CONFLICT = "!"

# Instructions whose first register operand receives a reference of a type the operand text states.
_NEW = re.compile(r"^v(\d+), (L[\w/$;]+;|\[[\w/$;\[]+)$")
_SGET = re.compile(r"^v(\d+), L[\w/$;]+;->[^:]+:(L[\w/$;]+;|\[[\w/$;\[]+)$")
_IGET = re.compile(r"^v(\d+), v(\d+), (L[\w/$;]+;)->[^:]+:(L[\w/$;]+;|\[[\w/$;\[]+)$")
_IPUT = _IGET
_INVOKE = re.compile(r"^\{([^}]*)\}, (L[\w/$;]+;)->([^(]+)\(([^)]*)\)(.+)$")
_MOVE_OBJ = re.compile(r"^v(\d+), v(\d+)$")


def _regs(text):
    return [int(m) for m in re.findall(r"\bv(\d+)\b", _strip(text or ""))]


def _strip(text):
    """Operand text with descriptors and quoted literals removed, so `v7` inside a type name is
    not mistaken for a register — the same hazard `preflight.regs` documents."""
    text = re.sub(r"'[^']*'", "''", text)
    return re.sub(r"L[\w/$;]+;", "", text)


def _parameter_slots(descriptor_list):
    """One entry per *register* a parameter list occupies, `None` where a type is not a reference.

    A long or double takes two registers, so a naive one-per-parameter walk misaligns every
    argument after the first wide one — and would then report conflicts that are only an off-by-one
    in this function. Primitives yield `None` rather than a type, because the lattice here models
    references and claiming otherwise would invent findings.
    """
    slots, i = [], 0
    while i < len(descriptor_list):
        ch = descriptor_list[i]
        if ch == "L":
            j = descriptor_list.index(";", i)
            slots.append(descriptor_list[i:j + 1])
            i = j + 1
        elif ch == "[":
            j = i
            while descriptor_list[j] == "[":
                j += 1
            if descriptor_list[j] == "L":
                j = descriptor_list.index(";", j)
            slots.append(descriptor_list[i:j + 1])
            i = j + 1
        else:
            slots.append(None)
            if ch in "JD":
                slots.append(None)  # the high half
            i += 1
    return slots


def _invoke_registers(text):
    m = _INVOKE.match((text or "").strip())
    if not m:
        return []
    inside = m.group(1)
    rng = re.match(r"v(\d+) \.\. v(\d+)$", inside.strip())
    if rng:
        return list(range(int(rng.group(1)), int(rng.group(2)) + 1))
    return [int(x) for x in re.findall(r"v(\d+)", inside)]


class Hierarchy:
    """Assignability, from the dex where possible and `unknown` otherwise."""

    def __init__(self, dexes):
        self.parent = {}
        self.interfaces = {}
        for d in dexes:
            import struct
            for i in range(d.cls_n):
                ci, _af, su, io, _sf, _ao, _cd, _sv = struct.unpack_from(
                    "<8I", d.b, d.cls_o + 32 * i)
                name = d.type(ci)
                self.parent[name] = d.type(su) if su != 0xFFFFFFFF else None
                if io:
                    n = struct.unpack_from("<I", d.b, io)[0]
                    self.interfaces[name] = [
                        d.type(struct.unpack_from("<H", d.b, io + 4 + 2 * k)[0]) for k in range(n)]

    def assignable(self, value, target):
        """True when [value] may be used where [target] is required, or when we cannot tell.

        Leaving the dex mid-walk is only unknowable when the *target* is also outside it. If the
        target is an app class that is present, then walking `value` to a framework superclass
        without meeting it **proves** the answer is no — a `Lpmy;` whose chain runs out at
        `Ljava/lang/Enum;` cannot be an `Lpvi;`.

        Getting that backwards made the first version of this file return True for exactly that
        pair, which merged the two types instead of conflicting them and let the check miss the
        shipped bug it was written to catch.
        """
        if value == target or target == "Ljava/lang/Object;":
            return True
        if value not in self.parent:
            return True  # value is a framework class; its hierarchy is not here to walk
        target_known = target in self.parent
        seen, cur = set(), value
        while cur and cur not in seen:
            seen.add(cur)
            if cur == target or target in self.interfaces.get(cur, ()):
                return True
            nxt = self.parent.get(cur)
            if nxt is not None and nxt not in self.parent:
                # The chain leaves the dex here. Only unknowable if the target is out there too.
                return not target_known
            cur = nxt
        return False


def join(a, b, hierarchy):
    if a == b:
        return a
    if a == UNKNOWN or b == UNKNOWN:
        return UNKNOWN
    if a == ZERO:
        return b
    if b == ZERO:
        return a
    if a == CONFLICT or b == CONFLICT:
        return CONFLICT
    if hierarchy.assignable(a, b):
        return b
    if hierarchy.assignable(b, a):
        return a
    return CONFLICT


def handler_entries(ins):
    """Indices of every `move-exception`, which is where a catch handler begins.

    The try/catch tables are not read. Instead every instruction is treated as able to reach
    every handler in the method, which over-approximates: some of those instructions are outside
    the try range. Over-approximating *edges* makes merges happen that ART would not perform, so
    it can only produce findings ART would not, which is the wrong direction for a checker meant
    to stay quiet.

    It is accepted because the alternative is worse. Modelling no handler edges means a register
    that conflicts only at a handler is invisible, and `Lpvf;->t` -- the method this whole
    exercise is about -- has a try block. A false positive gets argued about; a false negative
    ships.
    """
    return [i for i, (_pc, mnemonic, _a) in enumerate(ins)
            if mnemonic.startswith('move-exception')]


def successors(ins, index, pc_index, handlers=()):
    _pc, mnemonic, args = ins[index]
    out = []
    m = re.search(r"-> (\d+)", args or "")
    if m and mnemonic.startswith(("goto", "if-")):
        target = pc_index.get(int(m.group(1)))
        if target is not None:
            out.append(target)
    if mnemonic.startswith("goto"):
        pass
    elif mnemonic.startswith(("return", "throw")):
        out = []
    elif index + 1 < len(ins):
        out.append(index + 1)
    # An exception can be raised almost anywhere, so every handler is a successor of everything
    # else. A handler's own move-exception overwrites its register, so this does not make the
    # exception slot conflict with itself.
    return out + [h for h in handlers if h != index]


def check_method(ins, register_count, parameters, hierarchy):
    """Findings as (index, register, incoming types, what the use site required)."""
    pc_index = {pc: i for i, (pc, _n, _a) in enumerate(ins)}
    handlers = handler_entries(ins)

    entry = [UNKNOWN] * register_count
    for slot, descriptor in enumerate(parameters):
        entry[register_count - len(parameters) + slot] = descriptor

    state = {0: entry}
    order, seen_edges = [0], set()
    while order:
        i = order.pop()
        if i >= len(ins):
            continue
        before = state.get(i)
        if before is None:
            continue
        after = list(before)
        _pc, mnemonic, args = ins[i]
        text = (args or "").strip()

        if mnemonic == "new-instance":
            m = _NEW.match(text)
            if m:
                after[int(m.group(1))] = m.group(2)
        elif mnemonic.startswith("sget-object"):
            m = _SGET.match(text)
            if m:
                after[int(m.group(1))] = m.group(2)
        elif mnemonic.startswith("iget-object"):
            m = _IGET.match(text)
            if m:
                after[int(m.group(1))] = m.group(4)
        elif mnemonic == "check-cast":
            m = _NEW.match(text)
            if m:
                after[int(m.group(1))] = m.group(2)
        elif mnemonic.startswith("const-string"):
            r = _regs(text)
            if r:
                after[r[0]] = "Ljava/lang/String;"
        elif mnemonic.startswith("const") and re.search(r"#-?0x0\b|#0\b", text):
            r = _regs(text)
            if r:
                after[r[0]] = ZERO
        elif mnemonic.startswith("const"):
            r = _regs(text)
            if r:
                after[r[0]] = UNKNOWN
        elif mnemonic.startswith("move-object"):
            m = _MOVE_OBJ.match(text)
            if m:
                after[int(m.group(1))] = before[int(m.group(2))]
        elif mnemonic == "move-result-object":
            r = _regs(text)
            if r:
                after[r[0]] = state.get(("result", i), UNKNOWN)
        elif mnemonic.startswith("invoke"):
            m = _INVOKE.match(text)
            if m:
                state[("result", i + 1)] = m.group(5) if m.group(5).startswith(("L", "[")) else UNKNOWN
        elif mnemonic.startswith(("move-result", "move")):
            r = _regs(text)
            if r:
                after[r[0]] = UNKNOWN

        for s in successors(ins, i, pc_index, handlers):
            merged = state.get(s)
            if merged is None:
                state[s] = list(after)
                order.append(s)
            else:
                new = [join(x, y, hierarchy) for x, y in zip(merged, after)]
                if new != merged:
                    state[s] = new
                    order.append(s)
            seen_edges.add((i, s))

    findings = []
    for i, (_pc, mnemonic, args) in enumerate(ins):
        here = state.get(i)
        if here is None:
            continue
        text = (args or "").strip()
        demands = []
        if mnemonic.startswith(("iget", "iput")):
            m = _IGET.match(text)
            if m:
                demands.append((int(m.group(2)), m.group(3)))
                if mnemonic.startswith("iput-object"):
                    demands.append((int(m.group(1)), m.group(4)))
        elif mnemonic.startswith("invoke"):
            m = _INVOKE.match(text)
            regs = _invoke_registers(text)
            if m and regs:
                instance = not mnemonic.startswith("invoke-static")
                if instance:
                    demands.append((regs[0], m.group(2)))
                # Arguments, which the first version of this ignored entirely. Emissions here
                # hardcode invoke shapes, and this project has shipped four wrong ones; handing a
                # Lpmy; to a parameter declared Landroid/content/Context; is the same class of
                # load-time rejection as the receiver case and was invisible.
                for register, declared in zip(regs[1:] if instance else regs,
                                              _parameter_slots(m.group(4))):
                    if declared is not None:
                        demands.append((register, declared))
        for register, required in demands:
            if register < len(here) and here[register] == CONFLICT:
                findings.append((i, register, required, mnemonic))
    return findings


def declared_members(dexes):
    """Every method and field descriptor the APK declares, for resolution checks."""
    methods, fields = set(), set()
    for d in dexes:
        for _cname, _af, cd in d.classes():
            for m, _maf, _co in d.class_methods(cd):
                methods.add(m)
            try:
                for descriptor, _static, _af in _class_fields(d, cd):
                    fields.add(descriptor)
            except Exception:
                pass
    return methods, fields


def _class_fields(d, cd):
    """(descriptor, is_static, access_flags) per field — the encoded_field walk dexlib does not
    expose."""
    if not cd:
        return
    from dexlib import uleb
    b = d.b
    sf, o = uleb(b, cd)
    inf, o = uleb(b, o)
    dm, o = uleb(b, o)
    vm, o = uleb(b, o)
    for count, static in ((sf, True), (inf, False)):
        idx = 0
        for _ in range(count):
            delta, o = uleb(b, o)
            af, o = uleb(b, o)
            idx += delta
            yield d.field(idx), static, af


def unresolved_extension_references(ins, methods, fields):
    """Calls into the Flexboard extension that the APK does not declare.

    **Deliberately only the extension.** The general version of this — does every member a patched
    method calls exist — cannot be answered here. `Lpvi;` implements `java.lang.AutoCloseable`,
    `Lwzc;` inherits `cancel` from `java.util.concurrent.Future`, and neither is in the APK, so a
    walk up the hierarchy leaves it almost immediately and has to answer "unknowable". Telling an
    inherited framework member apart from a missing one needs `android.jar`, which needs the SDK.
    Attempting it anyway produced three false positives out of four findings on the first run.

    The extension is different: every class is in the APK, this project writes all of them, and a
    patch emitting a call to a member that was renamed is a bug we have actually shipped — the
    diagnostic probe's rename silently defeated a guard. Nothing about that needs a framework.
    """
    missing = []
    for index, (_pc, mnemonic, args) in enumerate(ins):
        text = (args or "").strip()
        if mnemonic.startswith("invoke"):
            m = _INVOKE.match(text)
            if not m or not m.group(2).startswith(EXTENSION):
                continue
            descriptor = f"{m.group(2)}->{m.group(3)}({m.group(4)}){m.group(5)}"
            if descriptor not in methods:
                missing.append((index, descriptor))
        elif mnemonic.startswith(("iget", "iput", "sget", "sput")):
            m = re.search(r"(L[\w/$;]+;)->([^:]+):(\S+)$", text)
            if not m or not m.group(1).startswith(EXTENSION):
                continue
            descriptor = f"{m.group(1)}->{m.group(2)}:{m.group(3)}"
            if descriptor not in fields:
                missing.append((index, descriptor))
    return missing


def _is_framework(descriptor):
    return descriptor.startswith((
        "Landroid/", "Ljava/", "Ljavax/", "Lkotlin/", "Ldalvik/", "Lorg/w3c/", "Lorg/xml/",
        "Lorg/json/", "Lorg/apache/", "Lj$/", "Lsun/",
    ))


PUBLIC, PRIVATE, PROTECTED, INTERFACE = 0x1, 0x2, 0x4, 0x200

FIELD_OPS = ("iget", "iput", "sget", "sput")
TYPE_OPS = ("check-cast", "instance-of", "new-instance", "const-class", "new-array",
            "filled-new-array")


def package_of(descriptor):
    """`com/a/b` for `Lcom/a/b/C;`, and `""` for an obfuscated class in the unnamed package."""
    body = descriptor[1:-1]
    return body.rsplit("/", 1)[0] if "/" in body else ""


class Access:
    """The access rules ART applies to an instruction, over the classes the APK declares.

    Members are decoded per class on first use: indexing every field and method of sixty thousand
    classes up front would cost more than the rest of verify put together, for the dozen classes a
    patch actually names.
    """

    def __init__(self, dexes):
        import struct
        self.flags, self.parent, self.interfaces, self._data = {}, {}, {}, {}
        self._members = {"field": {}, "method": {}}
        for d in dexes:
            for i in range(d.cls_n):
                ci, af, su, io, _sf, _ao, cd, _sv = struct.unpack_from(
                    "<8I", d.b, d.cls_o + 32 * i)
                name = d.type(ci)
                if name in self.flags:
                    continue  # the first definition is the one that loads
                self.flags[name] = af
                self.parent[name] = d.type(su) if su != 0xFFFFFFFF else None
                self.interfaces[name] = []
                if io:
                    n = struct.unpack_from("<I", d.b, io)[0]
                    self.interfaces[name] = [
                        d.type(struct.unpack_from("<H", d.b, io + 4 + 2 * k)[0]) for k in range(n)]
                self._data[name] = (d, cd)

    def knows(self, descriptor):
        """True when the class is in the APK, or is a primitive array that needs no access."""
        element = descriptor.lstrip("[")
        return not element.startswith("L") or element in self.flags

    def members(self, cls, kind):
        cache = self._members[kind]
        if cls not in cache:
            d, cd = self._data[cls]
            if kind == "field":
                cache[cls] = {desc.split("->", 1)[1]: af for desc, _s, af in _class_fields(d, cd)}
            else:
                cache[cls] = {m.split("->", 1)[1]: af for m, af, _co in d.class_methods(cd)}
        return cache[cls]

    def resolve(self, cls, signature, kind):
        """(declaring class, flags) for a member, or None when the search leaves the APK first.

        The superclass chain, then every interface it reaches. ART interleaves a class's interfaces
        before its superclass for fields; the two orders differ only for a name and type declared
        in both an interface and a superclass, which nothing in Gboard does.
        """
        chain, cur, seen = [], cls, set()
        while cur in self.flags and cur not in seen:
            seen.add(cur)
            chain.append(cur)
            cur = self.parent[cur]
        for c in chain:
            flags = self.members(c, kind).get(signature)
            if flags is not None:
                return c, flags
        queue = [i for c in chain for i in self.interfaces[c]]
        while queue:
            i = queue.pop(0)
            if i in seen or i not in self.flags:
                continue
            seen.add(i)
            flags = self.members(i, kind).get(signature)
            if flags is not None:
                return i, flags
            queue.extend(self.interfaces[i])
        return None

    def extends(self, host, ancestor):
        cur, seen = host, set()
        while cur and cur not in seen:
            if cur == ancestor:
                return True
            seen.add(cur)
            cur = self.parent.get(cur)
        return False

    def class_problem(self, host, target):
        element = target.lstrip("[")
        if not element.startswith("L"):
            return None
        if self.flags[element] & PUBLIC or package_of(element) == package_of(host):
            return None
        return f"class {element} is package-private, and {host} is in another package"

    def member_problem(self, host, declarer, flags):
        if flags & PUBLIC:
            return None
        if flags & PRIVATE:
            return None if host == declarer else f"it is private to {declarer}"
        if package_of(host) == package_of(declarer):
            return None
        if (flags & PROTECTED and not self.flags.get(host, 0) & INTERFACE
                and self.extends(host, declarer)):
            return None
        if flags & PROTECTED:
            return f"it is protected in {declarer}, and {host} is neither a subclass nor in its package"
        return f"it is package-private in {declarer}, and {host} is in another package"


def inaccessible_references(host, ins, access):
    """References [host] may not make, as (index, reference, why), and how many were undecidable.

    Every instruction in the method is checked, stock ones included. Those were compiled against
    the same rules and pass, so a finding is always the emission's; checking them anyway means
    nobody has to decide where the emission starts.
    """
    findings, unchecked = [], 0
    for index, (_pc, mnemonic, args) in enumerate(ins):
        base = mnemonic.split("/")[0]
        if base.startswith(FIELD_OPS):
            kind = "field"
        elif base.startswith("invoke-") and base not in ("invoke-polymorphic", "invoke-custom"):
            kind = "method"
        elif base in TYPE_OPS:
            kind = "type"
        else:
            continue
        reference = (args or "").rsplit(", ", 1)[-1].strip()
        owner = reference.split("->", 1)[0]
        if not access.knows(owner):
            unchecked += 1
            continue
        problem = access.class_problem(host, owner)
        if problem is None and kind != "type" and owner.startswith("L"):
            found = access.resolve(owner, reference.split("->", 1)[1], kind)
            if found is None:
                unchecked += 1  # inherited from a framework class, whose flags are not here
                continue
            problem = access.member_problem(host, *found)
        if problem:
            findings.append((index, reference, problem))
    return findings, unchecked


def extract(apk, into):
    with zipfile.ZipFile(apk) as zf:
        names = [n for n in zf.namelist() if n.startswith("classes") and n.endswith(".dex")]
        for name in sorted(names):
            with zf.open(name) as s, open(os.path.join(into, os.path.basename(name)), "wb") as d:
                shutil.copyfileobj(s, d)
    return into


def parameters_of(descriptor, is_static):
    inside = descriptor[descriptor.index("(") + 1:descriptor.index(")")]
    out, i = [], 0
    while i < len(inside):
        if inside[i] == "L":
            j = inside.index(";", i)
            out.append(inside[i:j + 1])
            i = j + 1
        elif inside[i] == "[":
            j = i
            while inside[j] == "[":
                j += 1
            if inside[j] == "L":
                j = inside.index(";", j)
            out.append(inside[i:j + 1])
            i = j + 1
        else:
            out.append(UNKNOWN if inside[i] not in "JD" else UNKNOWN)
            if inside[i] in "JD":
                out.append(UNKNOWN)
            i += 1
    owner = descriptor.split("->")[0]
    return out if is_static else [owner] + out


def method_bodies(dexes):
    """(registers, size) per method descriptor — enough to tell changed from untouched."""
    out = {}
    for d in dexes:
        for _cname, _af, cd in d.classes():
            for m, _maf, co in d.class_methods(cd):
                if not co:
                    continue
                c = d.code(co)
                if c is not None:
                    out[m] = (c["registers"], c["insns_size"])
    return out


def changed_methods(stock_tree, patched_dexes):
    """Descriptors present in both builds whose body differs.

    Methods the patch *added* are excluded: an extension class has no stock counterpart, and a
    method that exists only in the patched build cannot have a merge conflict with a path that
    does not exist.
    """
    stock = method_bodies(dexlib.load(stock_tree))
    patched = method_bodies(patched_dexes)
    return sorted(m for m, body in patched.items() if m in stock and stock[m] != body)


def added_methods(stock_tree, patched_dexes):
    """Methods a patch added to one of Gboard's own classes.

    Excluded from the merge check, which needs a stock path to conflict with, but not from the
    access check: a method added to `ScrubMotionEventHandler` runs with exactly its access rights.

    Only classes Gboard already has. Whole new classes — the extension, and the Kotlin standard
    library the bundle carries with it — are this project's own, compiled by javac and kotlinc
    against rules they already enforce, and there are twenty thousand methods of them.
    """
    stock_dexes = dexlib.load(stock_tree)
    gboard = {name for d in stock_dexes for name, _af, _cd in d.classes()}
    stock = method_bodies(stock_dexes)
    patched = method_bodies(patched_dexes)
    return sorted(m for m in patched if m not in stock and m.split("->")[0] in gboard)


def differing_methods(patched_apk, baseline_apk):
    """Methods whose body differs between two patched builds of the same APK.

    The evidence that one extra patch emitted anything at all. An emission that produces nothing
    looks exactly like one that worked -- the patch applies, verify finds nothing wrong, and the
    build ships unchanged, which is what `2.5.0-dev.1` was. Comparing a build with the patch
    against the same bundle's build without it removes that possibility: if they are identical, the
    patch did nothing, however cleanly it applied.
    """
    with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
        extract(patched_apk, a)
        extract(baseline_apk, b)
        mine = method_bodies(dexlib.load(a))
        base = method_bodies(dexlib.load(b))
        return sorted(m for m, body in mine.items() if m in base and base[m] != body)


def check_all(apk, stock_tree):
    """Verify every method the patch changed. Returns the number with findings."""
    with tempfile.TemporaryDirectory() as tmp:
        extract(apk, tmp)
        dl = dexlib.load(tmp)
        hierarchy = Hierarchy(dl)
        access = Access(dl)
        targets = changed_methods(stock_tree, dl)
        added = added_methods(stock_tree, dl)
        methods, fields = declared_members(dl)

        print(f"  {len(targets)} method(s) changed by the patch, "
              f"{len(added)} added to Gboard's own classes")
        bad = unchecked = 0
        for descriptor in targets + added:
            d, c, maf = ddis.find(descriptor, dl)
            if not c:
                continue
            ins = ddis.disasm(d, c)
            findings = [] if descriptor in added else check_method(
                ins, c["registers"], parameters_of(descriptor, bool(maf & 0x8)), hierarchy)
            # A member that moved is a NoSuchMethodError when the call runs, not when the class
            # loads, so the merge check above cannot see it: the patch applies, verifies, and
            # fails under a finger.
            missing = unresolved_extension_references(ins, methods, fields)
            # Neither can an access the host is not allowed: the class loads and the instruction
            # throws IllegalAccessError when it runs. See "Access" at the top.
            denied, undecided = inaccessible_references(descriptor.split("->")[0], ins, access)
            unchecked += undecided
            failed = findings or missing or denied
            mark = "FAIL" if failed else "ok  "
            print(f"    {mark} {descriptor[:92]}{'  (added)' if descriptor in added else ''}")
            for index, register, required, _nm in findings:
                pc, nm, a = ins[index]
                print(f"         pc {pc}: v{register} conflicts, `{nm}` requires {required}")
                print(f"         {nm} {a}")
            for index, reference in missing:
                pc = ins[index][0]
                print(f"         pc {pc}: nothing in the APK declares {reference}")
            for index, reference, why in denied:
                pc, nm = ins[index][0], ins[index][1]
                print(f"         pc {pc}: `{nm}` may not reach {reference}: {why}")
            bad += 1 if failed else 0
        print(f"  access: {unchecked} reference(s) lead outside the APK and were not judged")
    return bad


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]

    if "--differs-from" in sys.argv:
        at = sys.argv.index("--differs-from")
        baseline = sys.argv[at + 1]
        # By position, not by value: filtering out "the baseline's path" also removed the patched
        # argument whenever the two were the same file, and reported a usage error instead.
        args = [a for j, a in enumerate(sys.argv[1:], 1)
                if j not in (at, at + 1) and not a.startswith("--")]
        if len(args) != 1:
            print("usage: verify.py <patched.apk> --differs-from <baseline.apk>", file=sys.stderr)
            return 2
        changed = differing_methods(args[0], baseline)
        if not changed:
            print("  identical to the baseline: the extra patch applied and emitted nothing")
            return 1
        print(f"  {len(changed)} method(s) differ from the baseline:")
        for descriptor in changed:
            print(f"    {descriptor[:100]}")
        return 0

    if "--changed-from" in sys.argv:
        stock_tree = sys.argv[sys.argv.index("--changed-from") + 1]
        args = [a for a in args if a != stock_tree]
        if len(args) != 1:
            print(__doc__.strip().split("## Use")[1].strip(), file=sys.stderr)
            return 2
        bad = check_all(args[0], stock_tree)
        print()
        if bad:
            print(f"  {bad} method(s) have a conflicting merge, an unresolved reference or an "
                  f"inaccessible one")
            print("  (a merge conflict is rejected at class load; the other two throw when the "
                  "instruction runs)")
            return 1
        print("  no changed method has a conflicting register reaching a typed use, an "
              "unresolved extension call, or a reference its class may not make")
        return 0

    if len(args) != 2:
        print(__doc__.strip().split("## Use")[1].strip(), file=sys.stderr)
        return 2
    apk, descriptor = args

    with tempfile.TemporaryDirectory() as tmp:
        extract(apk, tmp)
        dl = dexlib.load(tmp)
        hierarchy = Hierarchy(dl)
        d, c, maf = ddis.find(descriptor, dl)
        if not c:
            print(f"not found: {descriptor}", file=sys.stderr)
            return 1
        ins = ddis.disasm(d, c)
        params = parameters_of(descriptor, bool(maf & 0x8))
        findings = check_method(ins, c["registers"], params, hierarchy)
        denied, undecided = inaccessible_references(descriptor.split("->")[0], ins, Access(dl))
        registers, count = c["registers"], len(ins)

    print(f"=== {descriptor}  registers={registers}  instructions={count}")
    if not findings and not denied:
        print("  no conflicting register reaches a use site that requires a type, and every")
        print(f"  reference is one this class may make ({undecided} outside the APK not judged).")
        print("  (a quiet result is not a proof: unknown types are never reported)")
        return 0
    for index, register, required, mnemonic in findings:
        pc, nm, a = ins[index]
        print(f"  FAIL pc {pc}: v{register} holds conflicting types here and `{nm}` requires "
              f"{required}")
        print(f"       {nm} {a}")
    for index, reference, why in denied:
        pc, nm = ins[index][0], ins[index][1]
        print(f"  FAIL pc {pc}: `{nm}` may not reach {reference}: {why}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
