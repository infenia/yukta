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
      <div className="mx-auto flex w-full max-w-3xl px-4 pt-1 pb-3">
        <div className="border-border bg-surface flex min-w-0 flex-1 items-center gap-2 rounded-2xl border px-3 py-2 opacity-60">
          <input
            disabled
            aria-label="Message to the agent"
            placeholder="Select an agent in the list to send it a message."
            className="text-body-md min-w-0 flex-1 bg-transparent px-1 py-1 outline-none"
          />
          <Button
            type="button"
            size="icon"
            className="size-8 rounded-full"
            aria-label="Send"
            disabled
          >
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-lg"
            >
              send
            </span>
          </Button>
        </div>
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
      className="mx-auto flex w-full max-w-3xl flex-col gap-1 px-4 pt-1 pb-3"
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
      <div className="border-border bg-surface focus-within:border-ring shadow-card flex flex-col gap-1 rounded-2xl border px-3 py-2">
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
          className="text-body-md max-h-40 min-w-0 resize-none bg-transparent px-1 py-1 outline-none disabled:opacity-60"
        />
        <div className="flex items-center justify-end gap-1">
          {conversation.state !== 'ENDED' && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
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
              variant="ghost"
              size="sm"
              disabled={interrupt.isPending}
              onClick={() => interrupt.mutate()}
            >
              Interrupt
            </Button>
          )}
          {working && can('interrupt') && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={disabled !== null || empty || send.isPending}
              onClick={() => submit(true)}
            >
              Interrupt and send
            </Button>
          )}
          <Button
            type="submit"
            size="icon"
            className="size-8 rounded-full"
            aria-label={working ? 'Queue' : 'Send'}
            disabled={disabled !== null || empty || send.isPending}
          >
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-lg"
            >
              send
            </span>
          </Button>
        </div>
      </div>
      {error ? (
        <span role="alert" className="text-body-sm text-destructive">
          {steerErrorMessage(error)}
        </span>
      ) : null}
    </form>
  );
}
