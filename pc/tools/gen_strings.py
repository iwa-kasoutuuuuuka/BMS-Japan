"""Generate src/bms_japan/strings_ja.py from the Android app's strings.xml.

Usage (from the repo root):
    python pc/tools/gen_strings.py app/src/main/res/values/strings.xml pc/src/bms_japan/strings_ja.py
"""

import re
import sys
import xml.etree.ElementTree as ET

PREFIXES = ("alarm_", "ev_", "set_", "group_", "battery_", "label_", "mode_", "state_", "confirm_", "section_")


def unescape(text: str) -> str:
    # Android string resource escapes: \' \" \n
    return text.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n")


def main() -> None:
    src, dst = sys.argv[1], sys.argv[2]
    root = ET.parse(src).getroot()
    out: dict[str, str] = {}
    for s in root.findall("string"):
        name = s.get("name") or ""
        if name.startswith(PREFIXES):
            t = unescape("".join(s.itertext()))
            t = t.replace("%%", "%")
            t = re.sub(r"%(\d)\$[sd]", lambda m: "{%d}" % (int(m.group(1)) - 1), t)
            out[name] = t
    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        f.write('"""Japanese labels generated from app/src/main/res/values/strings.xml (Android app).\n\n')
        f.write('Do not edit by hand; regenerate with pc/tools/gen_strings.py.\n"""\n\n')
        f.write("STRINGS: dict[str, str] = {\n")
        for k, v in out.items():
            f.write(f"    {k!r}: {v!r},\n")
        f.write("}\n")
    print(f"{len(out)} strings written to {dst}")


if __name__ == "__main__":
    main()
