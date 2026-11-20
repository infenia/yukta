// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router';
import { Tip } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { useSettings } from '../hooks/useSettings';
import { OPEN_ASSISTANT_EVENT } from '../lib/open';

/** The run page docks its logs at the bottom. */
const RUN_PAGE = /^\/sessions\/[^/]+\/workflow\/[^/]+\/?$/;

// the card is a separate chunk: nothing of it loads until the first open
const AssistantPanel = lazy(() => import('./AssistantPanel'));

/**
 * The floating assistant: a round button at the bottom right of every page and, once opened, the
 * card. Ctrl or ⌘ + J toggles it. The card stays mounted after the first open, so the
 * conversation survives route changes.
 */
export function AssistantLauncher() {
  const { settings, key, update, save } = useSettings();
  const [open, setOpen] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const button = useRef<HTMLButtonElement>(null);
  const { pathname } = useLocation();

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
    document.addEventListener('keydown', onKey);
    window.addEventListener(OPEN_ASSISTANT_EVENT, onOpen);
    return () => {
      document.removeEventListener('keydown', onKey);
      window.removeEventListener(OPEN_ASSISTANT_EVENT, onOpen);
    };
  }, [update]);

  const lifted = RUN_PAGE.test(pathname);
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
              'border-border bg-card text-primary shadow-raised transition-standard fixed right-3 z-60 flex size-12 items-center justify-center rounded-full border hover:-translate-y-0.5 sm:right-5',
              lifted ? 'bottom-20 sm:bottom-[17rem]' : 'bottom-20 sm:bottom-5',
              open && 'max-sm:hidden'
            )}
          >
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-2xl"
            >
              auto_awesome
            </span>
          </button>
        </Tip>
      )}
      {loaded && (
        <Suspense fallback={null}>
          <AssistantPanel
            open={open}
            lifted={lifted}
            settings={settings}
            apiKey={key}
            onSave={save}
            onHide={() => {
              update({ hidden: true });
              close();
            }}
            onClose={close}
          />
        </Suspense>
      )}
    </>
  );
}
