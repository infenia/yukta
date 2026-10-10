#!/usr/bin/env node

// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Fails the build when a JS chunk or the icon font in dist/assets exceeds its budget.
import fs from 'fs';
import path from 'path';

const dir = path.resolve(import.meta.dirname, '../dist/assets');
const budgets = [
  { pattern: /\.js$/, max: 300 * 1000, label: 'JS chunk' },
  { pattern: /^material-symbols.*\.woff2$/, max: 100 * 1000, label: 'icon font' },
];

const failures = fs.readdirSync(dir).flatMap((file) => {
  const size = fs.statSync(path.join(dir, file)).size;
  return budgets
    .filter((b) => b.pattern.test(file) && size > b.max)
    .map((b) => `${file}: ${size} B exceeds the ${b.label} budget of ${b.max} B`);
});

if (failures.length > 0) {
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log('Bundle size budget OK');
