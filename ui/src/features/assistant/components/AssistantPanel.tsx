// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMemo, useState, type KeyboardEvent } from 'react';
import { useLocation } from 'react-router';
import { IconButton } from '@/components/common/IconButton';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';
import { useNano } from '../hooks/useNano';
import { clearChat } from '../lib/history';
import { buildModel } from '../lib/models';
import { pageContext } from '../lib/pageContext';
import type { Settings } from '../lib/settings';
import { Chat } from './Chat';
import { SetupView } from './SetupView';

/** The floating card: header, the conversation, and the setup when no model is connected. */
export default function AssistantPanel({
  open,
  lifted,
  settings,
  apiKey,
  onSave,
  onHide,
  onClose,
}: {
  open: boolean;
  /** The run page docks its logs at the bottom, so the card sits higher there. */
  lifted: boolean;
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
  const [chat, setChat] = useState(0);
  const [wide, setWide] = useState(false);

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
        'border-border bg-card shadow-raised fixed z-60 flex flex-col overflow-hidden rounded-xl border',
        'motion-safe:animate-in motion-safe:fade-in-0 motion-safe:zoom-in-95',
        'max-sm:inset-x-2 max-sm:top-16 max-sm:bottom-20',
        wide ? 'sm:right-5 sm:w-xl' : 'sm:right-5 sm:w-md',
        lifted
          ? 'sm:bottom-84 sm:h-[max(16rem,min(40rem,calc(100dvh-26rem)))]'
          : 'sm:bottom-20 sm:h-[min(40rem,calc(100dvh-7rem))]',
        // expanded: docked full height beside the page (the run page keeps its logs clear)
        wide && !lifted && 'sm:top-20 sm:h-auto'
      )}
    >
      <header className="border-border flex items-center gap-2 border-b px-4 py-3">
        <span
          aria-hidden="true"
          className="material-symbols-outlined text-primary"
        >
          auto_awesome
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
        <IconButton
          icon="add"
          label="New chat"
          onClick={() => {
            clearChat();
            setChat(chat + 1);
          }}
        />
        <IconButton
          icon={wide ? 'close_fullscreen' : 'open_in_full'}
          label={wide ? 'Shrink' : 'Expand'}
          className="max-sm:hidden"
          onClick={() => setWide(!wide)}
        />
        <IconButton
          icon="settings"
          label="Settings"
          onClick={() => setConfiguring(!configuring)}
        />
        <IconButton icon="close" label="Close" onClick={onClose} />
      </header>
      {model && (
        <div hidden={configuring} className="flex min-h-0 flex-1 flex-col">
          <Chat key={chat} model={model} context={context} open={open} />
        </div>
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
