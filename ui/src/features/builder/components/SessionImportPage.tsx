// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Button } from '@/components/ui/button';
import { textareaFieldClassName } from '@/components/ui/native-select';
import { Callout } from '@/components/common/Callout';
import { PageHeader } from '@/components/layout/PageHeader';
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

  const readFile = async (file: File | undefined) => {
    if (file) {
      setText(await file.text());
      setReview(null);
    }
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
    <div className="flex flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
      <PageHeader
        title="Import a session"
        description="Load a session exported from Yukta or written by hand. Nothing is saved until you apply it."
      />
      <label className="flex flex-col gap-1 text-label-md text-on-surface">
        Session file (.json)
        <input
          type="file"
          accept=".json,application/json"
          onChange={(event) => void readFile(event.target.files?.[0])}
        />
      </label>
      <label className="flex flex-col gap-1 text-label-md text-on-surface">
        Or paste the JSON
        <textarea
          value={text}
          onChange={(event) => {
            setText(event.target.value);
            setReview(null);
          }}
          rows={12}
          spellCheck={false}
          className={`${textareaFieldClassName} text-code-sm`}
        />
      </label>
      <div>
        <Button
          type="button"
          onClick={() => void check()}
          disabled={busy || text.trim() === ''}
        >
          Check
        </Button>
      </div>
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
