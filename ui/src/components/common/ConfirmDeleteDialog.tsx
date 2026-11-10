// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ApiError } from '@/lib/apiClient';
import { Callout } from './Callout';
import { Modal } from './Modal';
import { activeRunIds } from './ConfirmDeleteDialog.helpers';

/**
 * A delete the user must confirm by typing the id. Active runs (409) are shown before anything is
 * stopped, with a second confirmation to stop them and delete. Once deleted it says so and a
 * button leaves for the parent page.
 */
export function ConfirmDeleteDialog({
  title,
  id,
  removes,
  keeps,
  remove,
  runLink,
  onDone,
  onClose,
}: {
  title: string;
  /** The session or workflow id the user types to confirm. */
  id: string;
  removes: string;
  keeps: string;
  remove: (force: boolean) => Promise<unknown>;
  /** Where a run id links to. */
  runLink: (runId: string) => string;
  /** Called by the button shown once deleted (invalidate and leave). */
  onDone: () => void;
  onClose: () => void;
}) {
  const [typed, setTyped] = useState('');
  const [runs, setRuns] = useState<string[]>([]);
  const [forceConfirmed, setForceConfirmed] = useState(false);
  const del = useMutation({
    mutationFn: (force: boolean) => remove(force),
    onError: (error) => {
      setForceConfirmed(false);
      setRuns(
        error instanceof ApiError && error.status === 409
          ? activeRunIds(error.message)
          : []
      );
    },
  });
  const error = del.error;
  const blocked = error instanceof ApiError && error.status === 409;
  const failure = blocked
    ? null
    : error instanceof ApiError && error.status === 403
      ? 'Deleting needs an ADMIN key.'
      : error?.message;

  if (del.isSuccess) {
    return (
      <Modal title={title} onClose={onDone}>
        <div className="flex flex-col gap-3">
          <Callout kind="success" compact>
            Deleted {id}.
          </Callout>
          <div className="flex justify-end">
            <Button type="button" onClick={onDone}>
              Done
            </Button>
          </div>
        </div>
      </Modal>
    );
  }

  return (
    <Modal title={title} onClose={onClose}>
      <div className="flex flex-col gap-3">
        <Callout kind="danger" compact>
          This removes {removes}. It stays: {keeps}. This cannot be undone.
        </Callout>
        {blocked && (
          <Callout kind="warning" title="Runs are still active">
            <ul>
              {runs.map((run) => (
                <li key={run}>
                  <Link className="text-primary font-mono" to={runLink(run)}>
                    {run}
                  </Link>
                </li>
              ))}
            </ul>
            {runs.length === 0 && error.message}
          </Callout>
        )}
        <label className="text-label-md text-on-surface flex flex-col gap-1">
          Type {id} to confirm
          <Input
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
            autoComplete="off"
          />
        </label>
        {failure && (
          <Callout kind="danger" compact>
            {failure}
          </Callout>
        )}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          {blocked ? (
            forceConfirmed ? (
              <Button
                type="button"
                variant="destructive"
                disabled={typed !== id || del.isPending}
                onClick={() => del.mutate(true)}
              >
                Yes, stop runs and delete
              </Button>
            ) : (
              <Button
                type="button"
                variant="destructive"
                disabled={typed !== id}
                onClick={() => setForceConfirmed(true)}
              >
                Stop runs and delete
              </Button>
            )
          ) : (
            <Button
              type="button"
              variant="destructive"
              disabled={typed !== id || del.isPending}
              onClick={() => del.mutate(false)}
            >
              Delete
            </Button>
          )}
        </div>
      </div>
    </Modal>
  );
}
