// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';

/** A titled card; its id comes from the title so a tile can link to it. */
export function Panel({
  title,
  action,
  children,
}: {
  title: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  const id = `ov-${title.replace(/\W+/g, '-').toLowerCase()}`;
  return (
    <section
      id={`${id}-panel`}
      aria-labelledby={id}
      className="flex min-w-0 scroll-mt-20 flex-col gap-2 rounded-xl border border-border bg-card p-4"
    >
      <div className="flex items-center justify-between gap-2">
        <h2 id={id} className="text-label-md text-on-surface">
          {title}
        </h2>
        {action}
      </div>
      {children}
    </section>
  );
}
