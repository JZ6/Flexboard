"""Minimal binary-XML (AXML) reader: enough to walk elements and attributes."""
import struct

TYPE_REFERENCE, TYPE_STRING, TYPE_INT_DEC, TYPE_INT_HEX, TYPE_INT_BOOLEAN = 0x01, 0x03, 0x10, 0x11, 0x12


def _pool(b, off):
    t, hs, sz = struct.unpack_from('<HHI', b, off)
    assert t == 0x0001, hex(t)
    cnt, styc, flags, strStart, styStart = struct.unpack_from('<IIIII', b, off + 8)
    utf8 = bool(flags & 0x100)
    offs = struct.unpack_from(f'<{cnt}I', b, off + 28)
    out, base = [], off + strStart
    for o in offs:
        p = base + o
        if utf8:
            n = b[p]; p += 2 if n & 0x80 else 1
            m = b[p]
            if m & 0x80:
                m = ((m & 0x7f) << 8) | b[p + 1]; p += 2
            else:
                p += 1
            out.append(b[p:p + m].decode('utf-8', 'replace'))
        else:
            n = struct.unpack_from('<H', b, p)[0]
            out.append(b[p + 2:p + 2 + 2 * n].decode('utf-16-le', 'replace'))
    return out, off + sz


def parse(b):
    """Yield (depth, tag, {attr: value}) for every element."""
    strs, p = _pool(b, 8)
    end = len(b)
    depth = 0
    while p < end - 8:
        t, hs, sz = struct.unpack_from('<HHI', b, p)
        if sz == 0:
            break
        if t == 0x0102:  # START_ELEMENT
            ns, name = struct.unpack_from('<II', b, p + 16)
            aStart, aSize, aCount = struct.unpack_from('<HHH', b, p + 24)
            attrs = {}
            ap = p + 16 + aStart
            for _ in range(aCount):
                ans, aname, araw = struct.unpack_from('<III', b, ap)
                dtype = b[ap + 15]
                data = struct.unpack_from('<I', b, ap + 16)[0]
                key = strs[aname] if aname < len(strs) else f'?{aname}'
                if araw != 0xffffffff and araw < len(strs):
                    val = strs[araw]
                elif dtype == TYPE_REFERENCE:
                    val = f'@0x{data:08x}'
                elif dtype == TYPE_INT_BOOLEAN:
                    val = 'true' if data else 'false'
                elif dtype in (TYPE_INT_DEC,):
                    val = str(data)
                elif dtype == TYPE_INT_HEX:
                    val = hex(data)
                else:
                    val = f'<t{dtype:#x}:{data}>'
                attrs[key] = val
                ap += aSize
            yield depth, strs[name], attrs
            depth += 1
        elif t == 0x0103:  # END_ELEMENT
            depth -= 1
        p += sz
