// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { buttonVariants } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { InlineError } from '@/components/common/InlineError';
import { Modal } from '@/components/common/Modal';

const BUTTON = buttonVariants({ variant: 'outline' });

/** Asks for the field and the value that make a node pause (both are required). */
export function BreakpointDialog({
  nodeId,
  pending,
  error,
  onSubmit,
  onClose,
}: {
  nodeId: string;
  pending: boolean;
  error: string | null;
  onSubmit: (field: string, equals: string) => void;
  onClose: () => void;
}) {
  const [field, setField] = useState('');
  const [equals, setEquals] = useState('');
  const [missing, setMissing] = useState(false);

  const submit = () => {
    if (!field.trim() || !equals.trim()) {
      setMissing(true);
      return;
    }
    setMissing(false);
    onSubmit(field.trim(), equals.trim());
  };

  return (
    <Modal title={`Set a breakpoint on ${nodeId}`} onClose={onClose}>
      <p className="text-body-sm text-on-surface-variant mb-3">
        The node pauses when the field of its message equals the value. Resume
        or Step it from its menu.
      </p>
      <label
        className="text-body-md text-on-surface mb-1 block"
        htmlFor="bp-field"
      >
        Field
      </label>
      <Input
        id="bp-field"
        value={field}
        placeholder="payload.status"
        onChange={(event) => setField(event.target.value)}
        className="mb-3"
      />
      <label
        className="text-body-md text-on-surface mb-1 block"
        htmlFor="bp-equals"
      >
        Equals
      </label>
      <Input
        id="bp-equals"
        value={equals}
        placeholder="error"
        onChange={(event) => setEquals(event.target.value)}
        className="mb-3"
      />
      <InlineError
        message={missing ? 'Both the field and the value are required' : error}
      />
      <div className="mt-4 flex justify-end gap-2">
        <button type="button" className={BUTTON} onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          className={BUTTON}
          disabled={pending}
          onClick={submit}
        >
          Set
        </button>
      </div>
    </Modal>
  );
}
