// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { textareaFieldClassName } from '@/components/ui/native-select';
import { Callout } from '@/components/common/Callout';
import { Modal } from '@/components/common/Modal';
import { ApiError } from '@/lib/apiClient';
import { parseCommand, sendCommand } from '../api/controlBus';

/** A JSON editor that sends one command to a control-bus node. */
export function SendCommandDialog({
  sessionId,
  executionId,
  workflowId,
  nodeId,
  onClose,
  onSent,
}: {
  sessionId: string;
  executionId: string;
  workflowId: string;
  nodeId: string;
  onClose: () => void;
  onSent: (response: unknown) => void;
}) {
  const [text, setText] = useState('{\n  "action": ""\n}');
  const [invalid, setInvalid] = useState<string | null>(null);
  const send = useMutation({
    mutationFn: (command: Record<string, unknown>) =>
      sendCommand(sessionId, executionId, workflowId, nodeId, command),
    onSuccess: (response) => onSent(response.payload),
  });

  const submit = () => {
    const parsed = parseCommand(text);
    if ('error' in parsed) {
      setInvalid(parsed.error);
      return;
    }
    setInvalid(null);
    send.mutate(parsed.command);
  };
  const failure =
    send.error instanceof ApiError && send.error.status === 403
      ? 'Sending commands needs an ADMIN key.'
      : send.error?.message;

  return (
    <Modal title={`Send a command to ${nodeId}`} onClose={onClose}>
      <div className="flex flex-col gap-3">
        <Callout kind="warning" compact>
          Commands go straight to the node; this is recorded in the audit log.
        </Callout>
        <label className="text-label-md text-on-surface flex flex-col gap-1">
          Command (JSON object)
          <textarea
            value={text}
            onChange={(event) => setText(event.target.value)}
            rows={6}
            spellCheck={false}
            aria-invalid={invalid !== null}
            className={`${textareaFieldClassName} text-code-sm`}
          />
        </label>
        {(invalid ?? failure) && (
          <Callout kind="danger" compact>
            {invalid ?? failure}
          </Callout>
        )}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="button" onClick={submit} disabled={send.isPending}>
            Send
          </Button>
        </div>
      </div>
    </Modal>
  );
}
