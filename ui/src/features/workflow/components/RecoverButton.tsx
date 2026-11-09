// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { RequireControl } from '@/features/supervisors/components/RequireControl';
import { InlineError } from '@/components/common/InlineError';
import { Modal } from '@/components/common/Modal';
import { buttonVariants } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { ICON_TONE } from '@/components/ui/iconTone';
import {
  controlErrorMessage,
  useExecutionControl,
} from '../hooks/useExecutionControl';
import { Tip } from '@/components/ui/tooltip';

const BUTTON = buttonVariants({ variant: 'outline' });

/** Recover for an ABORTED run: confirms, continues it as a new run, and reports the new id. */
export function RecoverButton({
  sessionId,
  workflowId,
  executionId,
  onRecovered,
  size,
  iconOnly = false,
}: {
  sessionId: string;
  workflowId: string;
  executionId: string;
  onRecovered: (executionId: string) => void;
  size?: 'sm';
  /** Icon only: "Recover" moves to the tooltip and accessible name. */
  iconOnly?: boolean;
}) {
  const { recover } = useExecutionControl(sessionId, workflowId);
  const [confirming, setConfirming] = useState(false);

  return (
    <>
      <RequireControl
        perm="controlRuns"
        command="recover"
        target={{ execution: executionId }}
      >
        <Tip label={iconOnly ? 'Recover this run' : undefined}>
          <button
            type="button"
            className={cn(
              buttonVariants({
                variant: 'outline',
                size: iconOnly ? 'icon' : size,
              }),
              iconOnly && ICON_TONE.primary
            )}
            aria-label={iconOnly ? 'Recover' : undefined}
            disabled={recover.isPending}
            onClick={() => setConfirming(true)}
          >
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-primary text-base"
            >
              history
            </span>
            {!iconOnly && 'Recover'}
          </button>
        </Tip>
      </RequireControl>
      <InlineError
        message={recover.error ? controlErrorMessage(recover.error) : null}
      />
      {confirming && (
        <Modal title="Recover this run?" onClose={() => setConfirming(false)}>
          <p className="text-body-md text-on-surface-variant mb-4">
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
