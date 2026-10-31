// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { RequirePermission } from '@/components/common/RequirePermission';
import { InlineError } from '@/components/common/InlineError';
import { Modal } from '@/components/common/Modal';
import { buttonVariants } from '@/components/ui/button';
import {
  controlErrorMessage,
  useExecutionControl,
} from '../hooks/useExecutionControl';

const BUTTON = buttonVariants({ variant: 'outline' });

/** Recover for an ABORTED run: confirms, continues it as a new run, and reports the new id. */
export function RecoverButton({
  sessionId,
  workflowId,
  executionId,
  onRecovered,
  size,
}: {
  sessionId: string;
  workflowId: string;
  executionId: string;
  onRecovered: (executionId: string) => void;
  size?: 'sm';
}) {
  const { recover } = useExecutionControl(sessionId, workflowId);
  const [confirming, setConfirming] = useState(false);

  return (
    <>
      <RequirePermission perm="controlRuns">
        <button
          type="button"
          className={buttonVariants({ variant: 'outline', size })}
          disabled={recover.isPending}
          onClick={() => setConfirming(true)}
        >
          <span className="material-symbols-outlined text-base">history</span>
          Recover
        </button>
      </RequirePermission>
      <InlineError
        message={recover.error ? controlErrorMessage(recover.error) : null}
      />
      {confirming && (
        <Modal title="Recover this run?" onClose={() => setConfirming(false)}>
          <p className="mb-4 text-body-md text-on-surface-variant">
            Continue this run as a new run? Finished nodes are replayed.
          </p>
          <div className="flex justify-end gap-2">
            <button
              type="button"
              className={BUTTON}
              onClick={() => setConfirming(false)}
            >
              Cancel
            </button>
            <button
              type="button"
              className={BUTTON}
              onClick={() => {
                setConfirming(false);
                recover.mutate(executionId, {
                  onSuccess: (result) => onRecovered(result.executionId),
                });
              }}
            >
              Recover
            </button>
          </div>
        </Modal>
      )}
    </>
  );
}
