#!/usr/bin/env node

// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Regenerates src/assets/material-symbols-subset.woff2: Material Symbols Outlined reduced to the
// icons the UI uses, at the single axis setting index.css applies (FILL 0, wght 300, opsz 20).
// Run `pnpm run icons` after adding an icon; it needs network access to Google Fonts.
//
// An icon is "used" when any identifier-like token in src (tests excluded) is a Material Symbols
// name, so a few look-alike words get included too. Plugin-provided node HTML also references
// icons by name (plugins/**/UiDesign); list those in PLUGIN_ICONS.
import fs from 'fs';
import path from 'path';
import { glob } from 'glob';

// expand_more/expand_less are valid icons that material-symbols' name list omits, so list them here.
const PLUGIN_ICONS = ['api', 'terminal', 'touch_app', 'expand_more', 'expand_less'];

const root = path.resolve(import.meta.dirname, '..');
const dts = fs.readFileSync(path.join(root, 'node_modules/material-symbols/index.d.ts'), 'utf8');
const known = new Set([...dts.matchAll(/"([a-z0-9_]+)"/g)].map((m) => m[1]));

const files = await glob('src/**/*.{ts,tsx}', {
  cwd: root,
  ignore: ['**/__tests__/**', '**/test/**', '**/*.test.*'],
});
const used = new Set(PLUGIN_ICONS);
for (const file of files) {
  for (const [token] of fs.readFileSync(path.join(root, file), 'utf8').matchAll(/[a-z][a-z0-9_]*/g)) {
    if (known.has(token)) used.add(token);
  }
}

// Google Fonts requires icon_names in alphabetical order.
const names = [...used].sort().join(',');
const cssUrl =
  'https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:opsz,wght,FILL,GRAD@20,300,0,0' +
  `&icon_names=${names}&display=block`;
// A modern user agent makes Google Fonts answer with woff2.
const headers = { 'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36' };
const css = await (await fetch(cssUrl, { headers })).text();
const fontUrl = css.match(/url\((https:[^)]+)\)/)?.[1];
if (!fontUrl) throw new Error(`No font URL in Google Fonts response:\n${css}`);

const font = Buffer.from(await (await fetch(fontUrl)).arrayBuffer());
fs.writeFileSync(path.join(root, 'src/assets/material-symbols-subset.woff2'), font);
console.log(`${used.size} icons, ${font.length} bytes`);
