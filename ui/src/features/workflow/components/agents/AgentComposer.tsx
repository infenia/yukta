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
  /** The agent to talk to; none while the timeline shows all of them. */
  conversation: Conversation | undefined;
}) {
  if (!conversation) {
    return (
      <div className="border-border flex items-center gap-2 border-t px-4 py-2">
        <input
          disabled
          aria-label="Message to the agent"
          placeholder="Select an agent in the list to send it a message."
          className="border-border bg-surface-variant text-body-sm min-w-0 flex-1 rounded-lg border px-3 py-2"
        />
        <Button type="button" disabled>
          Send
        </Button>
      </div>
    );
  }
  return <Composer conversation={conversation} />;
}

function Composer({ conversation }: { conversation: Conversation }) {
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
      className="border-border flex flex-col gap-1 border-t px-4 py-2"
      onSubmit={(event) => {
        event.preventDefault();
        submit(false);
      }}
    >
      {working && disabled === null && (
        <p className="text-body-sm text-muted-foreground">
          This agent cannot take a message during a turn. The queued message is
          sent when the turn ends.
        </p>
      )}
      <div className="flex items-center gap-2">
        <label htmlFor={`composer-${conversation.id}`} className="sr-only">
          Message to {conversation.nodeId}
        </label>
        <textarea
          id={`composer-${conversation.id}`}
          rows={1}
          value={text}
          disabled={disabled !== null}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={onKeyDown}
          placeholder={
            disabled ??
            'Message the agent. Enter sends, Shift+Enter adds a line.'
          }
          className="border-border bg-surface text-body-sm min-w-0 flex-1 resize-none rounded-lg border px-3 py-2 disabled:opacity-60"
        />
        {conversation.state !== 'ENDED' && (
          <Button
            type="button"
            variant="outline"
            className="text-destructive"
            disabled={end.isPending}
            onClick={() => end.mutate()}
          >
            End
          </Button>
        )}
        {can('interrupt') && conversation.state !== 'ENDED' && (
          <Button
            type="button"
            variant="outline"
            disabled={interrupt.isPending}
            onClick={() => interrupt.mutate()}
          >
            Interrupt
          </Button>
        )}
        {working && can('interrupt') && (
          <Button
            type="button"
            variant="outline"
            disabled={disabled !== null || empty || send.isPending}
            onClick={() => submit(true)}
          >
            Interrupt and send
          </Button>
        )}
        <Button
          type="submit"
          disabled={disabled !== null || empty || send.isPending}
        >
          {working ? 'Queue' : 'Send'}
        </Button>
      </div>
      {error ? (
        <span role="alert" className="text-body-sm text-destructive">
          {steerErrorMessage(error)}
        </span>
      ) : null}
    </form>
  );
}
