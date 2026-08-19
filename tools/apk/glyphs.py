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

import axml
import arsc

# Path commands, and how many numbers each takes per repetition.
ARITY = {'M': 2, 'L': 2, 'T': 2, 'H': 1, 'V': 1, 'C': 6, 'S': 4, 'Q': 4, 'A': 7, 'Z': 0}

NUMBER = re.compile(r'[-+]?(?:\d*\.\d+(?:[eE][-+]?\d+)?|\d+\.?(?:[eE][-+]?\d+)?)')
COMMAND = re.compile(r'[MmLlHhVvCcSsQqTtAaZz]')


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
    while cursor < len(d):
        match = COMMAND.search(d, cursor)
        if not match:
            break
        letter = match.group()
        cursor = match.end()
        nxt = COMMAND.search(d, cursor)
        chunk = d[cursor:nxt.start() if nxt else len(d)]
        numbers = [float(n) for n in NUMBER.findall(chunk)]
        cursor = nxt.start() if nxt else len(d)

        upper = letter.upper()
        relative = letter.islower()
        if upper == 'Z':
            x, y = start_x, start_y
            previous = upper
            continue

        step = ARITY[upper]
        if step == 0 or not numbers:
            continue
        for i in range(0, len(numbers) - step + 1, step):
            args = numbers[i:i + step]
            if upper == 'H':
                x = x + args[0] if relative else args[0]
            elif upper == 'V':
                y = y + args[0] if relative else args[0]
            elif upper == 'A':
                # Only the endpoint is comparable; the radii and flags describe the same arc in
                # both encodings when the endpoints agree.
                x = x + args[5] if relative else args[5]
                y = y + args[6] if relative else args[6]
            else:
                base_x, base_y = (x, y) if relative else (0.0, 0.0)
                for j in range(0, step, 2):
                    px, py = base_x + args[j], base_y + args[j + 1]
                    if j + 2 < step:
                        out.append((round(px, 2), round(py, 2)))
                x, y = base_x + args[step - 2], base_y + args[step - 1]
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
    for attrs, data in re.findall(r'<path\b([^>]*?)\bd="([^"]+)"', text):
        # `attrs` captures everything between `<path ` and `d=`. A `fill="none"` after `d=` on
        # the same element is caught by looking up to the next `>`, not past it — the tail check
        # used to read into the NEXT path and wrongly skipped a filled path followed by the
        # transparent backing rect.
        rest = text.split(data, 1)[1] if data in text else ''
        own = rest[:rest.find('>') + 1] if '>' in rest else rest[:40]
        if 'fill="none"' in attrs or 'fill="none"' in own:
            continue
        out += points(data)
    return out


def apk_glyphs(apk):
    """id -> points, for every vector drawable in the APK."""
    zf = zipfile.ZipFile(apk)
    table = arsc.load(zf.read('resources.arsc'))
    found = {}
    for rid, name in table.names.items():
        if not name.startswith('drawable/'):
            continue
        source = str(table.value(rid) or '')
        match = re.search(r'res/[^\'"]+\.xml', source)
        if not match:
            continue
        try:
            elements = list(axml.parse(zf.read(match.group(0))))
        except Exception:
            continue
        collected = []
        for _depth, tag, attrs in elements:
            if tag == 'path' and attrs.get('pathData'):
                collected += points(str(attrs['pathData']))
        if collected:
            found[rid] = (match.group(0), collected)
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
        with open(path) as handle:
            want = svg_points(handle.read())
        matches = [(rid, src) for rid, (src, got) in bundled.items() if got == want]
        name = path.split('/')[-1].replace('.svg', '')
        if matches:
            hits += 1
            ids = ', '.join(f'{rid:#010x} ({src})' for rid, src in matches[:3])
            print(f'  BUNDLED  {name:<16} {ids}')
        else:
            print(f'  absent   {name:<16} {len(want)} points, no drawable matches')
    print(f'\n{hits}/{len(references)} reference icons are bundled')
    return 0


if __name__ == '__main__':
    sys.exit(main())
