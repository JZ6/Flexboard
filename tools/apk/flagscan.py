#!/usr/bin/env python3
"""Rank Gboard's boolean Phenotype flags by whether forcing one on could do anything.

Seven flags were picked by hand for Hidden Features. Two worked, four did nothing, and one
stopped Gboard starting. The selection rule at the time -- "Google ships this feature publicly,
so the code behind the flag is finished" -- was satisfied by all seven, which makes it not a
rule. This scans for the property that actually separated them.

Two signals, and they are not equal:

  unread   The flag's Lnxp; object is sput into a field that no instruction anywhere reads.
           Nothing can consume the value, so flipping the default cannot change behaviour.
           This is a proof, not a heuristic -- the only escape is reflection, which R8-shrunk
           Gboard does not use to read its own flag fields.

  gated    Companion parameters named like server-delivered configuration sit next to the flag:
           download groups, locale allowlists, version allowlists. Forcing the parent on does
           not supply that configuration, it skips it.

           This signal is BAD. Scored against the seven flags whose outcomes are known it gets
           three of seven: it catches two of the five duds, wrongly condemns enable_grammar_
           checker (which works), and clears enable_on_device_proofread (which stops Gboard
           starting). An earlier hand-run looked far better only because the search token for
           each flag was chosen after the answers were known -- `grammar` finds nothing,
           `grammar_checker` finds a manifest URI and condemns it. Do not gate on this, and do
           not read a clean result as anything at all. It is kept because the inventory and the
           unread proof are worth having, and deleting the weak signal would hide the fact that
           it was tried. See docs/phenotype-flags.md.

Deliberately not a signal: how many companions a flag has. enable_grammar_checker works and has
11; enable_custom_sticker_tab does nothing and has 12. The count does not separate them, and a
metric that looks quantitative while sorting nothing is worse than no metric.

Usage:
    python3 tools/apk/flagscan.py [apk-dir]        # default ../../gboard-apk
    python3 tools/apk/flagscan.py --all            # every flag, not just the shortlist
"""

import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import dexlib
import dis as ddis

BOOLEAN_FACTORY = "Lnxs;->a(Ljava/lang/String;Z)Lnxp;"
FACTORY_WINDOW = 6

# Names that mean "this is configured from a server, not compiled in".
GATING = re.compile(
    r"data_file_group|_mdd_|^mdd_|download|_locales?$|allowed_versions"
    r"|supported_languages|_packs?$|superpack|manifest|_allowlist$"
)

# The flags Flexboard already forces, so the report can say so rather than re-proposing them.
ALREADY_FORCED = {
    "enable_grammar_checker",
    "enable_close_proactive_suggestions_access_point",
    "enable_emoji_kitchen_browse",
    "enable_custom_sticker_tab",
    "offline_translate",
    "enable_settings_search",
}

# Confirmed on a device, so the scan can be scored against them rather than trusted blind.
KNOWN = {
    "enable_grammar_checker": "works",
    "enable_close_proactive_suggestions_access_point": "works",
    "enable_on_device_proofread": "FATAL",
    "enable_emoji_kitchen_browse": "inert",
    "enable_custom_sticker_tab": "inert",
    "offline_translate": "inert",
    "enable_settings_search": "inert",
}


def collect_flags(dexes):
    """Every boolean flag declared in a <clinit>, with the field it lands in."""
    flags = {}
    for d in dexes:
        for _t, _af, cd in d.classes():
            for desc, _a, co in d.class_methods(cd):
                if not desc.endswith("-><clinit>()V"):
                    continue
                code = d.code(co)
                if not code:
                    continue
                try:
                    ins = ddis.disasm(d, code)
                except Exception:
                    continue
                for i, (_pc, mn, arg) in enumerate(ins):
                    if not mn.startswith("const-string"):
                        continue
                    m = re.match(r"\s*v(\d+),\s*'(.*)'\s*$", arg or "")
                    if not m:
                        continue
                    name = m.group(2)
                    if not re.fullmatch(r"[a-z][a-z0-9_]{5,70}", name):
                        continue
                    call = next(
                        (j for j in range(i + 1, min(i + FACTORY_WINDOW, len(ins)))
                         if BOOLEAN_FACTORY in (ins[j][2] or "")),
                        None,
                    )
                    if call is None:
                        continue

                    reg = [int(x) for x in re.findall(r"v(\d+)", ins[call][2].split("},")[0])][1]
                    own = [j for j in range(i + 1, call)
                           if ins[j][1].startswith("const")
                           and re.match(rf"\s*v{reg},", ins[j][2] or "")]
                    if own:
                        lit = re.search(r"#(-?\w+)", ins[own[-1]][2] or "")
                        default = int(lit.group(1), 0) if lit else None
                        shape = "own"
                    else:
                        # Hoisted: the constant is written before this flag's own name, so it is
                        # shared. Read it anyway -- the value still says whether Gboard ships it on.
                        prior = [j for j in range(0, i)
                                 if ins[j][1].startswith("const")
                                 and re.match(rf"\s*v{reg},", ins[j][2] or "")]
                        lit = re.search(r"#(-?\w+)", ins[prior[-1]][2] or "") if prior else None
                        default = int(lit.group(1), 0) if lit else None
                        shape = "shared"

                    # Where the built flag object is stored, if it is stored at all.
                    field = None
                    for j in range(call + 1, min(call + 4, len(ins))):
                        if ins[j][1].startswith("sput-object"):
                            fm = re.search(r"(L[^;]+;->[^:\s]+)", ins[j][2] or "")
                            if fm:
                                # Owner->name only. The reader side is normalised the same way;
                                # leaving the ":Ltype;" on one side and not the other made every
                                # flag in the APK look unread, including ones known to work.
                                field = fm.group(1)
                            break
                    flags[name] = {"field": field, "shape": shape, "default": default,
                                   "owner": desc.split("->")[0]}
    return flags


def collect_field_reads(dexes):
    """Every field a static read touches, anywhere in the APK."""
    read = set()
    for d in dexes:
        for _t, _af, cd in d.classes():
            for _desc, _a, co in d.class_methods(cd):
                code = d.code(co)
                if not code:
                    continue
                for _pc, _op, mn, arg in d.walk(code):
                    if mn == "sget-object" and arg:
                        read.add(arg.split(":")[0])
    return read


def collect_identifiers(apk_dir):
    """Config-looking identifiers in the string pool, for the companion scan."""
    idents = set()
    for f in sorted(Path(apk_dir).glob("*.dex")):
        for m in re.finditer(rb"[ -~]{8,70}", f.read_bytes()):
            s = m.group().decode("ascii", "ignore").lstrip("!\"#$%&()*+,-./0123456789 ")
            if re.fullmatch(r"[a-z0-9_]{8,64}", s):
                idents.add(s)
    return idents


def token(flag):
    """The distinctive middle of a flag name, for finding its companions."""
    t = re.sub(r"^(enable|disable|allow|use|show|is)_", "", flag)
    t = re.sub(r"_(enabled|access_point|setting|settings)$", "", t)
    return t


def main():
    show_all = "--all" in sys.argv
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    apk_dir = args[0] if args else str(Path(__file__).resolve().parent.parent.parent / "gboard-apk")

    t0 = time.time()
    dexes = dexlib.load(apk_dir)
    flags = collect_flags(dexes)
    reads = collect_field_reads(dexes)
    idents = collect_identifiers(apk_dir)

    rows = []
    for name, info in flags.items():
        if info["default"]:
            continue                                    # already on, nothing to force
        unread = info["field"] is not None and info["field"] not in reads
        tok = token(name)
        companions = [s for s in idents if tok and tok in s and s != name]
        gated = sorted(s for s in companions if GATING.search(s))
        rows.append({
            "flag": name, "shape": info["shape"], "unread": unread,
            "gated": gated, "companions": len(companions),
            "known": KNOWN.get(name), "forced": name in ALREADY_FORCED,
        })

    total = len(flags)
    off = len(rows)
    inert = [r for r in rows if r["unread"]]
    gated = [r for r in rows if r["gated"] and not r["unread"]]
    clean = [r for r in rows if not r["unread"] and not r["gated"]]

    print(f"\n  {total} boolean flags, {off} shipping off")
    print(f"  {len(inert):>5}  provably inert   (flag object stored in a field nothing reads)")
    print(f"  {len(gated):>5}  probably gated   (server-delivered companions nearby)")
    print(f"  {len(clean):>5}  neither          (no static objection — which is not the same as safe)")

    print("\n  == scored against the seven already tested on a device ==")
    print(f"  {'flag':<50}{'outcome':<8}{'this scan says'}")
    for r in sorted(rows, key=lambda r: r["flag"]):
        if not r["known"]:
            continue
        says = "provably inert" if r["unread"] else ("gated: " + r["gated"][0]) if r["gated"] else "no objection"
        print(f"  {r['flag']:<50}{r['known']:<8}{says}")

    listing = rows if show_all else clean
    label = ("every flag" if show_all else
             f"no static objection — {len(clean)} of {off}, which is why this is not a shortlist")
    print(f"\n  == {label} ({len(listing)}) ==")
    for r in sorted(listing, key=lambda r: (r["shape"] != "own", r["flag"])):
        mark = "*" if r["forced"] else " "
        note = []
        if r["shape"] == "shared":
            note.append("needs isolating emission")
        if r["unread"]:
            note.append("PROVABLY INERT")
        if r["gated"]:
            note.append("gated: " + r["gated"][0])
        print(f"  {mark} {r['flag']:<58}{', '.join(note)}")
    if not show_all:
        print("\n  * = already forced by Flexboard")
    print(f"\n  {time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()
