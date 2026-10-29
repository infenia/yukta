// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { buttonVariants } from '@/components/ui/button';
import { Modal } from '@/components/common/Modal';
import { textareaFieldClassName } from '@/components/ui/native-select';

const BUTTON = buttonVariants({ variant: 'outline' });

/** Longest reason the control audit keeps. */
export const MAX_REASON_LENGTH = 200;

/** Asks how to stop a node: drain in-flight work (default) or stop at once, with an optional reason. */
export function NodeStopDialog({
  nodeId,
  pending,
  onSubmit,
  onClose,
}: {
  nodeId: string;
  pending: boolean;
  onSubmit: (options: { immediate: boolean; reason: string }) => void;
  onClose: () => void;
}) {
  const [immediate, setImmediate] = useState(false);
  const [reason, setReason] = useState('');

  return (
    <Modal title={`Stop ${nodeId}?`} onClose={onClose}>
      <label className="text-body-md text-on-surface mb-3 flex items-center gap-2">
        <input
          type="checkbox"
          checked={immediate}
          onChange={(event) => setImmediate(event.target.checked)}
        />
        Stop immediately (do not drain)
      </label>
      <label
        className="text-body-md text-on-surface mb-1 block"
        htmlFor="stop-reason"
      >
        Reason (optional)
      </label>
      <textarea
        id="stop-reason"
        value={reason}
        maxLength={MAX_REASON_LENGTH}
        onChange={(event) => setReason(event.target.value)}
        rows={3}
        className={`${textareaFieldClassName} mb-1`}
      />
      <p className="text-body-sm text-on-surface-variant mb-4">
        The reason is recorded in the control audit.
      </p>
      <div className="flex justify-end gap-2">
        <button type="button" className={BUTTON} onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          className={BUTTON}
          disabled={pending}
          onClick={() => onSubmit({ immediate, reason })}
        >
          Stop
        </button>
      </div>
    </Modal>
  );
}
