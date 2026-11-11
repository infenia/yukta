// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import BreadcrumbNav from '@/components/layout/BreadcrumbNav';
import type { ReactNode } from 'react';
import { cn } from '@/lib/utils';
import {
  copyIcon,
  copyTone,
  useCopyCommand,
} from '@/features/workflow/hooks/useCopyCommand';
import { Badge } from '@/components/ui/badge';
import type { SessionDetails } from '../types/session';

interface SessionInfoPanelProps {
  session: SessionDetails;
  actions?: ReactNode;
  /** How many workflows the session has, and their nodes in all. */
  workflows?: { count: number; nodes: number };
}

function getInitials(initiator: string): string {
  const parts = initiator.trim().split(/\s+/);
  if (parts.length >= 2) {
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }
  return initiator.substring(0, 2).toUpperCase();
}

export function SessionInfoPanel({
  session,
  actions,
  workflows,
}: SessionInfoPanelProps) {
  const { status: copied, copy } = useCopyCommand();

  const initials = getInitials(session.initiator);

  const label = 'text-label-sm text-on-surface-variant';

  return (
    <header className="border-outline-variant relative isolate border-b px-4 py-6 sm:px-6">
      {/* The docs hero backdrop, as on the Sessions list: behind the header only */}
      <div
        aria-hidden="true"
        data-testid="hero-backdrop"
        className="hero-backdrop pointer-events-none absolute inset-0 -z-10"
      />
      <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
        <div>
          <BreadcrumbNav className="mb-2" />
          <h1 className="text-headline-lg text-on-surface">{session.name}</h1>
          <p className="text-code-sm text-on-surface-variant mt-1">
            Session ID: {session.sessionId}
          </p>
        </div>
        {actions}
      </div>

      <div className="mb-6 space-y-1">
        <h3 className={label}>Description</h3>
        <p className="text-body-md text-on-surface">{session.description}</p>
      </div>

      <div className="grid grid-cols-1 gap-6 md:grid-cols-2 xl:grid-cols-[1fr_1fr_1fr_1.5fr]">
        <div className="space-y-1">
          <h3 className={label}>Tags</h3>
          <div className="flex flex-wrap gap-2">
            {session.tags.map((tag) => (
              <Badge
                key={tag}
                variant="outline"
                className="bg-secondary-container text-label-sm text-on-secondary-container"
              >
                {tag}
              </Badge>
            ))}
          </div>
        </div>

        <div className="space-y-1">
          <h3 className={label}>Creator</h3>
          <div className="flex items-center gap-2">
            <div className="bg-accent text-label-sm text-accent-foreground flex size-6 items-center justify-center rounded-full">
              {initials}
            </div>
            <p className="text-body-md text-on-surface">{session.initiator}</p>
          </div>
        </div>

        {workflows && (
          <div className="space-y-1">
            <h3 className={label}>Workflows</h3>
            <p className="text-body-md text-on-surface">
              {workflows.count} · {workflows.nodes} nodes in all
            </p>
          </div>
        )}

        <div className="min-w-0 space-y-1">
          <h3 className={label}>Working Directory</h3>
          <button
            onClick={() => copy(session.projectPath)}
            aria-label={
              copied === 'copied'
                ? 'Working directory copied'
                : 'Copy working directory'
            }
            className="group border-border bg-card shadow-card transition-standard hover:border-ring flex w-full items-center justify-between rounded-lg border p-2"
          >
            <code className="text-code-sm text-on-surface truncate pr-4">
              {session.projectPath}
            </code>
            <span
              className={cn(
                'material-symbols-outlined flex size-6 shrink-0 items-center justify-center rounded-md text-base transition-colors',
                copied ? copyTone(copied) : 'group-hover:text-primary'
              )}
              aria-hidden="true"
            >
              {copyIcon(copied)}
            </span>
          </button>
        </div>
      </div>
    </header>
  );
}
