"""Mask Kotlin/Java comments without touching strings or moving source offsets.

Regexes that remove `//` from raw source can truncate a URL in a Kotlin or Java literal and make
a checker see a different descriptor from the compiler. Spaces preserve line numbers and offsets.
Kotlin's nested `/* ... */` comments are supported; Java is a subset of the same scan.
"""


def without_comments(text):
    out, i, n = [], 0, len(text)
    while i < n:
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            if end < 0:
                raise ValueError('unterminated raw string')
            out.append(text[i:end + 3])
            i = end + 3
        elif text[i] in ('"', "'"):
            delimiter, start = text[i], i
            i += 1
            while i < n:
                if text[i] == '\\':
                    i += 2
                elif text[i] == delimiter:
                    i += 1
                    break
                else:
                    i += 1
            out.append(text[start:i])
        elif text.startswith('//', i):
            end = text.find('\n', i)
            end = n if end < 0 else end
            out.append(' ' * (end - i))
            i = end
        elif text.startswith('/*', i):
            depth = 1
            out.append('  ')
            i += 2
            while i < n and depth:
                if text.startswith('/*', i):
                    depth += 1
                    out.append('  ')
                    i += 2
                elif text.startswith('*/', i):
                    depth -= 1
                    out.append('  ')
                    i += 2
                else:
                    out.append('\n' if text[i] == '\n' else ' ')
                    i += 1
            if depth:
                raise ValueError('unterminated block comment')
        else:
            out.append(text[i])
            i += 1
    masked = ''.join(out)
    assert len(masked) == len(text)
    return masked
