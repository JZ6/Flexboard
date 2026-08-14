"""ARSC (resources.arsc) reader.

Resolves resource ids to names and values, and values back to ids. Gboard ships its resource
*files* under obfuscated paths — the Latin keyboard layout is `res/aDh.xml` inside the APK — but
the resource table still carries the real entry names. The patcher decodes resources before a
resource patch sees them, so a patch addresses `res/xml/<name>.xml`, never `res/aDh.xml`. Getting
from one to the other is what this is for.

    import zipfile, arsc
    z = zipfile.ZipFile('gboard.apk')
    t = arsc.load(z.read('resources.arsc'))

    t.name(0x7f14097b)              # 'string/enable_gesture_input_key'
    t.value(0x7f14097b)             # 'enable_gesture_input'
    t.find_value('res/aDh.xml')     # [(0x7f170..., 'xml/...')]

Only what the findings in ../../docs need: entry names, simple values, and reverse lookup. No
style, no reference chasing, no configuration-aware "which value wins" logic — `values()` returns
every configuration variant and leaves the choice to the caller.
"""

from __future__ import annotations

import struct
from collections import defaultdict

RES_STRING_POOL = 0x0001
RES_TABLE = 0x0002
RES_TABLE_PACKAGE = 0x0200
RES_TABLE_TYPE = 0x0201
RES_TABLE_TYPE_SPEC = 0x0202

# ResTable_type flags
FLAG_SPARSE = 0x01
FLAG_OFFSET16 = 0x02

# Res_value dataTypes worth naming; anything else is reported as a raw int.
TYPE_NULL = 0x00
TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12

NO_ENTRY = 0xFFFFFFFF


class StringPool:
    """RES_STRING_POOL chunk. Strings are UTF-8 or UTF-16 depending on a header flag."""

    UTF8_FLAG = 1 << 8

    def __init__(self, data: bytes, offset: int):
        _type, header_size, size = struct.unpack_from('<HHI', data, offset)
        count, _style_count, flags, strings_start, _styles_start = struct.unpack_from(
            '<IIIII', data, offset + 8
        )
        self.utf8 = bool(flags & self.UTF8_FLAG)
        self.size = size
        offsets = struct.unpack_from(f'<{count}I', data, offset + header_size)
        base = offset + strings_start
        self._strings = [self._read(data, base + o) for o in offsets]

    def _read(self, data: bytes, at: int) -> str:
        if self.utf8:
            # Two length prefixes: character count then byte count. Either may be two bytes,
            # flagged by the high bit of the first.
            at = self._skip_len8(data, at)
            length, at = self._len8(data, at)
            return data[at:at + length].decode('utf-8', 'replace')
        length, at = self._len16(data, at)
        return data[at:at + length * 2].decode('utf-16-le', 'replace')

    @staticmethod
    def _len8(data: bytes, at: int) -> tuple[int, int]:
        value = data[at]
        if value & 0x80:
            return ((value & 0x7F) << 8) | data[at + 1], at + 2
        return value, at + 1

    @classmethod
    def _skip_len8(cls, data: bytes, at: int) -> int:
        return cls._len8(data, at)[1]

    @staticmethod
    def _len16(data: bytes, at: int) -> tuple[int, int]:
        value = struct.unpack_from('<H', data, at)[0]
        if value & 0x8000:
            high = (value & 0x7FFF) << 16
            return high | struct.unpack_from('<H', data, at + 2)[0], at + 4
        return value, at + 2

    def __getitem__(self, index: int) -> str:
        if index < 0 or index >= len(self._strings):
            return f'<string #{index}>'
        return self._strings[index]

    def __len__(self) -> int:
        return len(self._strings)

    def index_of(self, text: str) -> int | None:
        try:
            return self._strings.index(text)
        except ValueError:
            return None


class Value:
    """A single Res_value, already resolved against the global string pool where it is a string."""

    __slots__ = ('data_type', 'data', 'config', '_pool')

    def __init__(self, data_type: int, data: int, config: bytes, pool: StringPool):
        self.data_type = data_type
        self.data = data
        self.config = config
        self._pool = pool

    def resolve(self):
        if self.data_type == TYPE_STRING:
            return self._pool[self.data]
        if self.data_type == TYPE_REFERENCE:
            return f'@0x{self.data:08x}'
        if self.data_type == TYPE_INT_BOOLEAN:
            return self.data != 0
        if self.data_type == TYPE_NULL:
            return None
        return self.data

    def __repr__(self) -> str:
        return f'Value({self.resolve()!r})'


class Table:
    def __init__(self, data: bytes):
        chunk_type, header_size, _size = struct.unpack_from('<HHI', data, 0)
        if chunk_type != RES_TABLE:
            raise ValueError(f'not a resource table (chunk type 0x{chunk_type:04x})')

        self.strings = StringPool(data, header_size)

        # id -> 'type/name', and id -> [Value] across every configuration variant.
        self.names: dict[int, str] = {}
        self.entries: dict[int, list[Value]] = defaultdict(list)

        offset = header_size + self.strings.size
        while offset < len(data):
            chunk_type, chunk_header, chunk_size = struct.unpack_from('<HHI', data, offset)
            if chunk_size == 0:
                break
            if chunk_type == RES_TABLE_PACKAGE:
                self._package(data, offset, chunk_header, chunk_size)
            offset += chunk_size

    def _package(self, data: bytes, offset: int, header_size: int, size: int) -> None:
        package_id, = struct.unpack_from('<I', data, offset + 8)
        type_strings_off, _last_type, key_strings_off, _last_key = struct.unpack_from(
            '<IIII', data, offset + 8 + 4 + 256
        )
        type_names = StringPool(data, offset + type_strings_off)
        key_names = StringPool(data, offset + key_strings_off)

        cursor = offset + header_size
        end = offset + size
        while cursor < end:
            chunk_type, chunk_header, chunk_size = struct.unpack_from('<HHI', data, cursor)
            if chunk_size == 0:
                break
            if chunk_type == RES_TABLE_TYPE:
                self._type(data, cursor, chunk_header, package_id, type_names, key_names)
            cursor += chunk_size

    def _type(
        self,
        data: bytes,
        offset: int,
        header_size: int,
        package_id: int,
        type_names: StringPool,
        key_names: StringPool,
    ) -> None:
        type_id = data[offset + 8]
        flags = data[offset + 9]
        entry_count, entries_start = struct.unpack_from('<II', data, offset + 12)
        # ResTable_config follows; its own size leads it. Kept raw — only used to tell variants
        # of the same resource apart, never interpreted.
        config_size, = struct.unpack_from('<I', data, offset + 20)
        config = data[offset + 20:offset + 20 + config_size]

        type_name = type_names[type_id - 1]
        table_base = offset + entries_start
        index_base = offset + header_size

        if flags & FLAG_SPARSE:
            # Sparse: (entry index, offset/4) pairs instead of a dense offset array.
            pairs = struct.unpack_from(f'<{entry_count * 2}H', data, index_base)
            positions = [(pairs[i], pairs[i + 1] * 4) for i in range(0, len(pairs), 2)]
        elif flags & FLAG_OFFSET16:
            raw = struct.unpack_from(f'<{entry_count}H', data, index_base)
            positions = [(i, o * 4) for i, o in enumerate(raw) if o != 0xFFFF]
        else:
            raw = struct.unpack_from(f'<{entry_count}I', data, index_base)
            positions = [(i, o) for i, o in enumerate(raw) if o != NO_ENTRY]

        for entry_index, entry_offset in positions:
            at = table_base + entry_offset
            if at + 8 > len(data):
                continue
            entry_size, entry_flags, key_index = struct.unpack_from('<HHI', data, at)
            resource_id = (package_id << 24) | (type_id << 16) | entry_index
            self.names.setdefault(resource_id, f'{type_name}/{key_names[key_index]}')

            # Complex entries (bags: styles, arrays, attrs) carry a parent and a count instead of
            # a Res_value. Their contents are not needed here.
            if entry_flags & 0x0001:
                continue
            value_at = at + entry_size
            if value_at + 8 > len(data):
                continue
            _size, _res0, data_type, value = struct.unpack_from('<HBBI', data, value_at)
            self.entries[resource_id].append(Value(data_type, value, config, self.strings))

    # -- lookups ---------------------------------------------------------------------------

    def name(self, resource_id: int) -> str | None:
        """'xml/settings' for 0x7f1700xx, or None if the id is not in the table."""
        return self.names.get(resource_id)

    def values(self, resource_id: int) -> list:
        """Every configuration variant's resolved value, in table order."""
        return [v.resolve() for v in self.entries.get(resource_id, [])]

    def value(self, resource_id: int):
        """The first variant's value — right for ids with a single definition."""
        found = self.values(resource_id)
        return found[0] if found else None

    def find_value(self, needle) -> list[tuple[int, str]]:
        """Every (id, name) whose value equals `needle`. The reverse lookup, e.g. from a packed
        path like 'res/aDh.xml' back to the resource that owns it."""
        hits = []
        for resource_id, values in self.entries.items():
            if any(v.resolve() == needle for v in values):
                hits.append((resource_id, self.names.get(resource_id, '?')))
        return sorted(hits)

    def find_name(self, substring: str) -> list[tuple[int, str]]:
        """Every (id, name) whose 'type/name' contains `substring`."""
        return sorted(
            (rid, name) for rid, name in self.names.items() if substring in name
        )


def load(data: bytes) -> Table:
    """`load(zipfile.ZipFile(apk).read('resources.arsc'))`."""
    return Table(data)
