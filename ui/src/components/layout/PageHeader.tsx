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
  backdrop = false,
}: {
  title: ReactNode;
  /** A small pill above the title, like the docs' eyebrow. */
  eyebrow?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  /**
   * The docs hero backdrop behind the header, as on the Sessions page. It fills the width of the
   * page content area (the positioned <main>) from the top, so use it only where the header is the
   * first thing on the page; never behind tables or dense content.
   */
  backdrop?: boolean;
}) {
  return (
    <div className="mb-4 flex flex-wrap items-end justify-between gap-4">
      {backdrop && (
        <div
          aria-hidden="true"
          data-testid="hero-backdrop"
          className="pointer-events-none absolute inset-x-0 top-0 -z-10 h-56 hero-backdrop"
        />
      )}
      <div className="min-w-0">
        <BreadcrumbNav className="mb-2" />
        {eyebrow && (
          <span
            data-testid="page-eyebrow"
            className="mb-3 inline-flex items-center gap-2 rounded-full border border-border bg-card px-3 py-1 text-label-sm text-on-surface-variant"
          >
            {eyebrow}
          </span>
        )}
        <h1 className="text-headline-lg text-on-surface">{title}</h1>
        {description && (
          <p className="mt-1 text-body-md text-on-surface-variant">
            {description}
          </p>
        )}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}
