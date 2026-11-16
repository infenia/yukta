// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState, type KeyboardEvent } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import type { Conversation } from '@/types/conversations';
import {
  endConversation,
  interruptConversation,
  sendConversationMessage,
} from '../../api/conversations';
import { isForbidden, steerErrorMessage } from '../../lib/steerErrors';

/** Why the composer cannot send, or null when it can. */
function reasonDisabled(c: Conversation, forbidden: boolean): string | null {
  if (c.state === 'ENDED') return 'This conversation has ended.';
  if (!c.capabilities.includes('message'))
    return 'This agent takes no messages.';
  if (forbidden) return 'You may not steer this agent.';
  return null;
}

/**
 * Sends to the selected agent. Enter sends and Shift+Enter breaks the line. While the agent works
 * the button offers to queue the message, or, if the agent can be stopped, to interrupt and send.
 */
export function AgentComposer({
  conversation,
}: {
  conversation: Conversation;
}) {
  const [text, setText] = useState('');
  const [forbidden, setForbidden] = useState(false);
  const can = (capability: string) =>
    conversation.capabilities.includes(capability);
  const disabled = reasonDisabled(conversation, forbidden);

  const fail = (error: unknown) => {
    if (isForbidden(error)) setForbidden(true);
  };
  const send = useMutation({
    mutationFn: (interrupt: boolean) =>
      sendConversationMessage(conversation.id, text.trim(), interrupt),
    onSuccess: () => setText(''),
    onError: fail,
  });
  const interrupt = useMutation({
    mutationFn: () => interruptConversation(conversation.id),
    onError: fail,
  });
  const end = useMutation({
    mutationFn: () => endConversation(conversation.id),
    onError: fail,
  });

  const working = conversation.state === 'RUNNING' && !can('steer');
  const empty = text.trim() === '';
  const error: unknown = send.error ?? interrupt.error ?? end.error;
  const submit = (withInterrupt: boolean) => {
    if (!disabled && !empty && !send.isPending) send.mutate(withInterrupt);
  };
  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      submit(false);
    }
  };

  return (
    <form
      aria-label="Message to the agent"
      className="border-border flex flex-col gap-1.5 border-t px-4 py-2"
      onSubmit={(event) => {
        event.preventDefault();
        submit(false);
      }}
    >
      <label htmlFor={`composer-${conversation.id}`} className="sr-only">
        Message to {conversation.nodeId}
      </label>
      <textarea
        id={`composer-${conversation.id}`}
        rows={2}
        value={text}
        disabled={disabled !== null}
        onChange={(event) => setText(event.target.value)}
        onKeyDown={onKeyDown}
        placeholder={
          disabled ?? 'Message the agent (Enter sends, Shift+Enter adds a line)'
        }
        className="border-border bg-surface text-body-sm w-full resize-none rounded-md border px-2 py-1.5 disabled:opacity-60"
      />
      <div className="flex flex-wrap items-center gap-2">
        <Button
          type="submit"
          size="sm"
          disabled={disabled !== null || empty || send.isPending}
        >
          {working ? 'Queue' : 'Send'}
        </Button>
        {working && can('interrupt') && (
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={disabled !== null || empty || send.isPending}
            onClick={() => submit(true)}
          >
            Interrupt and send
          </Button>
        )}
        {can('interrupt') && conversation.state !== 'ENDED' && (
          <Button
            type="button"
            size="sm"
            variant="ghost"
            disabled={interrupt.isPending}
            onClick={() => interrupt.mutate()}
          >
            Interrupt
          </Button>
        )}
        {conversation.state !== 'ENDED' && (
          <Button
            type="button"
            size="sm"
            variant="ghost"
            disabled={end.isPending}
            onClick={() => end.mutate()}
          >
            End
          </Button>
        )}
        {error ? (
          <span role="alert" className="text-body-sm text-destructive">
            {steerErrorMessage(error)}
          </span>
        ) : null}
      </div>
    </form>
  );
}
