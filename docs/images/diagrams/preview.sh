#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
# Renders diagram SVGs to PNG with headless Chromium (what readers see; Inkscape drops the shadows) so you
# can look at them: ./preview.sh <outdir> <name>...    e.g. ./preview.sh /tmp/p path-learn concept-supervisors
# Writes <outdir>/<name>.light.png and .dark.png (still frame, as under prefers-reduced-motion).
set -euo pipefail
out=$1; shift
here=$(cd "$(dirname "$0")" && pwd)
chrome=${CHROME:-$(ls -d ~/.cache/ms-playwright/chromium-*/chrome-linux*/chrome 2>/dev/null | tail -1)}
[ -x "$chrome" ] || { echo "no Chromium found: set CHROME=/path/to/chrome" >&2; exit 1; }
mkdir -p "$out"
for n in "$@"; do for th in light dark; do
  f="$here/$n.$th.svg"; [ -f "$f" ] || { echo "missing $f" >&2; continue; }
  read -r w h < <(sed -n 's/.*viewBox="0 0 \([0-9.]*\) \([0-9.]*\)".*/\1 \2/p' "$f" | head -1)
  pw=1100; ph=$(python3 -c "print(round($pw*$h/$w))")
  bg=$([ "$th" = dark ] && echo '#0A0D14' || echo '#FBFCFE')
  echo "<html><body style='margin:0;background:$bg'><img src='file://$f' style='width:${pw}px;display:block'></body></html>" > "$out/$n.$th.html"
  "$chrome" --headless --no-sandbox --disable-gpu --force-prefers-reduced-motion --window-size=$pw,$ph \
    --screenshot="$out/$n.$th.png" "file://$out/$n.$th.html" >/dev/null 2>&1
done; done
