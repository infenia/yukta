// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, type ReactNode } from 'react';

/** Minimal modal: backdrop, Escape-to-close and an accessible dialog role. */
export function Modal({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => event.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className="bg-foreground/40 fixed inset-0 z-60 flex items-center justify-center p-4 backdrop-blur-sm">
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="border-border bg-card shadow-raised w-full max-w-lg rounded-2xl border p-6"
      >
        <h2 className="text-headline-md text-on-surface mb-4">{title}</h2>
        {children}
      </div>
    </div>
  );
}
