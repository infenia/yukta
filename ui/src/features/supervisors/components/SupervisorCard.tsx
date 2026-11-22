// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
import type { ComponentProps, ReactNode } from 'react';
import { cn } from '@/lib/utils';
import { Tip } from '@/components/ui/tooltip';
import type { SupervisorView } from '../types';

/** A small rounded label next to a name. */
export function Chip({
  children,
  tone = 'muted',
}: {
  children: ReactNode;
  tone?: 'muted' | 'attention' | 'success';
}) {
  return (
    <span
      className={cn(
        'text-label-sm rounded-full border px-2 py-px',
        tone === 'attention' &&
          'border-attention/30 bg-attention/10 text-attention',
        tone === 'success' && 'border-success/30 bg-success/10 text-success',
        tone === 'muted' &&
          'border-border bg-background/60 text-on-surface-variant'
      )}
    >
      {children}
    </span>
  );
}

/** The owner as a link to its page, or plain words for Direct control. */
export const OwnerLink = ({ owner }: { owner: string }) =>
  owner === 'default' ? (
    'Direct control'
  ) : (
    <Link
      to={`/supervisors?supervisor=${encodeURIComponent(owner)}`}
      className="text-primary hover:underline"
    >
      {owner}
    </Link>
  );

/**
 * The card background of a supervisor view: a primary-tinted gradient with a soft glow, or a plain
 * muted card for Direct control. The supervisor card and the supervisor page's header share it.
 */
export function SupervisorShell({
  direct,
  className,
  children,
  ...rest
}: {
  direct: boolean;
  className?: string;
  children: ReactNode;
} & Omit<ComponentProps<'section'>, 'className' | 'children'>) {
  return (
    <section
      {...rest}
      className={cn(
        'relative overflow-hidden rounded-xl border p-3',
        direct
          ? 'border-border bg-muted/30'
          : 'border-primary/25 from-primary/15 via-primary/5 bg-linear-to-br to-transparent',
        className
      )}
    >
      {!direct && (
        <span
          aria-hidden="true"
          className="bg-primary/20 pointer-events-none absolute -top-12 -right-12 size-36 rounded-full blur-3xl"
        />
      )}
      {children}
    </section>
  );
}

/**
 * Who controls something, as one card: the owner and its type, what it is for, then a line for
 * what it controls (`scope`) and who it reports to. Direct control is a plainer, muted card.
 */
export function SupervisorCard({
  owner,
  supervisor,
  typeName,
  label,
  scope,
  scopeIcon,
  children,
  className,
}: {
  owner: string;
  supervisor: SupervisorView | undefined;
  typeName: string | undefined;
  /** The overline above the name, for example "Supervised by". */
  label: string;
  /** What it controls; the card names nothing itself. */
  scope: ReactNode;
  scopeIcon: string;
  /** Under the scope line, for example a list of the nodes. */
  children?: ReactNode;
  className?: string;
}) {
  const direct = owner === 'default';
  const parents = supervisor?.parents ?? [];
  return (
    <SupervisorShell
      direct={direct}
      aria-label="Supervisor"
      className={className}
    >
      <div className="relative flex items-center gap-3">
        <span
          aria-hidden="true"
          className={cn(
            'flex size-10 shrink-0 items-center justify-center rounded-lg ring-1',
            direct
              ? 'bg-muted text-on-surface-variant ring-border'
              : 'bg-primary/15 text-primary ring-primary/30 shadow-card'
          )}
        >
          <span className="material-symbols-outlined text-[22px]!">
            {direct ? 'person' : 'shield_person'}
          </span>
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-overline text-on-surface-variant">{label}</p>
          <h3 className="text-body-md text-on-surface font-semibold break-all">
            <OwnerLink owner={owner} />
          </h3>
          {(typeName || supervisor?.dryRun || supervisor?.system) && (
            <div className="mt-1 flex flex-wrap items-center gap-1.5">
              {typeName && <Chip>{typeName}</Chip>}
              {supervisor?.dryRun && (
                <Tip label="Records the commands it would send and applies none">
                  <span>
                    <Chip tone="attention">Dry run</Chip>
                  </span>
                </Tip>
              )}
              {supervisor?.system && <Chip>Built in</Chip>}
            </div>
          )}
        </div>
      </div>
      {supervisor?.description && (
        <p className="text-body-sm text-on-surface-variant relative mt-2">
          {supervisor.description}
        </p>
      )}
      <div className="border-border/60 relative mt-2.5 flex flex-col gap-1 border-t pt-2.5">
        <div className="text-body-sm text-on-surface flex items-center gap-2">
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-primary text-lg"
          >
            {scopeIcon}
          </span>
          {scope}
        </div>
        {parents.length > 0 && (
          <p className="text-body-sm text-on-surface-variant flex items-center gap-2">
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-lg"
            >
              arrow_forward
            </span>
            <span>
              Reports to{' '}
              {parents.map((parent, index) => (
                <span key={parent}>
                  {index > 0 && ', '}
                  <OwnerLink owner={parent} />
                </span>
              ))}
            </span>
          </p>
        )}
        {children}
      </div>
    </SupervisorShell>
  );
}
