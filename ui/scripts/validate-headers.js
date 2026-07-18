#!/usr/bin/env node

// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { glob } from 'glob';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const SPDX_HEADER_JS = `// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited`;

const SPDX_HEADER_CSS = `/* SPDX-License-Identifier: Apache-2.0 */
/* SPDX-FileCopyrightText: 2026 Infenia Private Limited */`;

const SPDX_HEADER_JSON = `/*
 * SPDX-License-Identifier: Apache-2.0
 * SPDX-FileCopyrightText: 2026 Infenia Private Limited
 */`;

const SPDX_HEADER_HTML = `<!--
  SPDX-License-Identifier: Apache-2.0
  SPDX-FileCopyrightText: 2026 Infenia Private Limited
-->`;

const patterns = [
  'src/**/*.ts',
  'src/**/*.tsx',
  'src/**/*.js',
  '*.config.ts',
  '*.config.js',
  'vitest.config.ts',
  'playwright.config.ts',
  'index.html',
  'src/**/*.css',
];

const ignorePatterns = ['dist/**', 'node_modules/**', 'build/**', 'coverage/**'];

let errors = [];

function getHeaderForFile(filePath) {
  const ext = path.extname(filePath);

  if (ext === '.css') return SPDX_HEADER_CSS;
  if (ext === '.json') return SPDX_HEADER_JSON;
  if (ext === '.html') return SPDX_HEADER_HTML;
  return SPDX_HEADER_JS; // Default for .ts, .tsx, .js
}

function validateFile(filePath) {
  try {
    const content = fs.readFileSync(filePath, 'utf-8');
    const expectedHeader = getHeaderForFile(filePath);

    if (!content.startsWith(expectedHeader)) {
      errors.push(`${filePath}: missing or incorrect SPDX header`);
      return false;
    }
    return true;
  } catch (err) {
    errors.push(`${filePath}: error reading file - ${err.message}`);
    return false;
  }
}

// Find all matching files
let filesToCheck = [];
for (const pattern of patterns) {
  try {
    const matches = await glob(pattern, {
      ignore: ignorePatterns,
      cwd: process.cwd(),
    });
    filesToCheck.push(...matches);
  } catch (err) {
    console.warn(`Warning: glob pattern failed for ${pattern}: ${err.message}`);
  }
}

// Remove duplicates
filesToCheck = [...new Set(filesToCheck)];

if (filesToCheck.length === 0) {
  console.warn('No files found to validate');
  process.exit(0);
}

console.log(`Validating SPDX headers in ${filesToCheck.length} file(s)...`);

filesToCheck.forEach((file) => {
  validateFile(file);
});

if (errors.length > 0) {
  console.error('\n❌ SPDX header validation failed:');
  errors.forEach((err) => console.error(`  ${err}`));
  process.exit(1);
} else {
  console.log(`✅ All ${filesToCheck.length} file(s) have valid SPDX headers`);
  process.exit(0);
}
