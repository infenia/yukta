// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMemo, useState, type DragEvent } from 'react';
import { useNavigate } from 'react-router';
import { Button } from '@/components/ui/button';
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

/** Import a session from JSON: check it, see what it changes, then apply. */
export function SessionImportPage() {
  const navigate = useNavigate();
  const [text, setText] = useState('');
  const [problems, setProblems] = useState<string[]>([]);
  const [review, setReview] = useState<Review | null>(null);
  const [busy, setBusy] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [fileName, setFileName] = useState<string | null>(null);

  // Checked in the browser on every change; the Check button then asks the server.
  const live = useMemo(
    () => (text.trim() === '' ? null : parseSession(text)),
    [text]
  );

  const readFile = async (file: File | undefined) => {
    if (file) {
      setText(await file.text());
      setFileName(file.name);
      setReview(null);
      setProblems([]);
    }
  };

  const drop = (event: DragEvent) => {
    event.preventDefault();
    setDragging(false);
    void readFile(event.dataTransfer.files[0]);
  };

  const check = async () => {
    setBusy(true);
    setReview(null);
    const parsed = parseSession(text);
    if ('error' in parsed) {
      setProblems([parsed.error]);
      setBusy(false);
      return;
    }
    const { session } = parsed;
    const found: string[] = [];
    for (const [id, workflow] of Object.entries(session.workflows)) {
      try {
        const outcome = await validateWorkflow(workflow);
        if (!outcome.valid) found.push(`${id}: ${outcome.message}`);
      } catch (e) {
        found.push(`${id}: ${(e as Error).message}`);
      }
    }
    setProblems(found);
    if (found.length === 0) {
      let existing: SessionDraft | null = null;
      try {
        existing = await getSessionDraft(session.sessionId);
      } catch (e) {
        if (!(e instanceof ApiError && e.status === 404)) {
          setProblems([(e as Error).message]);
          setBusy(false);
          return;
        }
      }
      setReview({
        session,
        existing,
        redacted: text.includes(REDACTION_MARKER),
      });
    }
    setBusy(false);
  };

  const apply = async (toSave: Review) => {
    setBusy(true);
    try {
      await saveSession(toSave.session);
      void navigate(
        `/sessions/${encodeURIComponent(toSave.session.sessionId)}`
      );
    } catch (e) {
      setProblems([(e as Error).message]);
      setBusy(false);
    }
  };

  return (
    <div className="bg-background flex flex-1 flex-col gap-0">
      <div className="relative isolate px-4 pt-6 sm:px-6">
        <PageHeader
          backdrop
          title="Import session"
          description="Load a session exported from Yukta or written by hand. Nothing is saved until you apply it."
        />
      </div>
      <div className="flex flex-col gap-6 px-4 pb-8 sm:px-6">
        <section className="border-border bg-card shadow-card flex flex-col gap-5 rounded-2xl border p-5 sm:p-6">
          <label
            onDragOver={(event) => {
              event.preventDefault();
              setDragging(true);
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={drop}
            className={cn(
              'border-border text-on-surface-variant focus-within:ring-ring/30 hover:bg-muted/50 flex cursor-pointer flex-col items-center gap-1 rounded-xl border-2 border-dashed px-4 py-6 text-center transition-colors focus-within:ring-3',
              dragging && 'border-primary bg-accent'
            )}
          >
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-primary text-3xl"
            >
              upload
            </span>
            <span className="text-label-md text-on-surface">
              {fileName ?? 'Drop a .json file here, or click to browse'}
            </span>
            <input
              type="file"
              aria-label="Session file (.json)"
              accept=".json,application/json"
              className="sr-only"
              onChange={(event) => void readFile(event.target.files?.[0])}
            />
          </label>
          <div className="text-label-sm text-on-surface-variant flex items-center gap-3">
            <span className="bg-border h-px flex-1" />
            or
            <span className="bg-border h-px flex-1" />
          </div>
          <div className="flex flex-col gap-2">
            <label
              htmlFor="session-json"
              className="text-label-md text-on-surface"
            >
              Or paste the JSON
            </label>
            <textarea
              id="session-json"
              value={text}
              onChange={(event) => {
                setText(event.target.value);
                setFileName(null);
                setReview(null);
              }}
              rows={14}
              spellCheck={false}
              placeholder='{ "sessionId": "my-session", "workflows": { ... } }'
              aria-invalid={live !== null && 'error' in live}
              className={`${textareaFieldClassName} text-code-sm`}
            />
            <LiveStatus live={live} />
          </div>
          <div className="flex justify-end">
            <Button
              type="button"
              onClick={() => void check()}
              disabled={busy || text.trim() === ''}
            >
              Check
            </Button>
          </div>
        </section>
        {problems.length > 0 && (
          <Callout kind="danger" title="This cannot be imported">
            <ul>
              {problems.map((problem) => (
                <li key={problem}>{problem}</li>
              ))}
            </ul>
          </Callout>
        )}
        {review && (
          <ReviewPanel
            review={review}
            busy={busy}
            onApply={() => void apply(review)}
          />
        )}
      </div>
    </div>
  );
}

function LiveStatus({
  live,
}: {
  live: ReturnType<typeof parseSession> | null;
}) {
  if (live === null) {
    return (
      <p className="text-body-sm text-on-surface-variant">
        The JSON is checked as you type.
      </p>
    );
  }
  const bad = 'error' in live;
  return (
    <p
      role="status"
      className={cn(
        'text-body-sm flex items-center gap-1.5',
        bad ? 'text-destructive' : 'text-success'
      )}
    >
      <span aria-hidden="true" className="material-symbols-outlined text-base">
        {bad ? 'error' : 'check_circle'}
      </span>
      {bad
        ? live.error
        : `Valid JSON: session ${live.session.sessionId}, ${Object.keys(live.session.workflows).length} workflow(s).`}
    </p>
  );
}

function ReviewPanel({
  review,
  busy,
  onApply,
}: {
  review: Review;
  busy: boolean;
  onApply: () => void;
}) {
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
      <div>
        <Button type="button" onClick={onApply} disabled={busy}>
          {existing ? 'Replace session' : 'Import session'}
        </Button>
      </div>
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
