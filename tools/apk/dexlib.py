"""Minimal read-only DEX reader: enough to disassemble call/field/const traffic."""
import struct, os, glob

# Instruction length in 16-bit code units, indexed by opcode. Defaults to 1; the ranges below
# override every opcode that is longer.
_L = [1] * 256


def _set(rng, n):
    for o in rng:
        _L[o] = n


_set([0x02,0x05,0x08], 2); _set([0x03,0x06,0x09], 3)
_set([0x13,0x15,0x16,0x19,0x1a,0x1c,0x1f,0x20,0x22,0x23], 2)
_set([0x14,0x17,0x1b,0x24,0x25,0x26], 3)
_set([0x18], 5)
_set([0x29], 2); _set([0x2a,0x2b,0x2c], 3)
_set(range(0x2d,0x32), 2); _set(range(0x32,0x3e), 2)
_set(range(0x44,0x52), 2); _set(range(0x52,0x60), 2); _set(range(0x60,0x6e), 2)
_set(range(0x6e,0x73), 3); _set(range(0x74,0x79), 3)
_set(range(0x90,0xb0), 2); _set(range(0xd0,0xe3), 2)
_set([0xfa,0xfb], 4); _set([0xfc,0xfd], 3); _set([0xfe,0xff], 2)

INVOKE = {0x6e:'invoke-virtual',0x6f:'invoke-super',0x70:'invoke-direct',
          0x71:'invoke-static',0x72:'invoke-interface',
          0x74:'invoke-virtual/range',0x75:'invoke-super/range',0x76:'invoke-direct/range',
          0x77:'invoke-static/range',0x78:'invoke-interface/range'}
IFIELD = {**{0x52+i:n for i,n in enumerate(
    ['iget','iget-wide','iget-object','iget-boolean','iget-byte','iget-char','iget-short',
     'iput','iput-wide','iput-object','iput-boolean','iput-byte','iput-char','iput-short'])}}
SFIELD = {**{0x60+i:n for i,n in enumerate(
    ['sget','sget-wide','sget-object','sget-boolean','sget-byte','sget-char','sget-short',
     'sput','sput-wide','sput-object','sput-boolean','sput-byte','sput-char','sput-short'])}}
RET = {0x0e:'return-void',0x0f:'return',0x10:'return-wide',0x11:'return-object'}


def uleb(b, o):
    r = 0; s = 0
    while True:
        x = b[o]; o += 1; r |= (x & 0x7f) << s; s += 7
        if not x & 0x80: return r, o


class Dex:
    def __init__(self, path):
        self.path = path
        self.b = open(path, 'rb').read()
        b = self.b
        (self.str_n, self.str_o, self.typ_n, self.typ_o, self.pro_n, self.pro_o,
         self.fld_n, self.fld_o, self.mth_n, self.mth_o, self.cls_n,
         self.cls_o) = struct.unpack_from('<12I', b, 56)

    def string(self, i):
        off = struct.unpack_from('<I', self.b, self.str_o + 4*i)[0]
        n, off = uleb(self.b, off)
        end = off
        cnt = 0
        while cnt < n:
            c = self.b[end]
            if c < 0x80: end += 1
            elif c & 0xe0 == 0xc0: end += 2
            else: end += 3
            cnt += 1
        return self.b[off:end].decode('utf-8', 'replace')

    def type(self, i):
        return self.string(struct.unpack_from('<I', self.b, self.typ_o + 4*i)[0])

    def proto(self, i):
        sh, rt, po = struct.unpack_from('<3I', self.b, self.pro_o + 12*i)
        ps = []
        if po:
            n = struct.unpack_from('<I', self.b, po)[0]
            ps = [self.type(struct.unpack_from('<H', self.b, po+4+2*k)[0]) for k in range(n)]
        return ps, self.type(rt)

    def method(self, i):
        c, p, n = struct.unpack_from('<HHI', self.b, self.mth_o + 8*i)
        ps, rt = self.proto(p)
        return f"{self.type(c)}->{self.string(n)}({''.join(ps)}){rt}"

    def field(self, i):
        c, t, n = struct.unpack_from('<HHI', self.b, self.fld_o + 8*i)
        return f"{self.type(c)}->{self.string(n)}:{self.type(t)}"

    def classes(self):
        for i in range(self.cls_n):
            ci, af, su, io, sf, ao, cd, sv = struct.unpack_from('<8I', self.b, self.cls_o + 32*i)
            yield self.type(ci), af, cd

    def class_methods(self, cd):
        """Yield (descriptor, access_flags, code_off) for every method of a class_data_item."""
        if not cd: return
        b = self.b
        sf, o = uleb(b, cd); inf, o = uleb(b, o); dm, o = uleb(b, o); vm, o = uleb(b, o)
        for _ in range(sf + inf):
            _, o = uleb(b, o); _, o = uleb(b, o)
        for count in (dm, vm):
            idx = 0
            for _ in range(count):
                d, o = uleb(b, o); af, o = uleb(b, o); co, o = uleb(b, o)
                idx += d
                yield self.method(idx), af, co

    def code(self, off):
        if not off: return None
        rs, ins, outs, tries, dbg, isz = struct.unpack_from('<HHHHII', self.b, off)
        return dict(registers=rs, ins=ins, outs=outs, insns_off=off+16, insns_size=isz)

    def walk(self, c):
        """Yield (pc, opcode, mnemonic, operand_text) over one code_item."""
        b = self.b; base = c['insns_off']; end = base + 2*c['insns_size']; p = base
        while p < end:
            unit = struct.unpack_from('<H', b, p)[0]
            op = unit & 0xff
            if op == 0x00 and unit >> 8:
                ident = unit >> 8
                if ident == 1:
                    sz = struct.unpack_from('<H', b, p+2)[0]; n = sz*2 + 4
                elif ident == 2:
                    sz = struct.unpack_from('<H', b, p+2)[0]; n = sz*4 + 2
                else:
                    w = struct.unpack_from('<H', b, p+2)[0]
                    sz = struct.unpack_from('<I', b, p+4)[0]
                    n = (sz*w + 1)//2 + 4
                p += 2*n; continue
            n = _L[op]
            txt = None; mn = None
            if op in INVOKE:
                mn = INVOKE[op]
                midx = struct.unpack_from('<H', b, p+2)[0]
                txt = self.method(midx)
            elif op in IFIELD or op in SFIELD:
                mn = IFIELD.get(op) or SFIELD[op]
                fidx = struct.unpack_from('<H', b, p+2)[0]
                txt = self.field(fidx)
            elif op == 0x14:
                mn = 'const'; txt = hex(struct.unpack_from('<I', b, p+2)[0])
            elif op == 0x13:
                mn = 'const/16'; txt = hex(struct.unpack_from('<h', b, p+2)[0])
            elif op == 0x15:
                mn = 'const/high16'; txt = hex(struct.unpack_from('<H', b, p+2)[0] << 16)
            elif op == 0x12:
                mn = 'const/4'; v = (unit >> 12) & 0xf
                txt = hex(v - 16 if v > 7 else v)
            elif op == 0x1a:
                mn = 'const-string'; txt = repr(self.string(struct.unpack_from('<H', b, p+2)[0]))
            elif op in RET:
                mn = RET[op]; txt = ''
            elif op in (0x0a, 0x0b, 0x0c):
                mn = ['move-result','move-result-wide','move-result-object'][op-0x0a]
                txt = f"v{(unit>>8)&0xff}"
            if mn:
                yield (p - base)//2, op, mn, txt
            p += 2*n


def load(dirpath='/tmp/gb'):
    return [Dex(p) for p in sorted(glob.glob(os.path.join(dirpath, '*.dex')))]
