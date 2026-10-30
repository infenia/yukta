// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** The rows of a tag map, in order. */
export function toRows(tags: Record<string, string>): TagRow[] {
  return Object.entries(tags).map(([key, value]) => ({ key, value }));
}

/** Why the rows cannot be saved, or null: keys must be non-empty and unique. */
export function tagsError(rows: TagRow[]): string | null {
  const seen = new Set<string>();
  for (const { key } of rows) {
    const name = key.trim();
    if (!name) return 'A tag needs a key.';
    if (seen.has(name)) return `The tag key "${name}" is used twice.`;
    seen.add(name);
  }
  return null;
}

/** The tag map the rows stand for (call only when tagsError is null). */
export function toTags(rows: TagRow[]): Record<string, string> {
  return Object.fromEntries(rows.map(({ key, value }) => [key.trim(), value]));
}

/** A tag typed as `key: value` (the value may be empty), or why it can't be added. */
export function parseTag(
  text: string,
  rows: TagRow[]
): TagRow | { error: string } {
  const colon = text.indexOf(':');
  const key = (colon < 0 ? text : text.slice(0, colon)).trim();
  const value = colon < 0 ? '' : text.slice(colon + 1).trim();
  if (!key) return { error: 'A tag needs a key: write key: value.' };
  if (rows.some((row) => row.key.trim() === key)) {
    return { error: `The tag key "${key}" is used already.` };
  }
  return { key, value };
}

export interface TagRow {
  key: string;
  value: string;
}
