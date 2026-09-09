"""Import paths and instruction-stream builders shared by the tests.

The suite runs on stdlib `unittest` and nothing else. There is no pytest here and no virtualenv;
CI has a bare `python3`, and a test suite that needs installing is a test suite that stops being
run. `python3 -m unittest discover -s tools/tests -t .` is the whole invocation.

**Nothing here reads the APK.** Every test builds its own instruction stream, which is what makes
them able to describe a shape Gboard 18.0.3 does not happen to contain -- a switch, a wide pair
straddling a scratch register, a `/range` invoke over the exact registers an emitter wants. The
gate already checks the real APK; these check the code that does the checking.
"""

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]

for _path in (ROOT / "tools" / "apk", ROOT / ".github" / "scripts"):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))


def stream(*rows):
    """An instruction stream in the shape `dis.disasm` returns: (pc, mnemonic, operand text).

    Program counters are assigned one per instruction. Real dex pcs advance by instruction width,
    so a stream built here is not byte-accurate -- but every consumer under test addresses
    instructions by pc through a lookup table, never by arithmetic on it, and the tests that care
    about branch targets spell the target pc explicitly.
    """
    return [(i, mnemonic, args) for i, (mnemonic, args) in enumerate(rows)]


def goto(target):
    return ("goto", f"-> {target}")


def if_eqz(register, target):
    return ("if-eqz", f"v{register}, -> {target}")
