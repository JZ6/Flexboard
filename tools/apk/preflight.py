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
KEYBOARD_VIEW = 'Lcom/google/android/libraries/inputmethod/widgets/SoftKeyboardView;'
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
