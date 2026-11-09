// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

const OK = new Set(['success', 'ok', 'true', 'yes', 'pass', 'passed']);
const BAD = new Set(['failure', 'failed', 'error', 'false', 'no', 'fail']);
const WARN = new Set(['warning', 'warn', 'retry', 'timeout', 'skipped']);
/** Every other port name gets one of these, picked by its name so it stays the same everywhere. */
const SPREAD = [
  'var(--pal-teal)',
  'var(--pal-sky)',
  'var(--pal-violet)',
  'var(--pal-magenta)',
  'var(--color-primary)',
  'var(--color-attention)',
];

/** A CSS color for an output port: success and failure keep their tokens, the rest are told apart. */
export function portColor(port: string | null): string {
  const name = port?.toLowerCase() ?? 'default';
  if (OK.has(name)) return 'var(--color-success)';
  if (BAD.has(name)) return 'var(--color-error)';
  if (WARN.has(name)) return 'var(--color-warning)';
  if (name === 'default') return 'var(--node-accent)';
  let hash = 0;
  for (const char of name) hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  return SPREAD[hash % SPREAD.length];
}
