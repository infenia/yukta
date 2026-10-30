// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { RequirePermission } from '@/components/common/RequirePermission';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { cn } from '@/lib/utils';
import { CodeBlock } from '@/components/common/CodeBlock';
import { toCommand, type CommandFormat } from '@/lib/commandEquivalents';
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

/** A session ID made from a name: lower case, with every run of other characters one dash. */
export function slugify(name: string): string {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
}

const FORMATS: [CommandFormat, string][] = [
  ['mcp', 'MCP'],
  ['curl', 'curl'],
  ['cli', 'CLI'],
];

/** The session this form would create, as an MCP call, curl or the CLI; it updates as you type. */
function ScriptedPanel({ session }: { session: SessionDraft }) {
  const [format, setFormat] = useState<CommandFormat>('mcp');
  return (
    <section
      aria-labelledby="scripted-heading"
      className="flex min-w-0 flex-col gap-2 lg:sticky lg:top-18 lg:self-start"
    >
      <h2 id="scripted-heading" className="text-label-md text-on-surface">
        Same thing, scripted
      </h2>
      <div role="tablist" className="flex gap-1">
        {FORMATS.map(([key, label]) => (
          <button
            key={key}
            type="button"
            role="tab"
            aria-selected={format === key}
            onClick={() => setFormat(key)}
            className={cn(
              'text-label-md rounded-lg px-3 py-1',
              format === key
                ? 'bg-muted text-on-surface'
                : 'text-on-surface-variant hover:bg-muted'
            )}
          >
            {label}
          </button>
        ))}
      </div>
      <CodeBlock
        label="command"
        code={toCommand(
          format,
          { kind: 'createSession', session },
          window.location.origin
        )}
      />
      <p className="text-body-sm text-on-surface-variant">
        Updates as you type. Agents can create the same session through the
        Yukta MCP server.
      </p>
    </section>
  );
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
  // A new session's ID follows its name until the ID is edited by hand.
  const [idEdited, setIdEdited] = useState(false);
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

  const change = (key: keyof Fields, value: string) => {
    if (key === 'sessionId') setIdEdited(true);
    const follows = key === 'name' && !loaded && !idEdited;
    setFields({
      ...fields,
      [key]: value,
      ...(follows ? { sessionId: slugify(value) } : {}),
    });
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
        aria-describedby={hint ? `s-${key}-hint` : undefined}
        value={fields[key]}
        onChange={(event) => change(key, event.target.value)}
      />
      {hint && (
        <span
          id={`s-${key}-hint`}
          className="text-body-sm text-on-surface-variant"
        >
          {hint}
        </span>
      )}
    </div>
  );

  const form = (
    <form onSubmit={submit} className="flex min-w-0 flex-col gap-4">
      {field('name', 'Name')}
      {field(
        'sessionId',
        'Session ID',
        loaded
          ? "Used in URLs, the CLI and MCP calls; it can't be changed."
          : "Made from the name. Used in URLs, the CLI and MCP calls, and can't be changed later."
      )}
      {field('description', 'Description')}
      {field(
        'projectPath',
        'Project path',
        'Absolute path on the server, not on your machine'
      )}
      {field('initiator', 'Initiator', 'Who or what starts its runs')}
      <TagEditor rows={rows} onChange={setRows} />
      <InlineError message={error} />
      <div className="flex justify-end gap-2">
        <Button
          type="button"
          variant="ghost"
          onClick={() =>
            navigate(
              loaded ? `/sessions/${encodeURIComponent(loaded.sessionId)}` : '/'
            )
          }
        >
          Cancel
        </Button>
        <RequirePermission perm="editSessions">
          <Button type="submit" disabled={busy}>
            {!loaded && (
              <span
                aria-hidden="true"
                className="material-symbols-outlined text-base"
              >
                add
              </span>
            )}
            {loaded ? 'Save' : 'Create session'}
          </Button>
        </RequirePermission>
      </div>
    </form>
  );

  if (loaded) {
    return (
      <div className="mx-auto flex w-full max-w-xl flex-col gap-4 px-4 py-6 sm:px-6">
        <PageHeader title={`Edit ${loaded.sessionId}`} />
        {form}
      </div>
    );
  }
  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-4 px-4 py-6 sm:px-6">
      <PageHeader
        title="New session"
        description="A session groups the workflows of one project. You add workflows after creating it, or import a whole session as JSON."
      />
      <nav
        aria-label="How to create"
        className="border-border flex gap-5 border-b"
      >
        <span
          aria-current="page"
          className="text-label-md border-primary text-on-surface -mb-px border-b-2 py-2"
        >
          Fill in details
        </span>
        <Link
          to="/sessions/import"
          className="text-label-md text-on-surface-variant hover:text-on-surface py-2"
        >
          Import JSON
        </Link>
      </nav>
      <div className="grid gap-8 lg:grid-cols-[minmax(0,36rem)_minmax(0,1fr)]">
        {form}
        <ScriptedPanel
          session={{ ...starterSession(fields), tags: toTags(rows) }}
        />
      </div>
    </div>
  );
}
