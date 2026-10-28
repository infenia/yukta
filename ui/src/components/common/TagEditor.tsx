// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

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

/** Add, edit and remove key/value rows. */
export function TagEditor({
  rows,
  onChange,
}: {
  rows: TagRow[];
  onChange: (rows: TagRow[]) => void;
}) {
  const error = tagsError(rows);
  const edit = (index: number, patch: Partial<TagRow>) =>
    onChange(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)));

  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="text-label-md text-on-surface">Tags</legend>
      {rows.map((row, index) => (
        <div key={index} className="flex items-center gap-2">
          <Input
            aria-label={`Tag ${index + 1} key`}
            placeholder="key"
            value={row.key}
            onChange={(event) => edit(index, { key: event.target.value })}
          />
          <Input
            aria-label={`Tag ${index + 1} value`}
            placeholder="value"
            value={row.value}
            onChange={(event) => edit(index, { value: event.target.value })}
          />
          <Button
            type="button"
            variant="ghost"
            size="sm"
            aria-label={`Remove tag ${index + 1}`}
            onClick={() => onChange(rows.filter((_, i) => i !== index))}
          >
            <span className="material-symbols-outlined" aria-hidden="true">
              close
            </span>
          </Button>
        </div>
      ))}
      {error && <span className="text-body-sm text-error">{error}</span>}
      <div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => onChange([...rows, { key: '', value: '' }])}
        >
          Add tag
        </Button>
      </div>
    </fieldset>
  );
}
