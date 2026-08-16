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
LATIN_IME = 'Lcom/google/android/apps/inputmethod/libs/latin5/LatinIme;'
ABSTRACT_IME = 'Lcom/google/android/libraries/inputmethod/ime/AbstractIme;'
LATIN_APP = 'Lcom/google/android/apps/inputmethod/latin/LatinApp;'
CONTEXT = 'Landroid/content/Context;'

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
    'sigcheck_flag': 'Lrox;',
}

EXPECTED = {
    'dispatcher_name': 'q',              # LatinIme's event dispatcher
    'dispatcher_registers': 34,
    'suppressed_field': 'O',             # AbstractIme's suppression flag
    'store_singleton': 'I',              # Lqhy;->I(Context)Lqhy;
    'store_contains': 'ak',              # contains, keyed by resource id
    'store_write': 'T',                  # (I, Object) -> void
    'ime_context_field': 'B',
    'handler_context_field': 'o',
    'undo_slot_field': 'y',
    'recommit': 'Lcom/google/android/libraries/inputmethod/ime/AbstractIme;->t(Lojt;Z)V',
    'recommit_window': 40,
    'scrub_g_registers': 13,
    'scrub_r_registers': 13,
    'delete_ctor_registers': 12,
    'engine_ctor_registers': 11,
    'apply_preferences_registers': 13,
    'sigcheck_registers': 8,
    'sigcheck_returns': [6, 4, 3],
    'undo_scratch': [2, 3],
    'clamp_scratch': [5, 7, 9],
    'stock_start_keycode': 67,
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


def body(dl, descriptor):
    d, c, maf = ddis.find(descriptor, dl)
    if not c:
        return None, None
    return c, ddis.disasm(d, c)


def regs(arg):
    return [int(x) for x in re.findall(r'v(\d+)', arg)]


# --------------------------------------------------------------------------- checks

class Report:
    def __init__(self):
        self.rows = []

    def __call__(self, name, ok, detail=''):
        self.rows.append((bool(ok), name, detail))
        return bool(ok)

    def finish(self):
        width = max(len(n) for _, n, _ in self.rows)
        failed = 0
        for ok, name, detail in self.rows:
            failed += not ok
            line = f'{"PASS" if ok else "FAIL"}  {name:<{width}}  {detail if not ok else ""}'
            print(line.rstrip())
        print(f'\n{len(self.rows) - failed}/{len(self.rows)} passed')
        return failed


def run(dl):
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

    ime_ctx = find_instance_field(dl, ABSTRACT_IME, E['ime_context_field'])
    check('undo: IME Context field resolves', ime_ctx is not None, str(ime_ctx))
    check('undo: its value is a Context', bool(ime_ctx) and ime_ctx.endswith(':' + CONTEXT),
          str(ime_ctx))

    slot = B['undo_slot']
    for sig in (f'{slot}->d()Z', f'{slot}->a()Lj$/util/Optional;', f'{slot}->c()V'):
        c, _ = body(dl, sig)
        check(f'undo: {sig}', c is not None)

    # The re-commit, resolved the way the patch resolves it: from the call Gboard's own undo makes.
    #
    # Checking only that a named method *exists* is what let `0.0.3-dev.1` ship broken. On 18 the
    # re-commit is `AbstractIme->t`, while `s` — the 17.7.7 name — still exists with a
    # signature-compatible shape and an empty base declaration. Both resolve, both verify, and the
    # wrong one silently does nothing. Only the stock handler distinguishes them.
    c, ins = body(dl, dispatch)
    if ins:
        gets = [i for i, (pc, n, a) in enumerate(ins) if f'{slot}->a()Lj$/util/Optional;' in a]
        if check('undo: undo-slot get is unique in the dispatcher', len(gets) == 1,
                 f'found {len(gets)}'):
            window = ins[gets[0] + 1:gets[0] + 1 + E['recommit_window']]
            found = [a.split(', ')[-1] for pc, n, a in window
                     if n.startswith('invoke') and RECOMMIT_RE.match(a.split(', ')[-1])]
            if check('undo: stock undo re-commits via an AbstractIme hook', bool(found),
                     'no AbstractIme->…(L…;Z)V call follows the slot read'):
                resolved = found[0]
                check('undo: the re-commit is the expected one',
                      resolved == E['recommit'], f'stock calls {resolved}')
                committable = RECOMMIT_RE.match(resolved).group(1)
                check('undo: committable-text type matches the cast',
                      committable == B['committable'],
                      f'stock casts to {committable}, bindings say {B["committable"]}')
                # An empty base declaration means the subclass override is what runs; that is
                # exactly why the two hooks are indistinguishable without this call site.
                owner_free = resolved.replace(ABSTRACT_IME, LATIN_IME)
                c2, _ = body(dl, owner_free)
                check('undo: LatinIme overrides it', c2 is not None, owner_free)

    d, sup, cd = find_class(dl, LATIN_IME)
    held = [fd for fd, static in class_fields(d, cd)
            if fd.endswith(f'->{E["undo_slot_field"]}:{slot}')]
    check('undo: LatinIme holds the undo slot', len(held) == 1, str(held))

    # ---- swipe to delete
    ctor = f'{SCRUB_DELETE}-><init>({CONTEXT}{delegate})V'
    c, ins = body(dl, ctor)
    if check('scrubdelete: delete ctor exists', ins is not None, ctor):
        check('scrubdelete: delete ctor register count',
              c['registers'] == E['delete_ctor_registers'], f'got {c["registers"]}')
        keys = [i for i, (pc, n, a) in enumerate(ins)
                if n == 'const/16' and a.endswith(f'#{E["stock_start_keycode"]}')]
        cfgs = [i for i, (pc, n, a) in enumerate(ins) if f'{config}-><init>(IZIIIIII)V' in a]
        ok_k = check('scrubdelete: stock keycode constant is unique', len(keys) == 1,
                     f'found {len(keys)}')
        ok_c = check('scrubdelete: config ctor call is unique', len(cfgs) == 1,
                     f'found {len(cfgs)}')
        if ok_k and ok_c:
            check('scrubdelete: keycode precedes the config ctor', keys[0] < cfgs[0])
            window = ins[keys[0] + 1:cfgs[0]]
            computed = [n for pc, n, a in window if not n.startswith('const')]
            check('scrubdelete: every argument between them is a const', not computed,
                  str(computed))
            key_reg = regs(ins[keys[0]][2])[0]
            ctx_reg = c['registers'] - 3 + 1
            free = [r for pc, n, a in window for r in regs(a)[:1]]
            free = [r for r in dict.fromkeys(free)
                    if r not in (key_reg, ctx_reg) and r < 16]
            check('scrubdelete: three scratch registers are free', len(free) >= 3, str(free))
            clobbered = any(regs(a)[:1] == [ctx_reg] for pc, n, a in ins[:keys[0]])
            check('scrubdelete: Context register is not clobbered first', not clobbered,
                  f'v{ctx_reg}')

    c, ins = body(dl, f'{SCRUB}->g(Landroid/view/MotionEvent;)V')
    if check('scrubdelete: g() exists', ins is not None):
        check('scrubdelete: g() register count', c['registers'] == E['scrub_g_registers'],
              f'got {c["registers"]}')
        reads = [i for i, (pc, n, a) in enumerate(ins)
                 if n == 'iget' and f'{config}->a:I' in a]
        if check('scrubdelete: start-key read is unique', len(reads) == 1, f'found {len(reads)}'):
            gate = ins[reads[0] + 1]
            check('scrubdelete: if-ne follows the read', gate[1] == 'if-ne', gate[1])
            check('scrubdelete: the if-ne compares that register',
                  regs(ins[reads[0]][2])[0] in regs(gate[2])[:2])

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

    # ---- forced preferences and flick symbols share this hook
    c, _ = body(dl, f'{LATIN_APP}->d({store})V')
    check('prefs: applyPreferenceValues exists', c is not None)
    check('prefs: its register count', c is not None
          and c['registers'] == E['apply_preferences_registers'],
          f'got {c and c["registers"]}')

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
                   f'{B["sigcheck_flag"]}->b:Z'):
            check(f'bypass: reads {fd}', fd in seen)
        c2, _ = body(dl, f'{sig_cls}->c({CONTEXT}Ljava/lang/String;)[B')
        check('bypass: digest method exists', c2 is not None)

    failed = check.finish()
    print('resolved IME Context field:     ', ime_ctx)
    print('resolved handler Context field: ', handler_ctx)
    return failed


def main():
    if len(sys.argv) != 2:
        print(__doc__.strip().split('## Use')[1].split('## Updating')[0].strip(), file=sys.stderr)
        return 2
    tree = sys.argv[1]
    dl = dexlib.load(tree)
    if not dl:
        print(f'no .dex files in {tree}', file=sys.stderr)
        return 2
    print(f'{len(dl)} dex files from {tree}\n')
    return 1 if run(dl) else 0


if __name__ == '__main__':
    sys.exit(main())
