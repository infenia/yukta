#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# Regenerates the favicon set in public/ from public/logo.svg (left untouched):
#   favicon.svg                         the drawing only (no C2PA metadata/title), tight viewBox
#   favicon.ico                         16, 32 and 48 px frames
#   favicon-96x96.png, apple-touch-icon.png, web-app-manifest-{192x192,512x512}.png
# Needs Inkscape and ImageMagick (`convert`). Run after changing logo.svg: `pnpm run favicons`.
set -euo pipefail
cd "$(dirname "$0")/../public"

# Drawing extent is about x 32..216, y 36..214 of the 250x250 canvas; crop with a little air.
python3 - <<'PY'
import re
s = open('logo.svg').read()
s = re.sub(r'<metadata>.*?</metadata>', '', s, flags=re.S)
s = re.sub(r'<title[^>]*>.*?</title>', '', s, flags=re.S)
s = re.sub(r'\s+xmlns:c2pa="[^"]*"', '', s)
s = re.sub(r'\s+(width|height)="250"', '', s)
s = s.replace('viewBox="0 0 250 250"', 'viewBox="26 27 196 196"')
s = s.replace(' role="img" aria-labelledby="logo-title"', '')
s = re.sub(r'\n\s*\n+', '\n', s).strip() + '\n'
open('favicon.svg', 'w').write(s)
PY

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
for size in 16 32 48 96; do
  inkscape favicon.svg -w "$size" -h "$size" -o "$tmp/$size.png" >/dev/null 2>&1
done
cp "$tmp/96.png" favicon-96x96.png
convert "$tmp/16.png" "$tmp/32.png" "$tmp/48.png" favicon.ico

# Opaque, padded icons (the mark fills about 68 %, inside the maskable safe zone)
padded() { # size out
  inkscape favicon.svg -w "$(($1 * 68 / 100))" -h "$(($1 * 68 / 100))" -o "$tmp/m.png" >/dev/null 2>&1
  convert "$tmp/m.png" -background white -gravity center -extent "${1}x${1}" "$2"
}
padded 180 apple-touch-icon.png
padded 192 web-app-manifest-192x192.png
padded 512 web-app-manifest-512x512.png
echo "favicons regenerated from logo.svg"
