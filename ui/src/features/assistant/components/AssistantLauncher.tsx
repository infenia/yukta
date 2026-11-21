// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Tip } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { useSettings } from '../hooks/useSettings';
import { BUSY_ASSISTANT_EVENT, OPEN_ASSISTANT_EVENT } from '../lib/open';

// the card is a separate chunk: nothing of it loads until the first open
const AssistantPanel = lazy(() => import('./AssistantPanel'));

/**
 * The assistant: a button in the header of every page and, once opened, a card under it. Ctrl or ⌘ + J toggles it. The card stays mounted after the first open, so the
 * conversation survives route changes.
 */
export function AssistantLauncher() {
  const { settings, key, update, save } = useSettings();
  const [open, setOpen] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [working, setWorking] = useState(false);
  const button = useRef<HTMLButtonElement>(null);

  const show = () => {
    setLoaded(true);
    setOpen(true);
  };
  const close = () => {
    setOpen(false);
    button.current?.focus();
  };

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'j') {
        event.preventDefault();
        setLoaded(true);
        setOpen((current) => !current);
      }
    };
    const onOpen = () => {
      update({ hidden: false });
      setLoaded(true);
      setOpen(true);
    };
    const onBusy = (event: Event) =>
      setWorking((event as CustomEvent<boolean>).detail);
    document.addEventListener('keydown', onKey);
    window.addEventListener(OPEN_ASSISTANT_EVENT, onOpen);
    window.addEventListener(BUSY_ASSISTANT_EVENT, onBusy);
    return () => {
      document.removeEventListener('keydown', onKey);
      window.removeEventListener(OPEN_ASSISTANT_EVENT, onOpen);
      window.removeEventListener(BUSY_ASSISTANT_EVENT, onBusy);
    };
  }, [update]);

  return (
    <>
      {!settings.hidden && (
        <Tip label="Ask Yukta (Ctrl+J)">
          <button
            ref={button}
            type="button"
            aria-label="Yukta assistant"
            aria-expanded={open}
            onClick={open ? close : show}
            className={cn(
              'text-on-surface-variant hover:bg-muted hover:text-on-surface transition-standard mr-1 flex size-8 items-center justify-center rounded-full',
              open && 'bg-muted text-on-surface',
              working && 'ring-primary/60 ring-2 motion-safe:animate-pulse'
            )}
            data-working={working}
          >
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-lg"
            >
              auto_awesome
            </span>
          </button>
        </Tip>
      )}
      {loaded &&
        createPortal(
          <Suspense fallback={null}>
            <AssistantPanel
              open={open}
              settings={settings}
              apiKey={key}
              onSave={save}
              onHide={() => {
                update({ hidden: true });
                close();
              }}
              onClose={close}
            />
          </Suspense>,
          document.body
        )}
    </>
  );
}
