// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, type ReactNode } from 'react';

/** Minimal modal: backdrop, Escape-to-close and an accessible dialog role. */
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

  return (
    <div className="fixed inset-0 z-60 flex items-center justify-center bg-foreground/40 p-4 backdrop-blur-sm">
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={`max-h-[90vh] w-full overflow-auto rounded-2xl border border-border bg-card p-6 shadow-raised ${wide ? 'max-w-3xl' : 'max-w-lg'}`}
      >
        <h2 className="mb-4 text-headline-md text-on-surface">{title}</h2>
        {children}
      </div>
    </div>
  );
}
