// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useMemo, useState, type DragEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { Button, buttonVariants } from '@/components/ui/button';
import { textareaFieldClassName } from '@/components/ui/native-select';
import { Callout } from '@/components/common/Callout';
import { PageHeader } from '@/components/layout/PageHeader';
import { cn } from '@/lib/utils';
import { ApiError } from '@/lib/apiClient';
import { getSessionDraft, saveSession, validateWorkflow } from '../api';
import { REDACTION_MARKER, diffWorkflow, parseSession } from '../importSession';
import type { DraftWorkflow, SessionDraft } from '../types';

interface Review {
  session: SessionDraft;
  /** The session already on the server, or null when this one is new. */
  existing: SessionDraft | null;
  redacted: boolean;
}

/** How long the JSON must sit still before it is checked with the server. */
const CHECK_DELAY_MS = 500;

/** Asks the server about every workflow; with none refused, finds out whether the session exists. */
async function verify(
  session: SessionDraft,
  text: string
): Promise<{ problems: string[]; review: Review | null }> {
  const found: string[] = [];
  for (const [id, workflow] of Object.entries(session.workflows)) {
    try {
      const outcome = await validateWorkflow(workflow);
      if (!outcome.valid) found.push(`${id}: ${outcome.message}`);
    } catch (e) {
      found.push(`${id}: ${(e as Error).message}`);
    }
  }
  if (found.length > 0) return { problems: found, review: null };
  let existing: SessionDraft | null = null;
  try {
    existing = await getSessionDraft(session.sessionId);
  } catch (e) {
    if (!(e instanceof ApiError && e.status === 404)) {
      return { problems: [(e as Error).message], review: null };
    }
  }
  return {
    problems: [],
    review: { session, existing, redacted: text.includes(REDACTION_MARKER) },
  };
}

type Source = 'file' | 'paste';

/** Import a session from JSON: it is checked as it arrives, then one button applies it. */
export function SessionImportPage() {
  const navigate = useNavigate();
  const [text, setText] = useState('');
  const [source, setSource] = useState<Source>('file');
  const [problems, setProblems] = useState<string[]>([]);
  const [review, setReview] = useState<Review | null>(null);
  const [saving, setSaving] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [fileName, setFileName] = useState<string | null>(null);

  // Parsed in the browser on every change; a valid one is then checked with the server.
  const live = useMemo(
    () => (text.trim() === '' ? null : parseSession(text)),
    [text]
  );

  useEffect(() => {
    if (!live || 'error' in live) return undefined;
    let current = true;
    const timer = setTimeout(() => {
      void verify(live.session, text).then((outcome) => {
        if (!current) return;
        setProblems(outcome.problems);
        setReview(outcome.review);
      });
    }, CHECK_DELAY_MS);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [live, text]);

  const load = (next: string, name: string | null) => {
    setText(next);
    setFileName(name);
    setReview(null);
    setProblems([]);
  };

  const readFile = async (file: File | undefined) => {
    if (file) load(await file.text(), file.name);
  };

  const drop = (event: DragEvent) => {
    event.preventDefault();
    setDragging(false);
    void readFile(event.dataTransfer.files[0]);
  };

  const apply = async (toSave: Review) => {
    setSaving(true);
    try {
      await saveSession(toSave.session);
      void navigate(
        `/sessions/${encodeURIComponent(toSave.session.sessionId)}`
      );
    } catch (e) {
      setProblems([(e as Error).message]);
      setSaving(false);
    }
  };

  return (
    <div className="bg-background flex flex-1 flex-col gap-0">
      <div className="relative isolate px-4 pt-6 sm:px-6">
        <PageHeader
          backdrop
          title="Import session"
          description="Load a session exported from Yukta or written by hand."
        />
      </div>
      <div className="flex flex-col gap-5 px-4 pb-8 sm:px-6">
        {source === 'file' ? (
          <label
            onDragOver={(event) => {
              event.preventDefault();
              setDragging(true);
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={drop}
            className={cn(
              'border-border bg-muted/30 text-on-surface-variant transition-standard focus-within:ring-ring/30 hover:bg-muted/60 flex min-h-80 cursor-pointer flex-col items-center justify-center gap-3 rounded-2xl border-2 border-dashed px-6 py-10 text-center focus-within:ring-3',
              dragging && 'border-primary bg-accent'
            )}
          >
            <span className="border-border bg-card shadow-card flex size-14 items-center justify-center rounded-2xl border">
              <span
                aria-hidden="true"
                className="material-symbols-outlined text-3xl"
              >
                {fileName ? 'description' : 'upload'}
              </span>
            </span>
            <span className="text-label-md text-on-surface">
              {fileName ?? 'Drop a .json file here, or click to browse'}
            </span>
            <span className="text-body-sm">
              {fileName
                ? 'Drop or choose another file to replace it.'
                : 'A session exported from Yukta, or one written by hand.'}
            </span>
            <input
              type="file"
              aria-label="Session file (.json)"
              accept=".json,application/json"
              className="sr-only"
              onChange={(event) => void readFile(event.target.files?.[0])}
            />
          </label>
        ) : (
          <div className="flex flex-col gap-2">
            <label
              htmlFor="session-json"
              className="text-label-md text-on-surface"
            >
              Paste the JSON
            </label>
            <textarea
              id="session-json"
              value={text}
              onChange={(event) => load(event.target.value, null)}
              rows={14}
              spellCheck={false}
              placeholder='{ "sessionId": "my-session", "workflows": { ... } }'
              aria-invalid={live !== null && 'error' in live}
              className={`${textareaFieldClassName} text-code-sm min-h-80`}
            />
          </div>
        )}
        <div className="text-label-sm text-on-surface-variant flex items-center gap-4">
          <span className="bg-border h-px flex-1" />
          or
          <span className="bg-border h-px flex-1" />
        </div>
        <div className="flex justify-center">
          <Button
            type="button"
            variant="outline"
            size="lg"
            onClick={() => setSource(source === 'file' ? 'paste' : 'file')}
          >
            {source === 'file' ? 'Paste JSON' : 'Upload a file'}
          </Button>
        </div>
        {live !== null && (
          <Summary live={live} problems={problems} review={review} />
        )}
        <div className="border-border flex items-center justify-end gap-4 border-t pt-5">
          <div className="flex items-center gap-2">
            <Link
              to="/sessions/new"
              className={buttonVariants({ variant: 'ghost' })}
            >
              Cancel
            </Link>
            <Button
              type="button"
              onClick={() => review && void apply(review)}
              disabled={!review || saving}
            >
              {review?.existing ? 'Replace session' : 'Import session'}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}

/** What the import would do, or why it cannot: the one place the checks are reported. */
function Summary({
  live,
  problems,
  review,
}: {
  live: ReturnType<typeof parseSession>;
  problems: string[];
  review: Review | null;
}) {
  if ('error' in live) {
    return (
      <Callout kind="danger" title="This cannot be imported">
        {live.error}
      </Callout>
    );
  }
  if (problems.length > 0) {
    return (
      <Callout kind="danger" title="This cannot be imported">
        <ul>
          {problems.map((problem) => (
            <li key={problem}>{problem}</li>
          ))}
        </ul>
      </Callout>
    );
  }
  if (!review) {
    return (
      <p
        aria-live="polite"
        className="text-body-sm text-on-surface-variant flex items-center gap-2"
      >
        <span className="live-dot bg-attention size-1.5 rounded-full" />
        Checking with the server…
      </p>
    );
  }
  const { existing } = review;
  return (
    <section aria-label="Review" className="flex flex-col gap-3">
      {review.redacted && (
        <Callout kind="warning" compact>
          Secrets are redacted in this file ({REDACTION_MARKER}); they stay
          redacted after the import, so set them again.
        </Callout>
      )}
      {existing ? (
        <>
          <Callout kind="warning" compact>
            Session {review.session.sessionId} exists. Import replaces it; edits
            made since you opened this page are lost.
          </Callout>
          {Object.entries(review.session.workflows).map(([id, workflow]) => (
            <div key={id}>
              <h3 className="text-label-md text-on-surface">{id}</h3>
              <ul className="text-code-sm">
                {changes(existing.workflows[id], workflow)}
              </ul>
            </div>
          ))}
        </>
      ) : (
        <Callout kind="info" compact>
          New session {review.session.sessionId} with{' '}
          {Object.keys(review.session.workflows).length} workflow(s).
        </Callout>
      )}
    </section>
  );
}

function changes(existing: DraftWorkflow | undefined, imported: DraftWorkflow) {
  const lines = diffWorkflow(existing, imported);
  return lines.length === 0 ? (
    <li>no changes</li>
  ) : (
    lines.map((line) => <li key={line}>{line}</li>)
  );
}
