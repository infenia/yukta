// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { Link } from 'react-router';

/** A headline number that links to where it comes from. */
export function Tile({
  label,
  value,
  to,
  hint,
}: {
  label: string;
  value: ReactNode;
  to: string;
  hint?: string;
}) {
  const body = (
    <>
      <span className="text-label-md text-on-surface-variant">{label}</span>
      <span className="text-headline-md text-on-surface">{value}</span>
      {hint && (
        <span className="text-body-sm text-on-surface-variant">{hint}</span>
      )}
    </>
  );
  const className =
    'border-border bg-card flex flex-col gap-1 rounded-xl border p-4 no-underline';
  // A tile that points into this page is a plain anchor, so the browser scrolls to it
  return to.startsWith('#') ? (
    <a href={to} className={className}>
      {body}
    </a>
  ) : (
    <Link to={to} className={className}>
      {body}
    </Link>
  );
}

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
