// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { InlineError } from '@/components/common/InlineError';
import { ApiError } from '@/lib/apiClient';
import { saveSession } from '../api';
import type { SessionDraft } from '../types';

const INPUT = 'h-8 w-full border border-outline-variant bg-surface px-2 text-sm text-on-surface';

/** A session needs a workflow, so a new one starts with a trigger wired to a console terminal. */
export function starterSession(fields: Omit<SessionDraft, 'workflows' | 'tags'>): SessionDraft {
  return {
    ...fields,
    tags: {},
    workflows: {
      main: {
        workflowId: 'main',
        description: 'Starter workflow',
        nodes: [
          { nodeId: 'start', type: 'api-trigger', config: {} },
          { nodeId: 'out', type: 'CONSOLE_TERMINAL', config: {} },
        ],
        edges: [{ source: 'start', target: 'out', sourcePort: null }],
      },
    },
  };
}

/** Create a session; it opens the editor on its starter workflow. */
export function SessionFormPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [fields, setFields] = useState({
    sessionId: '',
    name: '',
    description: '',
    initiator: 'ui',
    projectPath: '',
  });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: { preventDefault(): void }) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await saveSession(starterSession(fields));
      await queryClient.invalidateQueries({ queryKey: ['sessions'] });
      navigate(`/sessions/${encodeURIComponent(fields.sessionId)}/workflow/main/edit`);
    } catch (e) {
      // fetchApi only throws Error subclasses
      setError(
        e instanceof ApiError && e.fieldErrors?.length
          ? e.fieldErrors.map((f) => `${f.field}: ${f.message}`).join('; ')
          : (e as Error).message
      );
      setBusy(false);
    }
  };

  const field = (key: keyof typeof fields, label: string, hint?: string) => (
    <div className="flex flex-col gap-1">
      <label htmlFor={`s-${key}`} className="text-xs font-semibold text-on-surface">
        {label}
      </label>
      <input
        id={`s-${key}`}
        required
        className={INPUT}
        value={fields[key]}
        onChange={(event) => setFields({ ...fields, [key]: event.target.value })}
      />
      {hint && <span className="text-xs text-on-surface-variant">{hint}</span>}
    </div>
  );

  return (
    <form onSubmit={submit} className="mx-auto flex w-full max-w-xl flex-col gap-4 p-6">
      <h1 className="text-2xl font-bold text-on-surface">New session</h1>
      {field('sessionId', 'Session ID')}
      {field('name', 'Name')}
      {field('description', 'Description')}
      {field('initiator', 'Initiator')}
      {field('projectPath', 'Project path', 'Absolute path on the server')}
      <InlineError message={error} />
      <div>
        <button
          type="submit"
          disabled={busy}
          className="h-8 border border-outline-variant px-4 text-xs font-semibold text-on-surface hover:bg-surface-container disabled:opacity-40"
        >
          Create
        </button>
      </div>
    </form>
  );
}
