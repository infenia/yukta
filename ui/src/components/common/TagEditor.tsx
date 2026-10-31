// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { tagsError, parseTag } from './TagEditor.helpers';
import type { TagRow } from './TagEditor.helpers';

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
