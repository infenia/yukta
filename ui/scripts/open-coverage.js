// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import open from 'open';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const coveragePath = path.join(__dirname, '..', 'coverage', 'index.html');

open(coveragePath).catch((err) => {
  console.error('Failed to open coverage report:', err.message);
  process.exit(1);
});
