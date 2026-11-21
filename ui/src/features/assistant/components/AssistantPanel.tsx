// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useMemo, useState, type KeyboardEvent } from 'react';
import { useLocation } from 'react-router';
import { Tip } from '@/components/ui/tooltip';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';
import { useNano } from '../hooks/useNano';
import { archiveChat, clearChat, restoreChat } from '../lib/history';
import { buildModel } from '../lib/models';
import { pageContext } from '../lib/pageContext';
import type { Settings } from '../lib/settings';
import { Chat } from './Chat';
import { HistoryView } from './HistoryView';
import { SetupView } from './SetupView';

/** The assistant card: header, the conversation, and the setup when no model is connected. */
export default function AssistantPanel({
  open,
  settings,
  apiKey,
  onSave,
  onHide,
  onClose,
}: {
  open: boolean;
  settings: Settings;
  apiKey: string | null;
  onSave: (settings: Settings, key: string) => void;
  onHide: () => void;
  onClose: () => void;
}) {
  const { pathname, search } = useLocation();
  const context = useMemo(
    () => pageContext(pathname, search),
    [pathname, search]
  );
  const nano = useNano();
  const model = useMemo(
    () => buildModel(settings, apiKey, nano.state),
    [settings, apiKey, nano.state]
  );
  const [configuring, setConfiguring] = useState(false);
  const [browsing, setBrowsing] = useState(false);
  const [chat, setChat] = useState(0);

  // on wide screens the card docks to the right edge; the page makes room for it (index.css)
  useEffect(() => {
    const root = document.documentElement;
    if (open) root.dataset.assistantDock = '';
    else delete root.dataset.assistantDock;
    return () => {
      delete root.dataset.assistantDock;
    };
  }, [open]);

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Escape') onClose();
  };

  return (
    <section
      role="dialog"
      aria-label="Yukta assistant"
      hidden={!open}
      onKeyDown={onKeyDown}
      className={cn(
        'assistant-surface border-border shadow-raised fixed z-60 flex flex-col overflow-hidden rounded-xl border',
        'motion-safe:animate-in motion-safe:fade-in-0 motion-safe:zoom-in-95',
        // a bottom sheet on phones
        'max-sm:inset-x-0 max-sm:top-24 max-sm:bottom-0 max-sm:rounded-b-none',
        // a popover under the header's assistant button
        'sm:top-16 sm:right-5 sm:h-[min(40rem,calc(100dvh-5rem))] sm:w-md',
        // docked beside the page on wide screens, so it never covers the content
        'lg:top-header lg:right-0 lg:bottom-0 lg:h-auto lg:w-md lg:rounded-none lg:border-y-0 lg:border-r-0 lg:shadow-none'
      )}
    >
      <div aria-hidden="true" className="assistant-gradient h-0.5 shrink-0" />
      <header className="border-primary/15 flex items-center gap-2.5 border-b px-4 py-3">
        <span
          aria-hidden="true"
          className="assistant-gradient flex size-7 shrink-0 items-center justify-center rounded-lg"
        >
          <span className="material-symbols-outlined text-base">
            auto_awesome
          </span>
        </span>
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="flex items-center gap-2">
            <h2 className="text-label-md whitespace-nowrap">Yukta Assistant</h2>
            <Badge variant="outline">Experimental</Badge>
          </div>
          <button
            type="button"
            aria-label="Change model"
            onClick={() => setConfiguring(!configuring)}
            className="text-label-sm text-on-surface-variant transition-standard hover:text-on-surface truncate text-left"
          >
            {model ? model.label : 'No model connected'}
          </button>
        </div>
        <HeaderButton
          icon="add"
          label="New chat"
          onClick={() => {
            archiveChat();
            clearChat();
            setBrowsing(false);
            setConfiguring(false);
            setChat(chat + 1);
          }}
        />
        <HeaderButton
          icon="history"
          label="History"
          active={browsing}
          onClick={() => {
            setConfiguring(false);
            setBrowsing(!browsing);
          }}
        />
        <HeaderButton
          icon="settings"
          label="Settings"
          active={configuring}
          onClick={() => {
            setBrowsing(false);
            setConfiguring(!configuring);
          }}
        />
        <HeaderButton icon="close" label="Close" onClick={onClose} />
      </header>
      {model && (
        <div
          hidden={configuring || browsing}
          className="flex min-h-0 flex-1 flex-col"
        >
          <Chat key={chat} model={model} context={context} open={open} />
        </div>
      )}
      {browsing && !configuring && (
        <HistoryView
          onOpen={(id) => {
            restoreChat(id);
            setBrowsing(false);
            setChat(chat + 1);
          }}
        />
      )}
      {(!model || configuring) && (
        <SetupView
          settings={settings}
          apiKey={apiKey}
          nano={nano}
          onSave={(next, key) => {
            onSave(next, key);
            setConfiguring(false);
          }}
          onHide={onHide}
        />
      )}
    </section>
  );
}

/** A compact icon button for the header: no outline, the glyph a size down. */
function HeaderButton({
  icon,
  label,
  active = false,
  onClick,
}: {
  icon: string;
  label: string;
  active?: boolean;
  onClick: () => void;
}) {
  return (
    <Tip label={label}>
      <button
        type="button"
        aria-label={label}
        aria-pressed={active || undefined}
        onClick={onClick}
        className={cn(
          'text-on-surface-variant hover:bg-primary/10 hover:text-on-surface transition-standard flex size-7 items-center justify-center rounded-full',
          active && 'bg-primary/15 text-on-surface'
        )}
      >
        <span
          aria-hidden="true"
          className="material-symbols-outlined text-base"
        >
          {icon}
        </span>
      </button>
    </Tip>
  );
}
