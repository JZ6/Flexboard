"""Match Gboard's stripped vector drawables against the published Material Icons set.

Gboard is built with aapt2 `--collapse-resource-names`, so all 1,679 of its drawables are called
`0_resource_name_obfuscated` and there is nothing to search by. What survives is the geometry, and
the geometry is enough: an icon lifted from Material is byte-for-byte the same shape, even though
the two encodings of it look nothing alike as text.

    Gboard    M3,5h2L5,3c-1.1,0 -2,0.9 -2,2z
    Material  M3 5h2V3c-1.1 0-2 .9-2 2z

Same path. `V3` and `L5,3` land on the same point, `.9` and `0.9` are the same number, and the
separators differ throughout. So comparison happens after evaluating each path to the absolute
points it visits, which both encodings agree on exactly.

## Use

    python3 tools/apk/glyphs.py <apk> <reference-svg>...

Prints, for each reference icon, whether the APK bundles that exact glyph and at which id.
"""
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

import axml
import arsc

# Path commands, and how many numbers each takes per repetition.
ARITY = {'M': 2, 'L': 2, 'T': 2, 'H': 1, 'V': 1, 'C': 6, 'S': 4, 'Q': 4, 'A': 7, 'Z': 0}

NUMBER = re.compile(r'[-+]?(?:\d*\.\d+(?:[eE][-+]?\d+)?|\d+\.?(?:[eE][-+]?\d+)?)')
COMMAND = re.compile(r'[MmLlHhVvCcSsQqTtAaZz]')


def arc_args(chunk):
    """SVG arc flags are single digits, even in compact `00-1.41` syntax."""
    cursor, out = 0, []
    while cursor < len(chunk):
        while cursor < len(chunk) and chunk[cursor] in " \t\r\n,":
            cursor += 1
        if cursor == len(chunk):
            break
        args = []
        for part in range(7):
            while cursor < len(chunk) and chunk[cursor] in " \t\r\n,":
                cursor += 1
            if part in (3, 4):
                if cursor >= len(chunk) or chunk[cursor] not in "01":
                    raise ValueError(f"invalid SVG arc flag at {chunk[cursor:]!r}")
                args.append(float(chunk[cursor]))
                cursor += 1
            else:
                match = NUMBER.match(chunk, cursor)
                if match is None:
                    raise ValueError(f"incomplete SVG arc at {chunk[cursor:]!r}")
                args.append(float(match.group()))
                cursor = match.end()
        out.append(args)
    return out


def points(d):
    """Every absolute point a path visits, in order, rounded to hundredths.

    Control points are included as well as anchors: two different curves can share endpoints, and
    dropping the controls would call them equal. Rounding absorbs the last-digit differences between
    a hand-authored SVG and what aapt2 wrote out, without being loose enough to merge real icons --
    Material's grid is 24 units wide and its features are never a hundredth apart.
    """
    out = []
    x = y = 0.0
    start_x = start_y = 0.0
    cursor = 0
    previous = None
    last_cubic = last_quadratic = None
    while cursor < len(d):
        match = COMMAND.search(d, cursor)
        if not match:
            break
        letter = match.group()
        cursor = match.end()
        nxt = COMMAND.search(d, cursor)
        chunk = d[cursor:nxt.start() if nxt else len(d)]
        numbers = [float(n) for n in NUMBER.findall(chunk)] if letter.upper() != 'A' else []
        cursor = nxt.start() if nxt else len(d)

        upper = letter.upper()
        relative = letter.islower()
        if upper == 'Z':
            x, y = start_x, start_y
            previous = upper
            continue

        step = ARITY[upper]
        if step == 0 or not numbers and upper != 'A':
            continue
        if upper != 'A' and len(numbers) % step:
            raise ValueError(f'incomplete {upper} path command: {chunk!r}')
        groups = arc_args(chunk) if upper == 'A' else [numbers[i:i + step]
                                                         for i in range(0, len(numbers), step)]
        for i, args in enumerate(groups):
            if upper == 'H':
                x = x + args[0] if relative else args[0]
            elif upper == 'V':
                y = y + args[0] if relative else args[0]
            elif upper == 'A':
                out.append(tuple(round(v, 2) for v in args[:3]) + tuple(int(v) for v in args[3:5]))
                x = x + args[5] if relative else args[5]
                y = y + args[6] if relative else args[6]
            else:
                base_x, base_y = (x, y) if relative else (0.0, 0.0)
                if upper == 'S':
                    reflected = (2*x - last_cubic[0], 2*y - last_cubic[1]) if (
                        previous in ('C', 'S') and last_cubic is not None) else (x, y)
                    out.append(tuple(round(v, 2) for v in reflected))
                if upper == 'T':
                    reflected = (2*x - last_quadratic[0], 2*y - last_quadratic[1]) if (
                        previous in ('Q', 'T') and last_quadratic is not None) else (x, y)
                    out.append(tuple(round(v, 2) for v in reflected))
                for j in range(0, step, 2):
                    px, py = base_x + args[j], base_y + args[j + 1]
                    if j + 2 < step:
                        out.append((round(px, 2), round(py, 2)))
                x, y = base_x + args[step - 2], base_y + args[step - 1]
                if upper in ('C', 'S'):
                    last_cubic = (base_x + args[step - 4], base_y + args[step - 3])
                elif upper == 'Q':
                    last_quadratic = (base_x + args[0], base_y + args[1])
                elif upper == 'T':
                    last_quadratic = reflected
            out.append((round(x, 2), round(y, 2)))
            # A repeated M is an implicit L, and only the first pair opens a subpath.
            if upper == 'M' and i == 0:
                start_x, start_y = x, y
                upper = 'L'
            previous = upper
    return out


def svg_points(text):
    """Reference SVGs carry a transparent 24x24 backing rect; it is not part of the glyph."""
    out = []
    for element in ET.fromstring(text).iter():
        tag = element.tag.rsplit('}', 1)[-1]
        if tag == 'g' and element.get('transform'):
            raise ValueError('SVG group transforms are not supported by this geometry matcher')
        if tag == 'path':
            if element.get('fill') == 'none' or 'fill:none' in element.get('style', ''):
                continue
            data = element.get('d')
            if not data:
                raise ValueError('filled SVG path without geometry')
            out += points(data)
            continue
        if tag in ('rect', 'circle', 'ellipse', 'polygon', 'polyline', 'line') and (
                element.get('fill') != 'none' and 'fill:none' not in element.get('style', '')):
            raise ValueError(f'filled SVG {tag} shape is not supported by this path-only matcher')
    return out


def apk_glyphs(apk):
    """id -> points, for every vector drawable in the APK."""
    found = {}
    skipped = []
    with zipfile.ZipFile(apk) as zf:
        table = arsc.load(zf.read('resources.arsc'))
        for rid, name in table.names.items():
            if not name.startswith('drawable/'):
                continue
            source = str(table.value(rid) or '')
            match = re.search(r'res/[^\'"]+\.xml', source)
            if not match:
                continue
            try:
                elements = list(axml.parse(zf.read(match.group(0))))
                collected = []
                for _depth, tag, attrs in elements:
                    if tag == 'path' and attrs.get('pathData'):
                        collected += points(str(attrs['pathData']))
                if collected:
                    found[rid] = (match.group(0), collected)
            except Exception as exc:
                skipped.append((match.group(0), str(exc)))
    if skipped:
        print(f'{len(skipped)} vector candidates could not be parsed:', file=sys.stderr)
        for path, reason in skipped[:10]:
            print(f'  {path}: {reason}', file=sys.stderr)
    return found


def main():
    if len(sys.argv) < 3:
        print(__doc__.strip().split('## Use')[1].strip(), file=sys.stderr)
        return 2
    apk, references = sys.argv[1], sys.argv[2:]
    bundled = apk_glyphs(apk)
    print(f'{len(bundled)} vector drawables in the APK\n')
    hits = 0
    for path in references:
        try:
            with open(path) as handle:
                want = svg_points(handle.read())
        except (ValueError, ET.ParseError) as exc:
            print(f'  unsupported {path}: {exc}')
            continue
        matches = [(rid, src) for rid, (src, got) in bundled.items() if got == want]
        name = path.split('/')[-1].replace('.svg', '')
        if matches:
            hits += 1
            ids = ', '.join(f'{rid:#010x} ({src})' for rid, src in matches[:3])
            print(f'  BUNDLED  {name:<16} {ids}')
        else:
            print(f'  not matched {name:<16} {len(want)} points (not proof of absence)')
    print(f'\n{hits}/{len(references)} reference icons are bundled')
    return 0


if __name__ == '__main__':
    sys.exit(main())
