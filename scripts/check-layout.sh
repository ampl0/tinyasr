#!/usr/bin/env bash
# Fails the build when a layout view relies on a <style> for its size.
#
# A Value in a <style> is not a reliable source of layout_width / layout_height:
# LayoutParams are generated from the view's own attributes, and a missing width
# aborts inflation with "You must supply a layout_width attribute" — a crash on
# the very first screen. Checking it here costs nothing and keeps it from ever
# reaching a device.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
fail=0

while IFS= read -r file; do
    python3 - "$file" <<'PY' || fail=1
import sys
import xml.etree.ElementTree as ET

ns = "{http://schemas.android.com/apk/res/android}"
views = {"View", "TextView", "Button", "ImageButton", "ImageView", "Spinner",
         "ProgressBar", "LinearLayout", "FrameLayout", "RelativeLayout",
         "ScrollView", "HorizontalScrollView", "GridLayout", "TableLayout",
         "TableRow", "Space", "CheckBox", "Switch", "EditText", "RadioButton"}

path = sys.argv[1]
tree = ET.parse(path)
bad = []


def walk(el):
    for child in el:
        if child.tag in views:
            if child.tag == "View" and ns + "layout_width" not in child.attrib:
                # <View> also needs a size, but never an id; report anyway.
                bad.append(child.attrib.get(ns + "id", child.tag))
            elif ns + "layout_width" not in child.attrib or ns + "layout_height" not in child.attrib:
                bad.append(child.attrib.get(ns + "id", child.tag))
        walk(child)


walk(tree.getroot())
if bad:
    print(f"{path}: views without their own layout_width/layout_height: {', '.join(bad)}")
    sys.exit(1)
PY
done < <(find "$root/app/src" -path "*/res/layout/*.xml")

if [ "$fail" -ne 0 ]; then
    echo "check-layout: refusing to continue" >&2
    exit 1
fi
echo "check-layout: every view declares its own size"
