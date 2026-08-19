"""Check every patch-time assertion against a Gboard dex, before anything reaches a device.

## Why this exists

Nothing in the pipeline applies the bundle to an APK. CI compiles Kotlin; the patches themselves
only ever run inside Morphe, on the user's phone. So between "it compiles" and "it works" there is
no step at all, and the two releases that got that wrong both reached a device before anything
noticed: `0.0.1-dev.1` emitted an instruction that failed ART's verifier, and `0.0.2-dev.1` looked
up a field on the wrong class and refused to apply.

This closes that gap for the cheap half of the problem. Each check below mirrors one
`check(...)`/`error(...)` in the Kotlin, evaluated against the real dex. It cannot prove a patch
*works* — only Morphe applying it and a device running it can do that — but it catches every
binding that has moved, which is what a Gboard version bump actually breaks.

## Use

    python3 -c "
    import zipfile
    z = zipfile.ZipFile('gboard.apk')
    for n in z.namelist():
        if n.endswith('.dex'):
            z.extract(n, '/tmp/gb')
    "
    python3 tools/apk/preflight.py /tmp/gb

Exits non-zero if anything fails, so it can gate a bump.

## Updating it for a new Gboard

Edit `BINDINGS` and the register counts in `EXPECTED`. Everything else is structural and should
carry over untouched — if a *check* needs rewriting rather than a constant, that is the signal that
a patch needs rewriting too.
"""
import os
import re
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dexlib
import dis as ddis
from dexlib import uleb

# --------------------------------------------------------------------------- what to expect

# Stable names. Gboard attaches motion event handlers and IMEs by class-name string, so R8 keeps
# these; everything in BINDINGS is obfuscated and moves on every build.
SCRUB = ('Lcom/google/android/libraries/inputmethod/motioneventhandler/scrubmove/'
         'ScrubMotionEventHandler;')
SCRUB_DELETE = ('Lcom/google/android/libraries/inputmethod/motioneventhandler/scrubmove/'
                'ScrubDeleteMotionEventHandler;')
ABSTRACT_HANDLER = ('Lcom/google/android/libraries/inputmethod/motioneventhandler/'
                    'AbstractMotionEventHandler;')
KEYBOARD_VIEW = 'Lcom/google/android/libraries/inputmethod/widgets/SoftKeyboardView;'
LATIN_IME = 'Lcom/google/android/apps/inputmethod/libs/latin5/LatinIme;'
ABSTRACT_IME = 'Lcom/google/android/libraries/inputmethod/ime/AbstractIme;'
LATIN_APP = 'Lcom/google/android/apps/inputmethod/latin/LatinApp;'
ACCESS_POINTS_BAR = ('Lcom/google/android/libraries/inputmethod/accesspoint/widget/'
                     'AccessPointsBar;')
CONTEXT = 'Landroid/content/Context;'

# A `35c` invoke encodes each register in a nibble, so it can only reach v0..v15.
PACKED_INVOKE_REGISTER_LIMIT = 16

# `AbstractIme->…(L…;Z)V` — the shape of the undo re-commit, whatever it is called this build.
RECOMMIT_RE = re.compile(
    r'^Lcom/google/android/libraries/inputmethod/ime/AbstractIme;->\w+\((L[\w/$;]+;)Z\)V$')

# Gboard 18.0.3.954559732-release-arm64-v8a.
BINDINGS = {
    'store': 'Lqhy;',
    'config': 'Lpvs;',
    'delegate': 'Lpvo;',
    'event': 'Lnur;',
    'scrub_state': 'Lomu;',
    'undo_slot': 'Lqyc;',
    'committable': 'Lojt;',
    'sigcheck': 'Lrpv;',
    # Not a cached signature verdict, despite the company it keeps here. Lrox;->b:Z is the global
    # test-environment flag (Build.FINGERPRINT.equals("robolectric")), permanently false on a
    # device and read in ~40 unrelated places. The signature check reads it once, as the value to
    # return when the caller's digest cannot be computed -- an input, never an output. It is
    # tracked only because reading it is part of what identifies the check.
    'test_environment': 'Lrox;',
}

EXPECTED = {
    'dispatcher_name': 'q',              # LatinIme's event dispatcher
    'dispatcher_registers': 34,
    'suppressed_field': 'O',             # AbstractIme's suppression flag
    'store_singleton': 'I',              # Lqhy;->I(Context)Lqhy;
    'store_contains': 'ak',              # contains, keyed by resource id
    'store_write': 'T',                  # (I, Object) -> void
    'handler_context_field': 'o',
    'undo_slot_field': 'y',
    'recommit': 'Lcom/google/android/libraries/inputmethod/ime/AbstractIme;->t(Lojt;Z)V',
    'recommit_window': 40,
    'slot_field': 'Lcom/google/android/apps/inputmethod/libs/latin5/LatinIme;->y:Lqyc;',
    'slot_available': 'Lqyc;->d()Z',
    'slot_clear': 'Lqyc;->c()V',
    'get_int': 'Lqhy;->b(Ljava/lang/String;I)I',
    'contains': 'Lqhy;->ak(I)Z',
    'scrub_g_registers': 13,
    'scrub_r_registers': 13,
    'engine_ctor_registers': 11,
    'apply_preferences_registers': 13,
    'apply_preferences_ins': 2,
    'sigcheck_registers': 8,
    'sigcheck_returns': [6, 4, 3],
    'undo_scratch': [2, 3],
    'clamp_scratch': [5, 7, 9],
    'distance_scratch': [7, 8, 9],
    'stock_start_keycode': 67,
    'toolbar_scratch': [2, 5],
    'toolbar_ctor_registers': 9,
    'toolbar_ctor_ins': 3,
    # Gboard's own log line for the order-update callback. The anchor for the *count*, as opposed to
    # the capacity the constructor writes -- and unusually good for one, because it does not merely
    # locate a method, it says in Google's own words what the value returned there is.
    'toolbar_count_log': 'oldVisibleCountOnBar %d, currentVisibleCountOnBar %d, definedCountOnBar %d',
    # Gboard's own name for the device class an open fold reports. The enum's <clinit> hands it to
    # each constant's constructor as a literal, and R8 rewrites the field but never the string, so
    # this is what tells the foldable constant apart from DEVICE_TABLET sitting next to it.
    'toolbar_foldable_name': 'DEVICE_FOLDABLE',
    'toolbar_count_registers': 5,
    'toolbar_count_ins': 2,
    'toolbar_count_scratch': [0, 1, 2],
    # Gboard's own stock icon count, the default of the getInt in the bar's constructor. Not used by
    # the patch -- it reads the preference with whatever Gboard computed -- but it is the number the
    # settings slider displays while unset, so it has to stay true.
    'toolbar_stock_count': 5,
    # ---- text editing buttons
    # The three resource ids Gboard's text-editing access-point seed uses together. The patch finds
    # the seed by them and then reads the builder's setters out of it by the value each is handed,
    # because five setters share the signature (I)V and naming one would be a bet on R8's letters.
    'buttons_seed_literals': [0x7f080546, 0x7f140720, 0x7f141218],
    # Every button's label and icon, with the path signature the icon was found by. The labels are
    # Gboard's own strings, already translated; the icons are Material's, which Gboard bundles and
    # never draws -- its text editing panel spells all three out in words with no icon at all.
    # Neither has a dex anchor, so both need the resource table. Found with tools/apk/glyphs.py.
    'buttons_resources': [
        ('Select all', 0x7f140576, 0x7f080218, 'M9,9h6v6L9,15L9,9z'),
        ('Copy', 0x7f140560, 0x7f080214, 'M19,21L8,21L8,7h11v14z'),
        ('Paste', 0x7f140570, 0x7f080217, 'M19,20L5,20L5,4h2v3h10L17,4h2v16z'),
    ],
    # The generated builder's own words for the properties it refuses to build without. These are
    # string literals in the dex, which is why they are worth anchoring on: R8 renames the class,
    # the methods and the fields around them and leaves these untouched.
    'hotkey_properties': [' icon', ' label', ' contentDescription'],
    # Of those, the ones whose literal is a String and so can be written directly. The icon's
    # literal is an Icon, which is why it is not here.
    'hotkey_literal_properties': [' label', ' contentDescription'],
    'hotkey_slots': 6,
    # One icon per hotkey slot, asserted by glyph rather than by type -- a renumbering would still
    # land on something reading 'drawable/'. Gboard bundles no numbered glyphs, so these are
    # arbitrary markers and the settings screen previews each one; showing the user one shape and
    # putting another on the bar would take away the only thing telling six buttons apart.
    'hotkey_icons': [
        ('star', 0x7f080239, 'M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-'),
        ('auto_awesome', 0x7f0806fc, 'M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2'),
        ('content_cut', 0x7f080215, 'M9.64,7.64c0.23,-0.5 0.36,-1.05 0.36,-1.64 0,-'),
        ('check_box', 0x7f08074e, 'M19,3L5,3c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 '),
        ('radio_button_unchecked', 0x7f080733,
         'M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.4'),
        ('share', 0x7f080219, 'M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12'),
    ],
    'buttons_split_registers': 7,
    'buttons_split_ins': 2,
    'buttons_split_scratch': [0, 1, 2, 3, 4],
    'buttons_oncreate_registers': 12,
    # The keycode Gboard wraps a Runnable in, and the dispatcher that runs it. Two other classes
    # test this keycode and decline it, so "something tests it" is not the check that matters.
    'buttons_runnable_keycode': -40007,
}

# --------------------------------------------------------------------------- dex helpers
# dexlib deliberately exposes only what its own scans need; these add the two reads this wants.


def class_defs(d):
    import struct
    for i in range(d.cls_n):
        ci, af, su, io, sf, ao, cd, sv = struct.unpack_from('<8I', d.b, d.cls_o + 32 * i)
        yield d.type(ci), (d.type(su) if su != 0xFFFFFFFF else None), cd


def find_class(dl, name):
    for d in dl:
        for cname, sup, cd in class_defs(d):
            if cname == name:
                return d, sup, cd
    return None, None, None


def class_fields(d, cd):
    """(descriptor, is_static) for every field of a class_data_item."""
    if not cd:
        return
    b = d.b
    sf, o = uleb(b, cd)
    inf, o = uleb(b, o)
    dm, o = uleb(b, o)
    vm, o = uleb(b, o)
    for count, static in ((sf, True), (inf, False)):
        idx = 0
        for _ in range(count):
            diff, o = uleb(b, o)
            af, o = uleb(b, o)
            idx += diff
            yield d.field(idx), static


def superclass_chain(dl, name, limit=16):
    out, cur = [], name
    while cur and len(out) < limit:
        out.append(cur)
        if cur == 'Ljava/lang/Object;':
            break
        d, sup, cd = find_class(dl, cur)
        if d is None:
            out.append('(not in dex)')
            break
        cur = sup
    return out


def find_instance_field(dl, type_, name):
    """Resolved the way the runtime resolves a field reference — walking up until one declares it.

    `ClassDef.instanceFields` alone is not enough, which is what shipped as `0.0.2-dev.1`.
    """
    cur = type_
    while cur:
        d, sup, cd = find_class(dl, cur)
        if d is None:
            return None
        for fd, static in class_fields(d, cd):
            if not static and fd.split('->')[1].split(':')[0] == name:
                return fd
        cur = sup
    return None


def switch_keys(dex, c):
    """Every case key of every switch in a method, including the ones no literal search can find.

    A packed-switch payload stores only its *first* key and a count; the rest are implied by
    position, so a keycode handled by one appears nowhere as a `const` and a literal search
    reports it unhandled. That is not a hypothetical -- it is how -10086 was first, wrongly,
    concluded to be handled, and how the -40007 dispatcher was first, wrongly, concluded absent.
    """
    b, keys = dex.b, set()
    base = c['insns_off']
    end = base + 2 * c['insns_size']
    p = base
    while p < end:
        unit = struct.unpack_from('<H', b, p)[0]
        op = unit & 0xff
        if op == 0x00 and unit >> 8:
            ident = unit >> 8
            if ident == 1:                                     # packed-switch payload
                size = struct.unpack_from('<H', b, p + 2)[0]
                first = struct.unpack_from('<i', b, p + 4)[0]
                keys.update(range(first, first + size))
                n = size * 2 + 4
            elif ident == 2:                                   # sparse-switch payload
                size = struct.unpack_from('<H', b, p + 2)[0]
                keys.update(struct.unpack_from(f'<{size}i', b, p + 4))
                n = size * 4 + 2
            else:                                              # fill-array-data
                width = struct.unpack_from('<H', b, p + 2)[0]
                size = struct.unpack_from('<I', b, p + 4)[0]
                n = (size * width + 1) // 2 + 4
            p += 2 * n
            continue
        p += 2 * dexlib._L[op]
    return keys


def body(dl, descriptor):
    d, c, maf = ddis.find(descriptor, dl)
    if not c:
        return None, None
    return c, ddis.disasm(d, c)


def regs(arg):
    return [int(x) for x in re.findall(r'v(\d+)', arg)]


# Mnemonics whose first register operand is a *source*, not a destination. Everything else that
# names a register writes the first one, which is what makes `writes_before` usable as a liveness
# test rather than a guess.
READS_FIRST_OPERAND = ('if-', 'invoke', 'iput', 'sput', 'aput', 'return', 'throw', 'monitor',
                       'fill-array', 'packed-switch', 'sparse-switch')


def writes_before(ins, reg, after_pc, before_pc):
    """Instructions in (after_pc, before_pc] that overwrite vreg."""
    return [(pc, n) for pc, n, a in ins
            if after_pc < pc <= before_pc
            and not n.startswith(READS_FIRST_OPERAND)
            and regs(a)[:1] == [reg]]


def live_free(ins, register_count, at_pc):
    """Registers that are dead at `at_pc`, by backward liveness over the real control flow.

    A forward "is the next touch a write?" scan is not sound here and gets a real answer wrong:
    in `r()` it reports v3 free because the table walk writes it, but the `if-gt` guarding that
    walk branches straight past the write to a path that reads v3. Borrowing it would corrupt the
    extrapolated word count on long swipes, silently. So this does the fixpoint properly.
    """
    n = len(ins)
    pcs = [i[0] for i in ins]
    index = {p: k for k, p in enumerate(pcs)}

    def successors(k):
        _, mnemonic, args = ins[k]
        match = re.search(r'-> (\d+)', args)
        if mnemonic.startswith('goto'):
            return [index[int(match.group(1))]] if match else []
        if mnemonic.startswith(('return', 'throw')):
            return []
        out = [index[int(match.group(1))]] if match else []
        if k + 1 < n:
            out.append(k + 1)
        return out

    live = [set() for _ in range(n + 1)]
    for _ in range(500):
        changed = False
        for k in range(n - 1, -1, -1):
            _, mnemonic, args = ins[k]
            r = regs(args)
            out = set()
            for t in successors(k):
                out |= live[t]
            if mnemonic.startswith(READS_FIRST_OPERAND):
                sources, destination = r, None
            else:
                sources, destination = r[1:], (r[0] if r else None)
            new = set(out)
            if destination is not None:
                new.discard(destination)
            new |= set(sources)
            if new != live[k]:
                live[k] = new
                changed = True
        if not changed:
            break
    at = live[index[at_pc]]
    return [r for r in range(register_count) if r not in at]


# --------------------------------------------------------------------------- checks

class Report:
    def __init__(self):
        self.rows = []

    def __call__(self, name, ok, detail=''):
        self.rows.append((bool(ok), name, detail))
        return bool(ok)

    def skip(self, name, why):
        """A check that could not run.

        Deliberately not the same thing as a pass. A check that silently does not run is the
        failure mode this tool exists to prevent, so a skip is printed and counted apart from the
        pass total rather than being folded into it.
        """
        self.rows.append((None, name, why))
        return False

    def finish(self):
        width = max(len(n) for _, n, _ in self.rows)
        failed = skipped = 0
        for ok, name, detail in self.rows:
            if ok is None:
                skipped += 1
                state = 'SKIP'
            else:
                failed += not ok
                state = 'PASS' if ok else 'FAIL'
            shown = detail if ok is not True else ''
            print(f'{state}  {name:<{width}}  {shown}'.rstrip())
        total = len(self.rows) - skipped
        tail = f', {skipped} skipped' if skipped else ''
        print(f'\n{total - failed}/{total} passed{tail}')
        return failed


def run(dl, apk=None):
    B, E = BINDINGS, EXPECTED
    store, config, delegate = B['store'], B['config'], B['delegate']
    check = Report()

    # ---- preference store
    for sig, label in (
        (f'{store}->{E["store_singleton"]}({CONTEXT}){store}', 'singleton getter'),
        (f'{store}->b(Ljava/lang/String;I)I', 'getInt by string'),
        (f'{store}->k(Ljava/lang/String;Z)Z', 'getBoolean by string'),
        (f'{store}->{E["store_contains"]}(I)Z', 'contains by id'),
        (f'{store}->{E["store_write"]}(ILjava/lang/Object;)V', 'write by id'),
    ):
        c, _ = body(dl, sig)
        check(f'store: {label}', c is not None, sig)

    # ---- undo delete
    dispatch = f'{LATIN_IME}->{E["dispatcher_name"]}({B["event"]})Z'
    c, ins = body(dl, dispatch)
    if check('undo: dispatcher exists', ins is not None, dispatch):
        check('undo: dispatcher register count', c['registers'] == E['dispatcher_registers'],
              f'got {c["registers"]}, expected {E["dispatcher_registers"]}')
        take_text = f'{B["scrub_state"]}->a(I)Ljava/lang/CharSequence;'
        hits = [i for i, (pc, n, a) in enumerate(ins) if take_text in a]
        if check('undo: takeText call is unique', len(hits) == 1, f'found {len(hits)}'):
            ti = hits[0]
            flag_field = f'{ABSTRACT_IME}->{E["suppressed_field"]}:Z'
            flags = [i for i in range(ti - 1, max(-1, ti - 13), -1)
                     if ins[i][1] == 'iget-boolean' and flag_field in ins[i][2]]
            if check('undo: suppression flag read in the anchor window', bool(flags), flag_field):
                fi = flags[0]
                flag_reg, ime_reg = regs(ins[fi][2])[:2]
                check('undo: if-nez follows the flag read', ins[fi + 1][1] == 'if-nez',
                      ins[fi + 1][1])
                check('undo: the if-nez tests the flag register',
                      regs(ins[fi + 1][2])[:1] == [flag_reg])
                check('undo: move-result precedes the flag read',
                      ins[fi - 1][1] == 'move-result', ins[fi - 1][1])
                count_reg = regs(ins[fi - 1][2])[0]
                claimed = [count_reg, ime_reg, flag_reg] + E['undo_scratch']
                check('undo: no register collision', len(set(claimed)) == len(claimed),
                      f'count=v{count_reg} this=v{ime_reg} flag=v{flag_reg} '
                      f'scratch={E["undo_scratch"]}')

    # The IME's Context field used to be checked here, because the undo patch reached the
    # preference store through it to read an on/off toggle. Undo is unconditional now, so nothing
    # resolves a Context inside the dispatcher and there is nothing left to assert.

    # The undo cluster, resolved the way the patch resolves it: from the handler that performs
    # Gboard's own undo, anchored on the re-commit's *shape* rather than any name.
    #
    # Checking that a named method merely *exists* is what let `0.0.3-dev.1` ship broken. Four of
    # these share a signature with siblings on the same class — `AbstractIme->s`/`t`, the slot's
    # three `()Z` methods, its nine `()V` methods — so existence proves nothing. Only the call site
    # distinguishes them, and this mirrors that resolution so a drift shows up here first.
    slot = B['undo_slot']
    c, ins = body(dl, dispatch)
    if ins:
        anchors = [i for i, (pc, n, a) in enumerate(ins)
                   if n.startswith('invoke') and RECOMMIT_RE.match(a.split(', ')[-1])]
        if check('undo: the re-commit anchor is unique in the dispatcher', len(anchors) == 1,
                 f'found {len(anchors)} AbstractIme->…(L…;Z)V calls'):
            ai = anchors[0]
            resolved = ins[ai][2].split(', ')[-1]
            check('undo: resolved re-commit matches the expected one',
                  resolved == E['recommit'], f'stock calls {resolved}')
            check('undo: committable-text type matches the cast',
                  RECOMMIT_RE.match(resolved).group(1) == B['committable'],
                  f'stock casts to {RECOMMIT_RE.match(resolved).group(1)}')
            # An empty base declaration means the subclass override is what runs; that is exactly
            # why the two hooks are indistinguishable without this call site.
            c2, _ = body(dl, resolved.replace(ABSTRACT_IME, LATIN_IME))
            check('undo: LatinIme overrides the re-commit', c2 is not None)

            # The slot is the receiver of the call *returning* an Optional. Matching on the type
            # appearing anywhere would catch Optional's own isPresent/get instead.
            start = max(0, ai - E['recommit_window'])
            gets = [i for i in range(start, ai)
                    if ins[i][1].startswith('invoke')
                    and ins[i][2].split(', ')[-1].endswith(')Lj$/util/Optional;')]
            if check('undo: an Optional getter precedes the re-commit', bool(gets)):
                gi = gets[-1]
                got = ins[gi][2].split(', ')[-1]
                slot_reg = regs(ins[gi][2])[0]
                check('undo: the Optional getter is on the expected slot',
                      got.startswith(slot), f'resolved slot is {got.split("->")[0]}')

                def on_slot(i, ret):
                    d_ = ins[i][2].split(', ')[-1]
                    return (ins[i][1].startswith('invoke') and d_.startswith(slot)
                            and d_.endswith(f'(){ret}') and regs(ins[i][2])[:1] == [slot_reg])

                avail = [ins[i][2].split(', ')[-1]
                         for i in range(gi - 1, start - 1, -1) if on_slot(i, 'Z')]
                clear = [ins[i][2].split(', ')[-1]
                         for i in range(ai + 1, min(len(ins), ai + 1 + E['recommit_window']))
                         if on_slot(i, 'V')]
                check('undo: resolved availability check',
                      bool(avail) and avail[0] == E['slot_available'],
                      f'resolved {avail[:1]}, expected {E["slot_available"]}')
                check('undo: resolved slot clear', bool(clear) and clear[0] == E['slot_clear'],
                      f'resolved {clear[:1]}, expected {E["slot_clear"]}')

                fields = [ins[i][2].split(', ')[-1]
                          for i in range(gi - 1, start - 1, -1)
                          if ins[i][1] == 'iget-object' and regs(ins[i][2])[:1] == [slot_reg]]
                check('undo: resolved slot field', bool(fields) and fields[0] == E['slot_field'],
                      f'resolved {fields[:1]}, expected {E["slot_field"]}')

    # Store members whose signature is NOT unique, so the patches derive them by behaviour. These
    # mirror that derivation; a mismatch means the letter has moved onto the sibling.
    def sole_with_signature(owner, signature, calling=None, not_calling=None):
        d_, sup_, cd_ = find_class(dl, owner)
        out = []
        for m in (d_.class_methods(cd_) if cd_ else []):
            desc, af_, co_ = m
            if not desc.endswith(signature):
                continue
            c_ = d_.code(co_)
            calls = ''
            if c_:
                calls = ' '.join(str(r) for _, _, _, r in d_.walk(c_) if r)
            if calling and calling not in calls:
                continue
            if not_calling and not_calling in calls:
                continue
            out.append(desc)
        return out

    got = sole_with_signature(store, '(Ljava/lang/String;I)I',
                              not_calling='Ljava/lang/Integer;->parseInt')
    check('store: getInt resolves uniquely by behaviour', len(got) == 1 and got[0] == E['get_int'],
          f'resolved {got}, expected {E["get_int"]}')
    got = sole_with_signature(store, '(I)Z', calling='Landroid/content/SharedPreferences;->contains')
    check('store: contains resolves uniquely by behaviour',
          len(got) == 1 and got[0] == E['contains'], f'resolved {got}, expected {E["contains"]}')

    d, sup, cd = find_class(dl, LATIN_IME)
    held = [fd for fd, static in class_fields(d, cd)
            if fd.endswith(f'->{E["undo_slot_field"]}:{slot}')]
    check('undo: LatinIme holds the undo slot', len(held) == 1, str(held))

    # ---- swipe to delete
    ctor = f'{SCRUB_DELETE}-><init>({CONTEXT}{delegate})V'
    c, ins = body(dl, ctor)
    # The patch now replaces the keycode constant outright rather than reading a preference to
    # decide it, so the checks that proved three registers dead here are gone with the insertion
    # they justified — the free-register scan, the all-arguments-are-consts window, and the Context
    # parameter's liveness. What remains is what still has to be true: exactly one keycode constant,
    # and it is the one feeding the config.
    if check('scrubdelete: delete ctor exists', ins is not None, ctor):
        keys = [i for i, (pc, n, a) in enumerate(ins)
                if n == 'const/16' and a.endswith(f'#{E["stock_start_keycode"]}')]
        cfgs = [i for i, (pc, n, a) in enumerate(ins) if f'{config}-><init>(IZIIIIII)V' in a]
        ok_k = check('scrubdelete: stock keycode constant is unique', len(keys) == 1,
                     f'found {len(keys)}')
        ok_c = check('scrubdelete: config ctor call is unique', len(cfgs) == 1,
                     f'found {len(cfgs)}')
        if ok_k and ok_c:
            check('scrubdelete: keycode precedes the config ctor', keys[0] < cfgs[0])

    c, ins = body(dl, f'{SCRUB}->g(Landroid/view/MotionEvent;)V')
    if check('scrubdelete: g() exists', ins is not None):
        check('scrubdelete: g() register count', c['registers'] == E['scrub_g_registers'],
              f'got {c["registers"]}')
        reads = [i for i, (pc, n, a) in enumerate(ins)
                 if n == 'iget' and f'{config}->a:I' in a]
        # The patch selects the gate by shape — the read `if-ne` tests — because it adds a second
        # read of the same field for the full-height rect. Both predicates hold on a stock dex.
        gated = [i for i in reads if ins[i + 1][1] == 'if-ne']
        if check('scrubdelete: start-key read is unique', len(reads) == 1, f'found {len(reads)}'):
            gate = ins[reads[0] + 1]
            check('scrubdelete: if-ne follows the read', gate[1] == 'if-ne', gate[1])
            check('scrubdelete: the if-ne compares that register',
                  regs(ins[reads[0]][2])[0] in regs(gate[2])[:2])
        check('scrubdelete: exactly one if-ne-gated start-key read', len(gated) == 1,
              f'found {len(gated)}')
        # All reads must go through one object register, which is how trackAcrossFullKeyboard
        # finds the config without depending on which patch edited g() first.
        objs = {regs(ins[i][2])[1] for i in reads}
        check('scrubdelete: start-key reads share one object register', len(objs) == 1, str(objs))

        # ---- the tracking rect, which trackAcrossFullKeyboard gives the full keyboard height
        rect_regs = {}
        for edge in ('left', 'right', 'top', 'bottom'):
            w = [i for i, (pc, n, a) in enumerate(ins)
                 if n == 'iput' and f'Landroid/graphics/Rect;->{edge}:I' in a]
            if check(f'scrubdelete: one write to Rect.{edge}', len(w) == 1, f'found {len(w)}'):
                rect_regs[edge] = regs(ins[w[0]][2])
        check('scrubdelete: every Rect edge is the same object',
              len({v[1] for v in rect_regs.values()}) == 1,
              str({k: v[1] for k, v in rect_regs.items()}))

        width = [i for i, (pc, n, a) in enumerate(ins)
                 if f'{KEYBOARD_VIEW}->getWidth()I' in a]
        # Gboard's own full-width override is the precedent the vertical edit mirrors. If it ever
        # stops widening horizontally, "we widen the other axis the same way" needs re-examining.
        if check('scrubdelete: getWidth is called once in g()', len(width) == 1,
                 f'found {len(width)}'):
            bottom = [i for i, (pc, n, a) in enumerate(ins)
                      if n == 'iput' and 'Landroid/graphics/Rect;->bottom:I' in a]
            check('scrubdelete: getWidth precedes the bottom write',
                  bool(bottom) and width[0] < bottom[0])
        # The stock outset that the full-height write replaces the effect of. `unop82` is
        # int-to-float and `unop87` float-to-int; c7 is sub-float/2addr and c6 add-float/2addr, so
        # this confirms the top edge is widened upward and the bottom downward — an outset, not an
        # inset, whatever the field is named.
        outset = [n for pc, n, a in ins if n in ('binop2addrc6', 'binop2addrc7')]
        check('scrubdelete: the stock rect outset is one sub + one add',
              outset.count('binop2addrc7') >= 1 and outset.count('binop2addrc6') >= 1,
              str(outset))

        # The three registers the inserted block reads have to still hold what it assumes at the
        # insertion point. This is the argument the patch cannot make for itself — it derives each
        # register from the instruction that loads it and then trusts it across a gap — so it is
        # made here instead, against the real method body.
        if rect_regs and width:
            bottom_pc = [pc for pc, n, a in ins
                         if n == 'iput' and 'Landroid/graphics/Rect;->bottom:I' in a][0]
            loads = {
                'config': (regs(ins[reads[0]][2])[1], f':{config}'),
                'keyboard view': (regs(ins[width[0]][2])[0], f'{SCRUB}->d:'),
                'rect': (rect_regs['bottom'][1], f'{SCRUB}->h:'),
            }
            for what, (reg, marker) in loads.items():
                src = [pc for pc, n, a in ins
                       if n == 'iget-object' and marker in a and regs(a)[:1] == [reg]]
                if not check(f'scrubdelete: the {what} register is loaded in g()', bool(src),
                             f'v{reg} {marker}'):
                    continue
                clobbered = writes_before(ins, reg, src[-1], bottom_pc)
                check(f'scrubdelete: the {what} register survives to the insertion point',
                      not clobbered, f'v{reg} rewritten at {clobbered}')

    # ---- tuning
    c, _ = body(dl, f'{SCRUB}-><init>({CONTEXT}{delegate}{config})V')
    check('tuning: 3-arg engine ctor register count',
          c is not None and c['registers'] == E['engine_ctor_registers'],
          f'got {c and c["registers"]}')
    c, _ = body(dl, f'{SCRUB}-><init>({CONTEXT}{delegate}{config}J)V')
    check('tuning: 4-arg engine ctor exists', c is not None)

    handler_ctx = find_instance_field(dl, SCRUB, E['handler_context_field'])
    check('tuning: handler Context field resolves', handler_ctx is not None, str(handler_ctx))
    check('tuning: it is inherited, not declared',
          bool(handler_ctx) and handler_ctx.startswith(ABSTRACT_HANDLER), str(handler_ctx))
    check('tuning: its value is a Context',
          bool(handler_ctx) and handler_ctx.endswith(':' + CONTEXT))
    chain = superclass_chain(dl, SCRUB)
    check('tuning: `this` in r() can legally read it', ABSTRACT_HANDLER in chain, str(chain))

    # ---- start-key recovery, for the backspace-keeps-stock-behaviour edits
    c, ins = body(dl, f'{SCRUB}->g(Landroid/view/MotionEvent;)V')
    if ins:
        views = [i for i, (pc, n, a) in enumerate(ins)
                 if n == 'iput-object' and a.endswith(':Landroid/view/View;')]
        check('startkey: one View field written in g()', len(views) == 1, f'found {len(views)}')
        kd = [i for i, (pc, n, a) in enumerate(ins)
              if n.startswith('invoke') and a.endswith(')Lpnu;')]
        if check('startkey: one no-arg call returning Lpnu;', len(kd) == 1, f'found {len(kd)}'):
            # The chain is walked back from that unique anchor; f() itself is called twice, so it
            # can only be identified by which call feeds the key-data accessor.
            action_reg = regs(ins[kd[0]][2])[0]
            ri = [i for i in range(kd[0] - 1, -1, -1)
                  if ins[i][1] == 'move-result-object' and regs(ins[i][2])[0] == action_reg]
            if check('startkey: the ActionDef feeding it is produced in g()', bool(ri)):
                acc = ins[ri[0] - 1][2]
                check('startkey: it comes from an ActionDef accessor',
                      acc.endswith(')Lcom/google/android/libraries/inputmethod/metadata/ActionDef;'),
                      acc[-60:])
                sel_reg = regs(acc.split('}')[0])[1]
                si = [i for i in range(ri[0] - 2, -1, -1)
                      if ins[i][1] == 'sget-object' and regs(ins[i][2])[0] == sel_reg]
                check('startkey: its action selector is loaded in g()', bool(si))
                # Two Lpmy; statics are read in g(); the walk must land on the one the gate uses.
                if si:
                    sels = [a for pc, n, a in ins if n == 'sget-object' and 'Lpmy;' in a]
                    check('startkey: the selector is disambiguated, not guessed', len(sels) > 1,
                          f'only {len(sels)} candidate(s) — check is not discriminating')
        kc = [i for i, (pc, n, a) in enumerate(ins) if n == 'iget' and 'Lpnu;->' in a]
        check('startkey: one Lpnu; field read in g()', len(kc) == 1, f'found {len(kc)}')

    c, ins = body(dl, f'{SCRUB}->r(Landroid/view/MotionEvent;Z)V')
    if ins:
        absi = [i for i, (pc, n, a) in enumerate(ins) if 'Ljava/lang/Math;->abs(F)F' in a]
        if check('distance: Math.abs(F)F is unique in r()', len(absi) == 1, f'found {len(absi)}'):
            delta = regs(ins[absi[0]][2])[0]
            sub = [i for i in range(absi[0] - 1, -1, -1)
                   if regs(ins[i][2])[:1] == [delta] and not ins[i][1].startswith('if-')]
            if check('distance: the delta is written before it', bool(sub)):
                # binop2addrc7 is sub-float/2addr (0xc7).
                check('distance: it comes from a sub-float/2addr',
                      ins[sub[0]][1] == 'binop2addrc7', ins[sub[0]][1])
                # The scratch set, checked by real backward liveness rather than a forward scan --
                # a forward scan wrongly reports v3 free, because the if-gt guarding the table walk
                # branches past the write that makes it look dead.
                site = sub[0] + 1
                free = live_free(ins, c['registers'], ins[site][0])
                want = E['distance_scratch']
                check('distance: the scratch registers are dead at the insertion point',
                      all(r in free for r in want), f'free={free} want={want}')
                check('distance: v3 is correctly NOT among them', 3 not in free,
                      'v3 looks free but is read on the extrapolation path')

    c, ins = body(dl, f'{SCRUB}->r(Landroid/view/MotionEvent;Z)V')
    if check('tuning: r() exists', ins is not None):
        check('tuning: r() register count', c['registers'] == E['scrub_r_registers'],
              f'got {c["registers"]}')
        box = [i for i, (pc, n, a) in enumerate(ins) if 'Ljava/lang/Integer;->valueOf(I)' in a]
        if check('tuning: Integer.valueOf is unique', len(box) == 1, f'found {len(box)}'):
            count_reg = regs(ins[box[0]][2])[0]
            # binop2addrb2 is 0xb2 (mul-int/2addr) and binop92 is 0x92 (mul-int); dis.py prints
            # arithmetic as family placeholders that encode the opcode byte directly.
            prod = [i for i, (pc, n, a) in enumerate(ins)
                    if n in ('binop2addrb2', 'binop92') and regs(a)[:1] == [count_reg]]
            ok = check('tuning: exactly two count producers', len(prod) == 2,
                       f'found {len(prod)} writing v{count_reg}')
            scratch = E['clamp_scratch']
            this_reg = c['registers'] - 3
            check('tuning: scratch is distinct from count and this',
                  count_reg not in scratch and this_reg not in scratch,
                  f'count=v{count_reg} this=v{this_reg} scratch={scratch}')
            check('tuning: scratch fits a 35c invoke', all(r < 16 for r in scratch))
            if ok:
                convergence = ins[prod[-1] + 1][0]
                live = set()
                for pc, n, a in ins:
                    if pc >= convergence:
                        live.update(regs(a))
                check('tuning: scratch is dead from the convergence onward',
                      not (set(scratch) & live),
                      f'scratch={scratch} live at/after {convergence}={sorted(live)}')

    # ---- toolbar icon count
    #
    # The bar's own class name survives R8 (a layout addresses it as a string), and the anchor for
    # the ceiling is a *string literal*, which R8 never rewrites. So unlike everything above, only
    # the register numbers here can move between builds.
    _, clinit = body(dl, f'{ACCESS_POINTS_BAR}-><clinit>()V')
    check('toolbar: the bar declares config_max_access_points',
          clinit is not None and any('config_max_access_points' in a for pc, n, a in clinit),
          'the flag naming this class as the toolbar cap is gone')

    ctor = f'{ACCESS_POINTS_BAR}-><init>({CONTEXT}Landroid/util/AttributeSet;)V'
    c, ins = body(dl, ctor)
    if check('toolbar: the bar constructor exists', ins is not None, ctor):
        check('toolbar: its register count',
              c['registers'] == E['toolbar_ctor_registers'], f'got {c["registers"]}')
        check('toolbar: its parameter words',
              c['ins'] == E['toolbar_ctor_ins'], f'got {c["ins"]}')

        # Gboard's own starting count, read off the getInt the flag falls back to.
        #
        # This used to assert that the settings slider showed the same number, because it did: the
        # patch had no default and an untouched slider left the count wherever Gboard put it. The
        # first-run seed ended that relationship — Flexboard writes its own number now — so what is
        # left is a fact about Gboard worth noticing if it moves, and the docs quote it.
        gi = [i for i, (pc, n, a) in enumerate(ins)
              if 'Landroid/content/res/TypedArray;->getInt(II)I' in a]
        if check('toolbar: one getInt on the styled attributes', len(gi) == 1, f'found {len(gi)}'):
            default_reg = regs(ins[gi[0]][2])[2]
            src = [i for i in range(gi[0] - 1, -1, -1)
                   if ins[i][1].startswith('const') and regs(ins[i][2])[:1] == [default_reg]]
            literal = re.search(r'#(-?\d+)', ins[src[0]][2]) if src else None
            check("toolbar: Gboard's own starting count is unchanged",
                  literal is not None and int(literal.group(1)) == E['toolbar_stock_count'],
                  f'got {literal and literal.group(1)}, '
                  f'expected {E["toolbar_stock_count"]}')

        flag = [i for i, (pc, n, a) in enumerate(ins) if 'Lnxp;->g()Ljava/lang/Object;' in a]
        if check('toolbar: one flag read in the constructor', len(flag) == 1, f'found {len(flag)}'):
            # By field *type*, not by opcode: `iput` (0x59) covers int and float alike, and the two
            # dimensions read out of the same TypedArray follow just below. Restricting to after the
            # flag read is what excludes `->y:I`, written near the top.
            puts = [i for i, (pc, n, a) in enumerate(ins)
                    if i > flag[0] and n == 'iput' and a.rstrip().endswith(':I')]
            if check('toolbar: one int field written after it', len(puts) == 1,
                     f'found {len(puts)}'):
                site = ins[puts[0]][0]
                ceiling = regs(ins[puts[0]][2])[0]
                free = live_free(ins, c['registers'], site)
                want = E['toolbar_scratch']
                check('toolbar: the scratch registers are dead at the insertion point',
                      all(r in free for r in want), f'free={free} want={want}')
                # The standing guard. Everything else at this point is live: the TypedArray, the two
                # constants the dimension reads still need, and the Context the store is handed.
                held = [r for r in (0, 1, 3, 7) if r in free]
                check('toolbar: v0, v1, v3 and v7 are correctly NOT among them', not held,
                      f'{held} look free but are read after the ceiling is written')
                check('toolbar: the capacity register is not borrowed as scratch',
                      ceiling not in want, f'v{ceiling} is in {want}')

    # The capacity checked above is not the icon count, and mistaking one for the other is what
    # shipped this patch broken once. The count is `definedCountOnBar`, which Gboard names for us in
    # a log line and which sits after both gates that can override the capacity. These checks guard
    # the derivation that finds it, since it is an obfuscated letter that is never written down.
    log_hits = []
    for dex in dl:
        for _cls_name, _af, cls_data in dex.classes():
            if not cls_data:
                continue
            for m_name, _maf, m_off in dex.class_methods(cls_data):
                if not m_off:
                    continue
                try:
                    mc = dex.code(m_off)
                except Exception:
                    continue
                if any(mn == 'const-string' and txt and E['toolbar_count_log'] in txt
                       for _pc, _op, mn, txt in dex.walk(mc)):
                    log_hits.append(m_name)
    if check('toolbar: exactly one method logs definedCountOnBar', len(log_hits) == 1,
             str(log_hits)):
        _c, ins = body(dl, log_hits[0])

        def called(a):
            return a.split('}, ')[-1]

        counts = sorted({called(a) for _pc, mn, a in ins
                         if mn.startswith('invoke') and called(a).endswith('(I)I')})
        # One, not "at least one". The patch takes the sole (I)I call as the count; a second would
        # be picked between silently, and the log line says nothing about which is which.
        if check('toolbar: one (I)I call in it', len(counts) == 1, str(counts)):
            c, ins = body(dl, counts[0])
            if check('toolbar: the count method has a body', ins is not None, counts[0]):
                check('toolbar: its register count',
                      c['registers'] == E['toolbar_count_registers'], f'got {c["registers"]}')
                check('toolbar: its parameter words',
                      c['ins'] == E['toolbar_count_ins'], f'got {c["ins"]}')
                # The insertion is at method entry, so the proof that the scratch registers are
                # free is arithmetic rather than a liveness fixpoint: locals below the parameters
                # hold nothing before the first instruction runs.
                locals_ = c['registers'] - c['ins']
                check('toolbar: the scratch registers are locals at entry',
                      locals_ == len(E['toolbar_count_scratch']),
                      f'{locals_} locals, insertion needs {len(E["toolbar_count_scratch"])}')
                this_reg = c['registers'] - c['ins']
                capacity_reg = c['registers'] - 1
                # The device-class branch the unfolded override rides on. Gboard picks its own
                # preference key from device class, and a fold changes class when it opens, so this
                # is what makes the inner and outer screens separately configurable.
                sgets = [a for _pc, mn, a in ins if mn == 'sget-object']
                if check('toolbar: one enum constant chooses the preference key',
                         len(sgets) == 1, f'found {len(sgets)}'):
                    mode_type = sgets[0].rsplit(':', 1)[-1].strip()
                    constant = sgets[0].split(', ')[-1].strip()
                    # Resolved by name out of the enum's <clinit>, never by its letter: R8 rewrites
                    # the field and leaves the string. A letter that moved onto DEVICE_TABLET would
                    # put the override on the wrong screens and nothing else would notice.
                    _cc, cins = body(dl, f'{mode_type}-><clinit>()V')
                    named = next((i for i, (_pc, _mn, a) in enumerate(cins or [])
                                  if E['toolbar_foldable_name'] in a), None)
                    if check(f'toolbar: {mode_type} names {E["toolbar_foldable_name"]}',
                             named is not None):
                        stored = next((a for _pc, mn, a in cins[named:]
                                       if mn == 'sput-object' and a.rstrip().endswith(mode_type)),
                                      None)
                        check('toolbar: the key branch tests the foldable constant',
                              stored is not None and stored.split(', ')[-1].strip() == constant,
                              f'branch tests {constant}, foldable is {stored}')
                    modes = [a for _pc, mn, a in ins
                             if mn == 'iget-object' and a.rstrip().endswith(f':{mode_type}')]
                    if check('toolbar: one device-mode field read', len(modes) == 1,
                             f'found {len(modes)}'):
                        check('toolbar: the device mode is read off the receiver',
                              regs(modes[0])[1] == this_reg,
                              f'read off v{regs(modes[0])[1]}, receiver is v{this_reg}')
                # What makes this the method that *finishes* the calculation rather than a step
                # inside it. Insert before the gate and the value goes back where Gboard's own
                # count preference and its reduced mode can each override it.
                #
                # Signature alone is not enough to find it -- the store's own id-keyed getInt is
                # (II)I as well, and this check failed on that before it was narrowed. The gate is
                # the (II)I call the *capacity parameter* flows into; the store read is a call that
                # has nothing to do with it.
                gates = [i for i, (_pc, mn, a) in enumerate(ins)
                         if mn.startswith('invoke') and called(a).endswith('(II)I')
                         and capacity_reg in regs(a.split('}, ')[0])]
                if check('toolbar: it still applies the count gate', len(gates) == 1,
                         f'found {len(gates)}'):
                    # The other half, and the half that stops the check above from passing on a
                    # coincidence: the gate's result must be what the method hands back. Input and
                    # output together say the body is `return gate(..., capacity)`, which is the
                    # property the patch actually depends on -- overriding at entry outranks the
                    # gate only if the gate is the last word on the value.
                    i = gates[0]
                    flows = (i + 2 < len(ins)
                             and ins[i + 1][1] == 'move-result'
                             and ins[i + 2][1] == 'return'
                             and regs(ins[i + 1][2]) == regs(ins[i + 2][2]))
                    check('toolbar: the gate result is what it returns', flows,
                          f'{[n for _pc, n, _a in ins[i:i + 3]]}')
                stores = [a for _pc, mn, a in ins
                          if mn == 'iget-object' and a.rstrip().endswith(f':{B["store"]}')]
                # The preference store is read out of this method rather than named, so exactly one
                # such field must be touched -- and off the receiver, or `iget-object ... p0` in the
                # emitted code reads the wrong object.
                if check('toolbar: one preference-store field read in it', len(stores) == 1,
                         f'found {len(stores)}'):
                    check('toolbar: it is read off the receiver',
                          regs(stores[0])[1] == this_reg,
                          f'read off v{regs(stores[0])[1]}, receiver is v{this_reg}')

    # ---- text editing buttons
    #
    # Both insertion points are derived structurally rather than named, so what these checks guard
    # is the *shape* the derivation relies on -- that each one still resolves to exactly one method.
    # A second match is as much a failure as none: the patch would pick one and give no sign.
    seed_literals = set(E['buttons_seed_literals'])
    keycode = E['buttons_runnable_keycode']
    keycode_masked = keycode & 0xffffffff
    seeds, splits, runners = [], [], []
    # Every method that reads an int field, by field. Collected here because the hotkey labels rest
    # on nothing outside one accessor reading the access point's label *resource id* -- see the
    # check further down -- and a second sweep to answer that would double how long preflight takes.
    int_reads = {}
    for dex in dl:
        for cls_name, _af, cls_data in dex.classes():
            if not cls_data:
                continue
            for m_name, _maf, m_off in dex.class_methods(cls_data):
                if not m_off:
                    continue
                try:
                    mc = dex.code(m_off)
                except Exception:
                    continue
                lits, calls = set(), []
                for _pc, _op, mn, txt in dex.walk(mc):
                    if mn and mn.startswith('const') and txt:
                        try:
                            lits.add(int(txt, 16))
                        except ValueError:
                            pass
                    elif mn and mn.startswith('invoke') and txt:
                        calls.append(txt)
                    elif mn == 'iget' and txt:
                        int_reads.setdefault(txt, set()).add(m_name)
                if seed_literals <= lits:
                    seeds.append(m_name)
                if m_name.endswith('(Ljava/util/List;)V'):
                    subs = calls.count('Ljava/util/List;->subList(II)Ljava/util/List;')
                    if subs == 2 and 'Ljava/lang/Math;->min(II)I' in calls:
                        splits.append(m_name)
                # The dispatcher that makes the button do anything: it both sees the Runnable
                # keycode and calls run(). Two other classes test that keycode and *decline* it,
                # so "something mentions the keycode" would be the wrong test.
                #
                # The keycode reaches the real dispatcher through a packed-switch, so it is not a
                # literal there and switch_keys is what finds it. Checking only `lits` reports the
                # dispatcher missing on a build where it is present and working.
                if 'Ljava/lang/Runnable;->run()V' in calls:
                    if (keycode in lits or keycode_masked in lits
                            or keycode in switch_keys(dex, mc)):
                        runners.append(m_name)

    check('buttons: a dispatcher turns the Runnable keycode into run()', bool(runners),
          f'no method both sees {hex(keycode_masked)} and calls Runnable.run()')

    if check('buttons: exactly one access-point seed method', len(seeds) == 1, str(seeds)):
        c, ins = body(dl, seeds[0])
        # The setters are told apart by the literal handed to each, so each literal must appear
        # once. Two occurrences and the derivation picks the first, silently.
        for want in E['buttons_seed_literals']:
            n = sum(1 for _pc, mn, a in ins
                    if mn.startswith('const') and re.search(r'0x[0-9a-f]+', a)
                    and int(re.search(r'0x[0-9a-f]+', a).group(), 16) == want)
            check(f'buttons: seed loads {hex(want)} exactly once', n == 1, f'found {n}')
        # `args` is "{v0, v1}, Lowner;->name(...)ret" -- the descriptor is what follows the
        # register list, so parsing has to drop that first.
        def called(a):
            return a.split('}, ')[-1]

        def field_of(a):
            """The field descriptor an iget/iput operand text ends with."""
            return a.rsplit(', ', 1)[-1].strip()

        def literal_of(a):
            """The `#N` an instruction carries, or None. Read off the operand rather than the
            mnemonic: dis.py prints the arithmetic opcodes as family placeholders."""
            m = re.search(r'#(-?0x[0-9a-f]+|-?\d+)', a)
            return int(m.group(1), 0) if m else None

        builder = access_point = None
        for _pc, mn, a in ins:
            if mn.startswith('invoke') and '()' in called(a) and not called(a).endswith(')V'):
                builder = called(a).split(')')[-1]
                # The type declaring the factory is also what the build method returns, which is
                # what the patch derives it as.
                access_point = called(a).split('->')[0]
                break
        if check('buttons: the seed opens a builder', builder is not None):
            setters = [called(a) for _pc, mn, a in ins
                       if mn.startswith('invoke') and called(a).startswith(f'{builder}->')
                       and called(a).endswith('(I)V')]
            # Distinct, not merely three calls. The patch tells the icon, label and content
            # description apart *by which setter each literal reaches*; if all three literals
            # came to reach the same setter, counting call sites would still say three and the
            # button would be built with two of its three properties silently unset.
            check('buttons: it drives three distinct (I)V setters',
                  len(set(setters)) == 3, f'found {len(set(setters))} distinct of {len(setters)}')
            # The very ambiguity the derivation exists to route around -- if this ever drops to
            # one, naming the setter would have been safe and this machinery is over-built.
            d_b, sup_b, cd_b = find_class(dl, builder)
            if check('buttons: the builder class is present', d_b is not None):
                same = [m for m, _a, _o in d_b.class_methods(cd_b) if m.endswith('(I)V')]
                check('buttons: (I)V is still ambiguous on the builder', len(same) > 1,
                      f'only {len(same)}: naming it would now be safe')
                # Mirrors the patch's soleBuilderMethod assertions. All four, not three -- the
                # build method is as much a derivation as the setters, and leaving it out means a
                # Gboard bump that grows a sibling returning the access-point type reports green
                # here and throws at apply time.
                for sig, what in (('(Ljava/lang/String;)V', 'id setter'),
                                  ('(Ljava/lang/Runnable;)V', 'action setter'),
                                  ('(Ljava/lang/String;Ljava/lang/Object;)V', 'extras setter'),
                                  (f'(){access_point}', 'build method')):
                    n = sum(1 for m, _a, _o in d_b.class_methods(cd_b) if m.endswith(sig))
                    check(f'buttons: exactly one {what} on the builder', n == 1, f'found {n}')

                # The other half of the mechanism: the action setter is what bakes the keycode the
                # dispatcher above switches on. If it stops doing that, the button still builds
                # and still renders, and tapping it does nothing at all.
                action = next((m for m, _a, _o in d_b.class_methods(cd_b)
                               if m.endswith('(Ljava/lang/Runnable;)V')), None)
                if check('buttons: the builder has a Runnable setter to inspect', action):
                    _ac, a_ins = body(dl, action)
                    lits = set()
                    for _pc, mn, a in a_ins or []:
                        m = re.search(r'#(-?0x[0-9a-f]+|-?\d+)', a)
                        if mn.startswith('const') and m:
                            lits.add(int(m.group(1), 0) & 0xffffffff)
                    check('buttons: the action setter still bakes the Runnable keycode',
                          keycode_masked in lits,
                          f'{hex(keycode_masked)} not among {sorted(hex(x) for x in lits)}')

                # ---- how the setters are told apart, and where a literal label goes
                #
                # The builder is generated code that refuses to build an incomplete access point
                # and names what is missing. Each property it names is tested against one bit of a
                # completeness mask, and exactly one (I)V setter writes that bit -- so a bit leads
                # from a setter to a *string literal* naming what it sets, which is the one kind of
                # anchor R8 cannot rename.
                #
                # Everything below mirrors resolveAccessPointBuilder step for step. It replaced a
                # derivation that read the setters off the values Gboard's seed handed them, which
                # could not tell the label from the content description because the seed passes
                # both the same string. That was harmless while both were set to the same text and
                # is not harmless now: a hotkey's label is a literal written beside the label
                # resource id, and writing it beside the content description instead would leave
                # every hotkey on the bar named "Text editing".
                build = next((m for m, _a, _o in d_b.class_methods(cd_b)
                              if m.endswith(f'(){access_point}')), None)
                if check('buttons: the builder has a build method to inspect', build):
                    _bc, b_ins = body(dl, build)
                    b_ins = b_ins or []

                    masks ={field_of(a) for _pc, mn, a in b_ins if mn == 'iget-byte'}
                    if check('buttons: the builder has one completeness mask',
                             len(masks) == 1, f'byte fields read: {sorted(masks)}'):
                        mask = masks.pop()

                        # bit -> (setter, the int field it writes). The mask write is what tells a
                        # property setter from the builder's other (I)V methods: one of those is a
                        # convenience that sets several properties at once, and it loads a
                        # bit-shaped literal of its own while writing no mask at all.
                        by_bit = {}
                        for m, _a, _o in d_b.class_methods(cd_b):
                            if not m.endswith('(I)V'):
                                continue
                            _sc, s_ins = body(dl, m)
                            s_ins = s_ins or []
                            if not any(mn == 'iput-byte' and field_of(a) == mask
                                       for _pc, mn, a in s_ins):
                                continue
                            bits = [literal_of(a) for _pc, _mn, a in s_ins
                                    if literal_of(a) is not None]
                            written = [field_of(a) for _pc, mn, a in s_ins if mn == 'iput']
                            if check(f'buttons: {m.split("->")[1]} contributes one bit and one '
                                     f'field', len(bits) == 1 and len(written) == 1,
                                     f'bits={bits} fields={written}'):
                                check(f'buttons: bit {bits[0]} of the mask has one setter',
                                      bits[0] not in by_bit,
                                      f'{m} and {by_bit.get(bits[0], (None,))[0]} share it')
                                by_bit[bits[0]] = (m, written[0])

                        resource_fields = {}
                        for name in E['hotkey_properties']:
                            named = [i for i, (_pc, _mn, a) in enumerate(b_ins)
                                     if a.endswith(repr(name))]
                            if not check(f'buttons: the builder names the{name} property once',
                                         len(named) == 1, f'found {len(named)}'):
                                continue
                            tested = next((literal_of(a) for _pc, _mn, a
                                           in reversed(b_ins[:named[0]])
                                           if literal_of(a) is not None), None)
                            if check(f'buttons: a mask bit precedes the{name} property',
                                     tested is not None):
                                if check(f'buttons: bit {tested} for{name} has a setter',
                                         tested in by_bit,
                                         f'known bits {sorted(by_bit)}'):
                                    resource_fields[name] = by_bit[tested][1]

                        # The literal that pairs with a resource id. `build` reads the builder's
                        # fields straight into the constructor's argument registers, in constructor
                        # order, and the generated constructor takes each property as a resource id
                        # *immediately* followed by its literal -- so the literal is the very next
                        # field read.
                        #
                        # Adjacency, not "the next String somewhere after". Only some properties
                        # carry a String literal: the icon's is an Icon, so a looser rule walks
                        # past it and lands on the label's, reporting a field that belongs to a
                        # different property. This check is how that was found.
                        id_setter = next((m for m, _a, _o in d_b.class_methods(cd_b)
                                          if m.endswith('(Ljava/lang/String;)V')), None)
                        _ic, id_ins = body(dl, id_setter) if id_setter else (None, [])
                        id_fields = [field_of(a) for _pc, mn, a in id_ins or []
                                     if mn == 'iput-object']

                        literals = {}
                        for name in E['hotkey_literal_properties']:
                            resource = resource_fields.get(name)
                            if resource is None:
                                continue
                            at = next((i for i, (_pc, mn, a) in enumerate(b_ins)
                                       if mn == 'iget' and field_of(a) == resource), None)
                            if not check(f'buttons: build reads the{name} resource id',
                                         at is not None, f'{resource} never read'):
                                continue
                            after = next(((mn, field_of(a)) for _pc, mn, a in b_ins[at + 1:]
                                          if mn.startswith('iget')), None)
                            if not check(f'buttons: a field follows it for{name}', after):
                                continue
                            if check(f'buttons: the{name} literal is a String',
                                     after[0] == 'iget-object'
                                     and after[1].endswith(':Ljava/lang/String;'),
                                     f'{after[1]} follows {resource}'):
                                check(f'buttons: the{name} literal is not the access point id',
                                      after[1] not in id_fields,
                                      f'{after[1]} is what the id setter writes')
                                literals[name] = after[1]

                        check('buttons: the label and content description have separate literals',
                              len(set(literals.values())) == len(literals),
                              f'{literals}')

            # ---- the one thing a hotkey's label rests on
            #
            # A hotkey has no Gboard string to name it, so the patch sets the label *resource id*
            # to zero and writes the user's snippet into the literal beside it. That is only sound
            # because the accessor below is the only thing that reads the resource id: anything
            # rendering from it directly would draw an empty name on every hotkey, and nothing
            # short of a device would say so.
            d_a, _sup_a, cd_a = find_class(dl, access_point)
            if check('buttons: the access point class is present', d_a is not None):
                accessors = [m for m, _a, _o in d_a.class_methods(cd_a)
                             if m.endswith('(Landroid/content/Context;)Ljava/lang/String;')]
                if check('buttons: exactly one label accessor on the access point',
                         len(accessors) == 1, str(accessors)):
                    _hc, h_ins = body(dl, accessors[0])
                    h_ins = h_ins or []
                    ints = [field_of(a) for _pc, mn, a in h_ins if mn == 'iget']
                    strings = [field_of(a) for _pc, mn, a in h_ins if mn == 'iget-object']
                    if check('buttons: the accessor reads one resource id and one String',
                             len(ints) == 1 and len(strings) == 1,
                             f'ints={ints} strings={strings}'):
                        check('buttons: it falls back to the literal when the id is zero',
                              len(h_ins) > 1 and h_ins[0][1] == 'iget'
                              and h_ins[1][1] == 'if-eqz',
                              f'{[mn for _pc, mn, _a in h_ins[:3]]}')
                        readers = int_reads.get(ints[0], set())
                        check('buttons: the accessor is among the readers of the label id',
                              accessors[0] in readers, f'readers: {sorted(readers)}')
                        # equals, hashCode and the builder's copy constructor also read it, and
                        # none of them renders anything. Anything *outside* those two classes does.
                        outside = sorted(m for m in readers
                                         if not m.startswith(f'{access_point}->')
                                         and not m.startswith(f'{builder}->'))
                        check('buttons: nothing outside the access point reads the label id',
                              not outside, f'also read by {outside}')

                # ---- why a zero resource id is safe at all
                #
                # A hotkey hands the builder zero for its label and content description, and takes
                # the literal instead. The label is safe because only the accessor above reads it,
                # but the content description is *not* like that: four rendering methods read its
                # resource id straight off the access point and pass it to Context.getString.
                #
                # Every one of them guards with if-eqz first, so zero means "no resource" rather
                # than a lookup of resource 0 -- which would throw NotFoundException while the
                # toolbar is being built, on a keyboard, from a background of nothing. Nothing else
                # checks that, and it is the one fact standing between a hotkey and a crash loop.
                get_string = 'Landroid/content/Context;->getString(I)Ljava/lang/String;'
                unguarded = []
                for field, methods in int_reads.items():
                    if not field.startswith(f'{access_point}->'):
                        continue
                    for m in methods:
                        _rc, r_ins = body(dl, m)
                        for i, (_pc, mn, a) in enumerate(r_ins or []):
                            if mn != 'iget' or field_of(a) != field:
                                continue
                            into = regs(a)[0]
                            ahead = (r_ins or [])[i + 1:i + 4]
                            uses = next((j for j, (_p, n, t) in enumerate(ahead)
                                         if n.startswith('invoke') and get_string in t
                                         and into in regs(t.split('}')[0])), None)
                            if uses is None:
                                continue
                            guarded = any(n.startswith('if-eqz') and regs(t)[:1] == [into]
                                          for _p, n, t in ahead[:uses])
                            if not guarded:
                                unguarded.append(f'{m} @{_pc} ({field})')
                check('buttons: every resource id read off the access point is zero-guarded',
                      not unguarded, f'{sorted(unguarded)} would call getString(0)')

    # The label id is the one fact this feature rests on that has NO anchor in the dex: unlike the
    # icon, 0x7f140576 has zero const sites, because nothing in stock Gboard loads it the way the
    # patch does. So it cannot be checked without the resource table, and until the APK argument
    # existed this constant sat in EXPECTED asserted by nothing while its comment claimed
    # otherwise. A bump renumbers string resources, and the button would ship labelled with
    # whatever the id came to mean.
    if apk is None:
        check.skip('buttons: the label and icon ids still mean what they say',
                   'no APK given; pass one as the second argument to check resource ids')
    else:
        try:
            import zipfile

            import arsc
            table = arsc.load(zipfile.ZipFile(apk).read('resources.arsc'))
            zf = zipfile.ZipFile(apk)
            icon = table.name(E['buttons_seed_literals'][0])
            check('buttons: the seed icon id is still a drawable',
                  str(icon).startswith('drawable/'), f'reads {icon!r}')

            import re as _re
            import axml

            def glyph(rid):
                src = _re.search(r"res/[^']+\.xml", str(table.value(rid)))
                if not src:
                    return ''
                return ''.join(str(at.get('pathData', ''))
                               for _d, _t, at in axml.parse(zf.read(src.group(0))))

            # Every button's label and icon, none of which has any anchor in the dex: nothing in
            # stock Gboard loads either the way this patch does, so without the resource table they
            # would sit in EXPECTED asserted by nothing. A bump renumbers resources, and the buttons
            # would ship labelled and drawn as whatever the ids came to mean -- Copy wearing Paste's
            # icon is exactly the kind of wrong that looks deliberate.
            #
            # The icon is checked by *glyph*, not by type. A renumbering would still land on
            # something reading 'drawable/', so the path signature each was found by is the check.
            for name, label_id, icon_id, signature in E['buttons_resources']:
                label = table.value(label_id)
                check(f'buttons: the {name} label still reads "{name}"',
                      str(label).lower() == name.lower(),
                      f'{hex(label_id)} now reads {label!r}')
                drawable = table.name(icon_id)
                if check(f'buttons: the {name} icon id is still a drawable',
                         str(drawable).startswith('drawable/'), f'reads {drawable!r}'):
                    check(f'buttons: it is still the {name} glyph',
                          signature in glyph(icon_id),
                          f'{hex(icon_id)} no longer draws it')

            # The hotkey icons have no label to go with them -- a hotkey is named by the user's own
            # snippet -- so the glyph is the whole of what the check can be about.
            check('hotkeys: there is an icon per slot',
                  len(E['hotkey_icons']) == E['hotkey_slots'],
                  f"{len(E['hotkey_icons'])} icons for {E['hotkey_slots']} slots")
            check('hotkeys: no two slots share an icon',
                  len({i for _n, i, _s in E['hotkey_icons']}) == len(E['hotkey_icons']),
                  'two slots would be indistinguishable on the bar')
            for name, icon_id, signature in E['hotkey_icons']:
                drawable = table.name(icon_id)
                if check(f'hotkeys: the {name} icon id is still a drawable',
                         str(drawable).startswith('drawable/'), f'reads {drawable!r}'):
                    check(f'hotkeys: it is still the {name} glyph',
                          signature in glyph(icon_id),
                          f'{hex(icon_id)} no longer draws it')
        except Exception as exc:
            check('buttons: the label id still reads "Select all"', False,
                  f'could not read resources from {apk}: {exc}')

    if check('buttons: exactly one access-points split method', len(splits) == 1, str(splits)):
        c, ins = body(dl, splits[0])
        check('buttons: the split register count',
              c['registers'] == E['buttons_split_registers'], f'got {c["registers"]}')
        check('buttons: the split parameter words',
              c['ins'] == E['buttons_split_ins'], f'got {c["ins"]}')
        free = live_free(ins, c['registers'], 0)
        want = E['buttons_split_scratch']
        check('buttons: the scratch registers are dead at the split entry',
              all(r in free for r in want), f'free={free} want={want}')
        # The list parameter is substituted wholesale at entry, so it has to still be genuinely an
        # input: some path must read it before writing it.
        #
        # "Never written" is the wrong test and fails here for a benign reason. The method opens
        # with an early return taken when the bar view is null, and that path reuses the parameter
        # register as scratch before returning -- it never reads the list at all. Backward liveness
        # over the real CFG answers the question that actually matters, and answers it soundly:
        # if the parameter is live at entry, every path that reads it reads what was passed in.
        p1 = c['registers'] - c['ins'] + 1
        check('buttons: the list parameter is live at entry', p1 not in free,
              f'v{p1} is dead at entry, so substituting it would reach nothing')

    # Read superclasses straight out of each class_def rather than resolving every class through
    # find_class, which is a scan per class and turns this into minutes.
    imes = []
    for dex in dl:
        for i in range(dex.cls_n):
            ci, _af, su, _io, _sf, _ao, _cd, _sv = struct.unpack_from(
                '<8I', dex.b, dex.cls_o + 32 * i)
            if su != 0xffffffff and dex.type(su) == 'Landroid/inputmethodservice/InputMethodService;':
                imes.append(dex.type(ci))
    if check('buttons: exactly one InputMethodService subclass', len(imes) == 1, str(imes)):
        c, ins = body(dl, f'{imes[0]}->onCreate()V')
        if check('buttons: it declares onCreate()V', ins is not None):
            check('buttons: its register count',
                  c['registers'] == E['buttons_oncreate_registers'], f'got {c["registers"]}')
            free = live_free(ins, c['registers'], 0)
            check('buttons: v0 is dead at onCreate entry', 0 in free, f'free={free}')

    # ---- forced preferences, flick symbols and the first-run seed share this hook
    c, _ = body(dl, f'{LATIN_APP}->d({store})V')
    check('prefs: applyPreferenceValues exists', c is not None)
    check('prefs: its register count', c is not None
          and c['registers'] == E['apply_preferences_registers'],
          f'got {c and c["registers"]}')
    # `this` plus the store. The seed passes p0 — the LatinApp, and so a Context — straight to the
    # extension, which is only sound if the parameter list is still the one that says so.
    check('prefs: its parameter words', c is not None
          and c['ins'] == E['apply_preferences_ins'], f'got {c and c["ins"]}')
    # ...and only encodable if p0 fits the four-bit register field of a 35c invoke. Emitting a `pN`
    # an invoke cannot address is what produced an unappliable bundle once before, and it is not
    # visible in Kotlin, in smali, or anywhere but on the phone that refuses the patch.
    if c is not None:
        receiver = c['registers'] - c['ins']
        check('prefs: p0 is addressable by a packed invoke',
              receiver < PACKED_INVOKE_REGISTER_LIMIT,
              f'p0 is v{receiver}; the seed would need move-object/from16 first')

    # ---- bypass signature
    sig_cls = B['sigcheck']
    c, ins = body(dl, f'{sig_cls}->a({CONTEXT}Ljava/lang/String;)Z')
    if check('bypass: signature check exists', ins is not None):
        check('bypass: register count', c['registers'] == E['sigcheck_registers'],
              f'got {c["registers"]}')
        returns = [regs(a)[0] for pc, n, a in ins if n == 'return']
        check('bypass: return registers', returns == E['sigcheck_returns'], str(returns))
        seen = {a.split(', ')[-1] for pc, n, a in ins if n.startswith(('sget', 'iget'))}
        for fd in (f'{sig_cls}->e:[B', f'{sig_cls}->d:[B', f'{sig_cls}->c:[B',
                   f'{B["test_environment"]}->b:Z'):
            check(f'bypass: reads {fd}', fd in seen)
        c2, _ = body(dl, f'{sig_cls}->c({CONTEXT}Ljava/lang/String;)[B')
        check('bypass: digest method exists', c2 is not None)

    failed = check.finish()
    print('resolved handler Context field: ', handler_ctx)
    return failed


def main():
    if len(sys.argv) not in (2, 3):
        print(__doc__.strip().split('## Use')[1].split('## Updating')[0].strip(), file=sys.stderr)
        return 2
    tree = sys.argv[1]
    # Optional, because most checks only need the dex. The resource ids a patch emits cannot be
    # checked without it, and those checks report SKIP rather than passing when it is absent.
    apk = sys.argv[2] if len(sys.argv) == 3 else None
    dl = dexlib.load(tree)
    if not dl:
        print(f'no .dex files in {tree}', file=sys.stderr)
        return 2
    print(f'{len(dl)} dex files from {tree}\n')
    return 1 if run(dl, apk) else 0


if __name__ == '__main__':
    sys.exit(main())
