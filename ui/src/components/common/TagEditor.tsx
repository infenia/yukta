// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';

export interface TagRow {
  key: string;
  value: string;
}

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

/**
 * Tags as `key: value` chips, as on the New session artboard: type one and press Enter (or a
 * comma) to add it, × to remove it, or click a chip to edit it in the box again.
 */
export function TagEditor({
  rows,
  onChange,
}: {
  rows: TagRow[];
  onChange: (rows: TagRow[]) => void;
}) {
  const [draft, setDraft] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const error = problem ?? tagsError(rows);
  const add = () => {
    if (!draft.trim()) return;
    const tag = parseTag(draft, rows);
    if ('error' in tag) {
      setProblem(tag.error);
      return;
    }
    onChange([...rows, tag]);
    setDraft('');
    setProblem(null);
  };
  const label = ({ key, value }: TagRow) => (value ? `${key}: ${value}` : key);

  return (
    <fieldset className="flex flex-col gap-1.5">
      <legend className="text-label-md text-on-surface mb-1.5">Tags</legend>
      <div className="bg-muted flex flex-wrap items-center gap-1.5 rounded-2xl px-2 py-1.5">
        {rows.map((row, index) => (
          <span
            key={index}
            className="border-border bg-card text-label-sm inline-flex h-6 items-center gap-1 rounded-full border pr-1 pl-2.5"
          >
            <button
              type="button"
              title="Edit this tag"
              onClick={() => {
                setDraft(label(row));
                onChange(rows.filter((_, i) => i !== index));
              }}
            >
              {label(row)}
            </button>
            <button
              type="button"
              aria-label={`Remove tag ${row.key}`}
              onClick={() => onChange(rows.filter((_, i) => i !== index))}
              className="material-symbols-outlined text-on-surface-variant hover:text-on-surface text-sm"
            >
              close
            </button>
          </span>
        ))}
        <input
          aria-label="Add a tag"
          placeholder="key: value"
          value={draft}
          onChange={(event) => {
            setDraft(event.target.value);
            setProblem(null);
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ',') {
              event.preventDefault();
              add();
            }
          }}
          onBlur={add}
          className="text-body-md placeholder:text-on-surface-variant min-w-32 flex-1 bg-transparent px-1 outline-none"
        />
      </div>
      {error && <span className="text-body-sm text-error">{error}</span>}
    </fieldset>
  );
}
