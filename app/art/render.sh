#!/bin/bash
# Renders art/*.svg into app/res (launcher icon mipmaps + in-app drawables). Needs cairosvg:
#   python3 -m venv v && v/bin/pip install cairosvg ; PY=v/bin/python art/render.sh
set -euo pipefail
cd "$(dirname "$0")"
PY=${PY:-python3}
export DYLD_FALLBACK_LIBRARY_PATH=${DYLD_FALLBACK_LIBRARY_PATH:-/opt/homebrew/lib}
"$PY" - <<'P'
import cairosvg, os
sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for d, s in sizes.items():
    os.makedirs(f"../res/mipmap-{d}", exist_ok=True)
    cairosvg.svg2png(url="icon.svg", write_to=f"../res/mipmap-{d}/ic_launcher.png", output_width=s, output_height=s)
os.makedirs("../res/drawable-nodpi", exist_ok=True)
cairosvg.svg2png(url="icon.svg", write_to="../res/drawable-nodpi/hero.png", output_width=320, output_height=320)
cairosvg.svg2png(url="quick.svg", write_to="../res/drawable-nodpi/quick.png", output_width=176, output_height=176)
cairosvg.svg2png(url="icon.svg", write_to="icon-preview-512.png", output_width=512, output_height=512)
print("rendered")
P
