"""Full-format Dalvik disassembler on top of dexlib."""
import struct
import dexlib

N = {}
def _n(o, name, fmt): N[o] = (name, fmt)

_n(0x00,'nop','10x'); _n(0x01,'move','12x'); _n(0x02,'move/from16','22x'); _n(0x03,'move/16','32x')
_n(0x04,'move-wide','12x'); _n(0x05,'move-wide/from16','22x'); _n(0x06,'move-wide/16','32x')
_n(0x07,'move-object','12x'); _n(0x08,'move-object/from16','22x'); _n(0x09,'move-object/16','32x')
_n(0x0a,'move-result','11x'); _n(0x0b,'move-result-wide','11x'); _n(0x0c,'move-result-object','11x')
_n(0x0d,'move-exception','11x'); _n(0x0e,'return-void','10x'); _n(0x0f,'return','11x')
_n(0x10,'return-wide','11x'); _n(0x11,'return-object','11x')
_n(0x12,'const/4','11n'); _n(0x13,'const/16','21s'); _n(0x14,'const','31i')
_n(0x15,'const/high16','21h'); _n(0x16,'const-wide/16','21s'); _n(0x17,'const-wide/32','31i')
_n(0x18,'const-wide','51l'); _n(0x19,'const-wide/high16','21h')
_n(0x1a,'const-string','21c'); _n(0x1b,'const-string/jumbo','31c'); _n(0x1c,'const-class','21c')
_n(0x1d,'monitor-enter','11x'); _n(0x1e,'monitor-exit','11x'); _n(0x1f,'check-cast','21c')
_n(0x20,'instance-of','22c'); _n(0x21,'array-length','12x'); _n(0x22,'new-instance','21c')
_n(0x23,'new-array','22c'); _n(0x24,'filled-new-array','35c'); _n(0x25,'filled-new-array/range','3rc')
_n(0x26,'fill-array-data','31t'); _n(0x27,'throw','11x')
_n(0x28,'goto','10t'); _n(0x29,'goto/16','20t'); _n(0x2a,'goto/32','30t')
_n(0x2b,'packed-switch','31t'); _n(0x2c,'sparse-switch','31t')
for i, nm in enumerate(['cmpl-float','cmpg-float','cmpl-double','cmpg-double','cmp-long']):
    _n(0x2d+i, nm, '23x')
for i, nm in enumerate(['if-eq','if-ne','if-lt','if-ge','if-gt','if-le']): _n(0x32+i, nm, '22t')
for i, nm in enumerate(['if-eqz','if-nez','if-ltz','if-gez','if-gtz','if-lez']): _n(0x38+i, nm, '21t')
for i, nm in enumerate(['aget','aget-wide','aget-object','aget-boolean','aget-byte','aget-char',
                        'aget-short','aput','aput-wide','aput-object','aput-boolean','aput-byte',
                        'aput-char','aput-short']): _n(0x44+i, nm, '23x')
for i, nm in enumerate(['iget','iget-wide','iget-object','iget-boolean','iget-byte','iget-char',
                        'iget-short','iput','iput-wide','iput-object','iput-boolean','iput-byte',
                        'iput-char','iput-short']): _n(0x52+i, nm, '22c')
for i, nm in enumerate(['sget','sget-wide','sget-object','sget-boolean','sget-byte','sget-char',
                        'sget-short','sput','sput-wide','sput-object','sput-boolean','sput-byte',
                        'sput-char','sput-short']): _n(0x60+i, nm, '21c')
for i, nm in enumerate(['invoke-virtual','invoke-super','invoke-direct','invoke-static',
                        'invoke-interface']): _n(0x6e+i, nm, '35c')
for i, nm in enumerate(['invoke-virtual/range','invoke-super/range','invoke-direct/range',
                        'invoke-static/range','invoke-interface/range']): _n(0x74+i, nm, '3rc')
for o in range(0x7b, 0x90): _n(o, f'unop{o:02x}', '12x')
for o in range(0x90, 0xb0): _n(o, f'binop{o:02x}', '23x')
for o in range(0xb0, 0xd0): _n(o, f'binop2addr{o:02x}', '12x')
for o in range(0xd0, 0xd8): _n(o, f'lit16_{o:02x}', '22s')
for o in range(0xd8, 0xe3): _n(o, f'lit8_{o:02x}', '22b')

SZ = {'10x':1,'12x':1,'11n':1,'11x':1,'10t':1,'20t':2,'22x':2,'21t':2,'21s':2,'21h':2,'21c':2,
      '23x':2,'22b':2,'22t':2,'22s':2,'22c':2,'30t':3,'31i':3,'31t':3,'31c':3,'32x':3,
      '35c':3,'3rc':3,'51l':5}

FIELD_OPS = set(range(0x52, 0x60)) | set(range(0x60, 0x6e))
METH_OPS = set(range(0x6e, 0x73)) | set(range(0x74, 0x79))
TYPE_OPS = {0x1c, 0x1f, 0x22, 0x23, 0x20, 0x24, 0x25}
STR_OPS = {0x1a, 0x1b}


def disasm(d, c):
    b = d.b; base = c['insns_off']; end = base + 2*c['insns_size']; p = base
    out = []
    while p < end:
        u0 = struct.unpack_from('<H', b, p)[0]; op = u0 & 0xff; pc = (p-base)//2
        if op == 0x00 and u0 >> 8:
            ident = u0 >> 8
            if ident == 1: n = struct.unpack_from('<H', b, p+2)[0]*2 + 4
            elif ident == 2: n = struct.unpack_from('<H', b, p+2)[0]*4 + 2
            else:
                w = struct.unpack_from('<H', b, p+2)[0]; s = struct.unpack_from('<I', b, p+4)[0]
                n = (s*w + 1)//2 + 4
            out.append((pc, 'payload', f'{n} units')); p += 2*n; continue
        name, fmt = N.get(op, (f'op{op:02x}', '10x'))
        n = SZ[fmt]
        u1 = struct.unpack_from('<H', b, p+2)[0] if n > 1 else 0
        u2 = struct.unpack_from('<H', b, p+4)[0] if n > 2 else 0
        A8 = (u0 >> 8) & 0xff; A4 = (u0 >> 8) & 0xf; B4 = (u0 >> 12) & 0xf
        a = ''
        if fmt == '11x': a = f'v{A8}'
        elif fmt == '12x': a = f'v{A4}, v{B4}'
        elif fmt == '11n': a = f'v{A4}, #{B4 - 16 if B4 > 7 else B4}'
        elif fmt == '22x': a = f'v{A8}, v{u1}'
        elif fmt == '32x': a = f'v{u1}, v{u2}'
        elif fmt == '21s': a = f'v{A8}, #{struct.unpack_from("<h", b, p+2)[0]}'
        elif fmt == '21h': a = f'v{A8}, #0x{u1 << 16:x}'
        elif fmt == '31i': a = f'v{A8}, #0x{struct.unpack_from("<I", b, p+2)[0]:x}'
        elif fmt == '51l': a = f'v{A8}, #wide'
        elif fmt == '10t': a = f'-> {pc + struct.unpack_from("<b", b, p+1)[0]}'
        elif fmt == '20t': a = f'-> {pc + struct.unpack_from("<h", b, p+2)[0]}'
        elif fmt == '30t': a = f'-> {pc + struct.unpack_from("<i", b, p+2)[0]}'
        elif fmt == '21t': a = f'v{A8}, -> {pc + struct.unpack_from("<h", b, p+2)[0]}'
        elif fmt == '22t': a = f'v{A4}, v{B4}, -> {pc + struct.unpack_from("<h", b, p+2)[0]}'
        elif fmt == '31t': a = f'v{A8}, -> {pc + struct.unpack_from("<i", b, p+2)[0]}'
        elif fmt == '23x': a = f'v{A8}, v{u1 & 0xff}, v{u1 >> 8}'
        elif fmt == '22b': a = f'v{A8}, v{u1 & 0xff}, #{struct.unpack_from("<b", b, p+3)[0]}'
        elif fmt == '22s': a = f'v{A4}, v{B4}, #{struct.unpack_from("<h", b, p+2)[0]}'
        elif fmt == '22c': a = f'v{A4}, v{B4}'
        elif fmt == '21c': a = f'v{A8}'
        elif fmt == '35c':
            cnt = B4; regs = [(u2 >> (4*i)) & 0xf for i in range(4)] + [A4]
            a = '{' + ', '.join(f'v{r}' for r in regs[:cnt]) + '}'
        elif fmt == '3rc':
            a = '{' + f'v{u2} .. v{u2 + A8 - 1}' + '}' if A8 else '{}'
        ref = ''
        if op in METH_OPS: ref = d.method(u1)
        elif op in FIELD_OPS: ref = d.field(u1)
        elif op in STR_OPS: ref = repr(d.string(u1 if op == 0x1a
                                                else struct.unpack_from('<I', b, p+2)[0]))
        elif op in TYPE_OPS: ref = d.type(u1)
        out.append((pc, name, (a + (', ' + ref if ref else '')).strip()))
        p += 2*n
    return out


def find(descriptor, dexes=None):
    for d in (dexes or dexlib.load()):
        for cname, af, cd in d.classes():
            if not descriptor.startswith(cname + '->'): continue
            for m, maf, co in d.class_methods(cd):
                if m == descriptor:
                    return d, d.code(co), maf
    return None, None, None


def show(descriptor, dexes=None):
    d, c, maf = find(descriptor, dexes)
    if not c:
        print(f"!! not found or abstract: {descriptor}"); return
    print(f"=== {descriptor}  regs={c['registers']} ins={c['ins']} outs={c['outs']} "
          f"static={bool(maf & 8)} ===")
    for pc, nm, a in disasm(d, c):
        print(f"  {pc:>4}: {nm:<22} {a}")
