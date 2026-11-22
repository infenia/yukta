// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { deleteArchived, listArchive } from '../lib/history';

const when = (at: number) =>
  new Date(at).toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });

/** The earlier conversations kept in this browser: open one, or delete it. */
export function HistoryView({ onOpen }: { onOpen: (id: string) => void }) {
  const [chats, setChats] = useState(listArchive);

  if (chats.length === 0) {
    return (
      <div className="flex flex-1 flex-col justify-center gap-1 p-6 text-center">
        <p className="text-label-md">No earlier chats</p>
        <p className="text-label-sm text-on-surface-variant">
          A chat you leave with New chat is kept here, in this browser only.
        </p>
      </div>
    );
  }
  return (
    <ul className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto p-4">
      {chats.map((chat) => (
        <li
          key={chat.id}
          className="border-border hover:bg-primary/5 transition-standard flex items-center gap-1 rounded-xl border"
        >
          <button
            type="button"
            aria-label={`Open ${chat.title}`}
            onClick={() => onOpen(chat.id)}
            className="flex min-w-0 flex-1 flex-col px-3 py-2 text-left"
          >
            <span className="text-label-md truncate">{chat.title}</span>
            <span className="text-label-sm text-on-surface-variant">
              {when(chat.at)} · {chat.messages.length} messages
            </span>
          </button>
          <button
            type="button"
            aria-label={`Delete ${chat.title}`}
            onClick={() => {
              deleteArchived(chat.id);
              setChats(listArchive());
            }}
            className="text-on-surface-variant hover:text-on-surface mr-2 flex size-7 shrink-0 items-center justify-center rounded-full"
          >
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-base"
            >
              close
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}
