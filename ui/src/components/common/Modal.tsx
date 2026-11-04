// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

/**
 * Minimal modal: backdrop, Escape-to-close and an accessible dialog role. Portaled to body so
 * that a stacking context in the caller (a header with isolate) cannot trap its z-index.
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
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => event.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return createPortal(
    <div className="animate-in fade-in-0 bg-overlay/50 fixed inset-0 z-60 flex items-center justify-center p-4 backdrop-blur-sm">
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={`animate-in zoom-in-95 fade-in-0 border-border bg-card shadow-raised max-h-[90vh] w-full overflow-auto rounded-2xl border p-6 duration-150 ${wide ? 'max-w-3xl' : 'max-w-lg'}`}
      >
        <h2 className="text-headline-md text-on-surface mb-4">{title}</h2>
        {children}
      </div>
    </div>,
    document.body
  );
}
