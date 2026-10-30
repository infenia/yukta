// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import BreadcrumbNav from './BreadcrumbNav';

/** The single h1 per page, with optional eyebrow, description and actions. */
export function PageHeader({
  title,
  eyebrow,
  description,
  actions,
}: {
  title: ReactNode;
  /** A small pill above the title, like the docs' eyebrow. */
  eyebrow?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="mb-4 flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0">
        <BreadcrumbNav className="mb-2" />
        {eyebrow && (
          <span
            data-testid="page-eyebrow"
            className="border-border bg-card text-label-sm text-on-surface-variant mb-3 inline-flex items-center gap-2 rounded-full border px-3 py-1"
          >
            {eyebrow}
          </span>
        )}
        <h1 className="text-headline-lg text-on-surface">{title}</h1>
        {description && (
          <p className="text-body-md text-on-surface-variant mt-1">
            {description}
          </p>
        )}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}
