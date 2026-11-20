// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';

/** A side-panel section the reader can fold away; native `<details>` keeps it keyboard and AT friendly. */
export const PanelSection = ({
  title,
  actions,
  children,
}: {
  title: ReactNode;
  /** Controls for the section, kept in its header beside the chevron. */
  actions?: ReactNode;
  children: ReactNode;
}) => (
  <details
    open
    className="group border-border border-b pb-3 last:border-b-0 last:pb-0"
  >
    <summary className="hover:text-on-surface text-on-surface-variant transition-standard flex cursor-pointer list-none items-center justify-between rounded-md py-1 select-none [&::-webkit-details-marker]:hidden">
      <h3 className="text-overline flex items-center gap-2">{title}</h3>
      <span className="flex items-center gap-2">
        {actions}
        <span
          aria-hidden="true"
          className="material-symbols-outlined transition-standard text-lg group-open:rotate-180"
        >
          expand_more
        </span>
      </span>
    </summary>
    <div className="flex flex-col gap-1.5 pt-1.5">{children}</div>
  </details>
);
