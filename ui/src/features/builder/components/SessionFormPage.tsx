// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { InlineError } from '@/components/common/InlineError';
import { StateMessage } from '@/components/common/StateMessage';
import {
  TagEditor,
  tagsError,
  toRows,
  toTags,
  type TagRow,
} from '@/components/common/TagEditor';
import { PageHeader } from '@/components/layout/PageHeader';
import { ApiError } from '@/lib/apiClient';
import { getSessionDraft, saveSession } from '../api';
import type { SessionDraft } from '../types';

/** A session needs a workflow, so a new one starts with a trigger wired to a console terminal. */
export function starterSession(
  fields: Omit<SessionDraft, 'workflows' | 'tags'>
): SessionDraft {
  return {
    ...fields,
    tags: {},
    workflows: {
      main: {
        workflowId: 'main',
        description: 'Starter workflow',
        nodes: [
          { nodeId: 'start', type: 'API_TRIGGER', config: {} },
          { nodeId: 'out', type: 'CONSOLE_TERMINAL', config: {} },
        ],
        edges: [{ source: 'start', target: 'out', sourcePort: null }],
      },
    },
  };
}

type Fields = {
  sessionId: string;
  name: string;
  description: string;
  initiator: string;
  projectPath: string;
};

function errorText(e: unknown): string {
  // fetchApi only throws Error subclasses
  return e instanceof ApiError && e.fieldErrors?.length
    ? e.fieldErrors.map((f) => `${f.field}: ${f.message}`).join('; ')
    : (e as Error).message;
}

/** Create a session, or edit the metadata and tags of one (`/sessions/:sessionId/edit`). */
export function SessionFormPage() {
  const { sessionId } = useParams();
  return sessionId ? <EditSession sessionId={sessionId} /> : <SessionForm />;
}

function EditSession({ sessionId }: { sessionId: string }) {
  const draft = useQuery({
    queryKey: ['session-draft', sessionId],
    queryFn: () => getSessionDraft(sessionId),
    gcTime: 0,
  });
  if (draft.isPending) {
    return <StateMessage tone="loading" title="Loading…" />;
  }
  if (draft.isError) {
    return <StateMessage tone="error" title="Failed to load the session" />;
  }
  return <SessionForm loaded={draft.data} />;
}

/** The form for a new session, or for `loaded` (whose workflows are kept as they are). */
function SessionForm({ loaded }: { loaded?: SessionDraft }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [fields, setFields] = useState<Fields>({
    sessionId: loaded?.sessionId ?? '',
    name: loaded?.name ?? '',
    description: loaded?.description ?? '',
    initiator: loaded?.initiator ?? 'ui',
    projectPath: loaded?.projectPath ?? '',
  });
  const [rows, setRows] = useState<TagRow[]>(toRows(loaded?.tags ?? {}));
  // What the session looked like when the page loaded; a difference at save time is a change by someone else.
  const [seen, setSeen] = useState(loaded ? JSON.stringify(loaded) : null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: { preventDefault(): void }) => {
    event.preventDefault();
    const invalidTags = tagsError(rows);
    if (invalidTags) {
      setError(invalidTags);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const metadata = { ...fields, tags: toTags(rows) };
      if (loaded) {
        const current = await getSessionDraft(loaded.sessionId);
        if (JSON.stringify(current) !== seen) {
          setSeen(JSON.stringify(current));
          setError(
            'This session changed since you opened it. Save again to keep your edits on top of the new version.'
          );
          setBusy(false);
          return;
        }
        await saveSession({
          ...current,
          ...metadata,
          workflows: current.workflows,
        });
      } else {
        await saveSession({ ...starterSession(fields), tags: metadata.tags });
      }
      await queryClient.invalidateQueries({ queryKey: ['sessions'] });
      navigate(
        loaded
          ? `/sessions/${encodeURIComponent(fields.sessionId)}`
          : `/sessions/${encodeURIComponent(fields.sessionId)}/workflow/main/edit`
      );
    } catch (e) {
      setError(errorText(e));
      setBusy(false);
    }
  };

  const field = (key: keyof Fields, label: string, hint?: string) => (
    <div className="flex flex-col gap-1">
      <label htmlFor={`s-${key}`} className="text-label-md text-on-surface">
        {label}
      </label>
      <Input
        id={`s-${key}`}
        required
        readOnly={loaded !== undefined && key === 'sessionId'}
        value={fields[key]}
        onChange={(event) =>
          setFields({ ...fields, [key]: event.target.value })
        }
      />
      {hint && (
        <span className="text-body-sm text-on-surface-variant">{hint}</span>
      )}
    </div>
  );

  return (
    <form
      onSubmit={submit}
      className="mx-auto flex w-full max-w-xl flex-col gap-4 px-4 py-6 sm:px-6"
    >
      <PageHeader title={loaded ? `Edit ${loaded.sessionId}` : 'New session'} />
      {field('sessionId', 'Session ID')}
      {field('name', 'Name')}
      {field('description', 'Description')}
      {field('initiator', 'Initiator')}
      {field('projectPath', 'Project path', 'Absolute path on the server')}
      <TagEditor rows={rows} onChange={setRows} />
      <InlineError message={error} />
      <div>
        <Button type="submit" variant="outline" disabled={busy}>
          {loaded ? 'Save' : 'Create'}
        </Button>
      </div>
    </form>
  );
}
