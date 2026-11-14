"""Import unmodified Lucide geometry as Android VectorDrawable resources."""
from pathlib import Path
import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)


def attr(key):
    return "{" + ANDROID + "}" + key


def geometry(node):
    kind = node.tag.rsplit("}", 1)[-1]
    a = node.attrib
    if kind == "path":
        return a["d"]
    if kind in ("polyline", "polygon"):
        return "M" + a["points"] + ("Z" if kind == "polygon" else "")
    if kind == "line":
        return f'M{a["x1"]},{a["y1"]} L{a["x2"]},{a["y2"]}'
    if kind == "circle":
        x, y, r = (float(a[k]) for k in ("cx", "cy", "r"))
        return f"M{x-r},{y} a{r},{r} 0 1,0 {2*r},0 a{r},{r} 0 1,0 {-2*r},0"
    if kind == "rect":
        x, y, w, h = (float(a.get(k, 0)) for k in ("x", "y", "width", "height"))
        r = float(a.get("rx", 0))
        return f"M{x+r},{y} H{x+w-r} Q{x+w},{y} {x+w},{y+r} V{y+h-r} Q{x+w},{y+h} {x+w-r},{y+h} H{x+r} Q{x},{y+h} {x},{y+h-r} V{y+r} Q{x},{y} {x+r},{y} Z"
    raise ValueError("Unsupported SVG element: " + kind)


icons = ["arrow-left", "arrow-right", "camera", "image-plus", "plus", "check", "rotate-cw", "list", "pencil", "trash-2", "book-open", "house", "circle-check", "settings-2", "calendar-days", "user-round", "volume-2", "chevron-right", "chevron-left", "chevron-down", "chevron-up", "x", "ellipsis", "bell", "sparkles", "flame", "clock", "repeat", "spell-check", "shield-check", "book-x", "pen-line", "layers", "file-text", "images", "undo-2", "circle-alert", "list-checks", "target"]
vendor = ROOT / "vendor/lucide"
vendor.mkdir(parents=True, exist_ok=True)
source = Path(os.environ.get("LUCIDE_HOME", "/home/cudebug/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/lucide"))
cache = vendor / "icons.json"
if cache.exists():
    nodes = json.loads(cache.read_text())
else:
    names = ["".join(p.title() for p in name.split("-")) for name in icons]
    nodes = json.loads(subprocess.check_output(["node", "-e", "const lib=require(process.argv[1]);console.log(JSON.stringify(JSON.parse(process.argv[2]).map(n=>lib.icons[n])));", str(source), json.dumps(names)]))
    cache.write_text(json.dumps(nodes, indent=2) + "\n")
    shutil.copyfile(source / "LICENSE", vendor / "LICENSE")
for name in icons:
    shapes = nodes[icons.index(name)]
    vector = ET.Element("vector", {attr("width"): "24dp", attr("height"): "24dp", attr("viewportWidth"): "24", attr("viewportHeight"): "24"})
    for kind, attributes in shapes:
        node = ET.Element(kind, attributes)
        ET.SubElement(vector, "path", {attr("pathData"): geometry(node), attr("fillColor"): "@android:color/transparent", attr("strokeColor"): "#184E45", attr("strokeWidth"): "1.8", attr("strokeLineCap"): "round", attr("strokeLineJoin"): "round"})
    ET.indent(vector)
    ET.ElementTree(vector).write(ROOT / "app/src/main/res/drawable" / ("ic_" + name.replace("-", "_") + ".xml"), encoding="utf-8", xml_declaration=True)
print("Imported", len(icons), "Lucide icons")
