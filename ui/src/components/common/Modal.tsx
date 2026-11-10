// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

/**
 * Minimal modal: backdrop, a close button, Escape and a click outside it to close, and an
 * accessible dialog role. Portaled to body so that a stacking context in the caller (a header
 * with isolate) cannot trap its z-index.
 */
export function Modal({
  title,
  onClose,
  children,
  wide = false,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  /** For content that needs room, such as a canvas. */
  wide?: boolean;
}) {
  // A press that starts inside the dialog (say, a text selection dragged out of a field) must not
  // close it, so the backdrop only counts when the press began on the backdrop too
  const pressedBackdrop = useRef(false);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => event.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return createPortal(
    <div
      className="animate-in bg-overlay/50 fade-in-0 fixed inset-0 z-60 flex items-center justify-center p-4 backdrop-blur-sm"
      onMouseDown={(event) => {
        pressedBackdrop.current = event.target === event.currentTarget;
      }}
      onClick={(event) => {
        if (pressedBackdrop.current && event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={`animate-in border-border bg-card shadow-raised fade-in-0 zoom-in-95 max-h-[90vh] w-full overflow-auto rounded-2xl border p-6 duration-150 ${wide ? 'max-w-3xl' : 'max-w-lg'}`}
      >
        <div className="mb-4 flex items-start justify-between gap-4">
          <h2 className="text-headline-md text-on-surface">{title}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close dialog"
            className="text-on-surface-variant hover:bg-muted hover:text-on-surface focus-visible:ring-ring -mt-1 -mr-1 flex size-8 shrink-0 items-center justify-center rounded-lg focus-visible:ring-2 focus-visible:outline-none"
          >
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-xl"
            >
              close
            </span>
          </button>
        </div>
        {children}
      </div>
    </div>,
    document.body
  );
}
