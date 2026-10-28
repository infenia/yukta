// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState, type ReactNode } from 'react';
import { Badge } from '@/components/ui/badge';
import type { SessionDetails } from '../types/session';

interface SessionInfoPanelProps {
  session: SessionDetails;
  actions?: ReactNode;
}

function getInitials(initiator: string): string {
  const parts = initiator.trim().split(/\s+/);
  if (parts.length >= 2) {
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }
  return initiator.substring(0, 2).toUpperCase();
}

export function SessionInfoPanel({ session, actions }: SessionInfoPanelProps) {
  const [copied, setCopied] = useState(false);

  const handleCopyPath = async () => {
    try {
      await navigator.clipboard.writeText(session.projectPath);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (error) {
      console.error('Failed to copy to clipboard:', error);
    }
  };

  const initials = getInitials(session.initiator);

  const label = 'text-label-sm text-on-surface-variant';

  return (
    <header className="border-b border-outline-variant bg-surface-container px-4 py-6 sm:px-6">
      <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-headline-lg text-on-surface">{session.name}</h1>
          <p className="mt-1 text-code-sm text-on-surface-variant">
            Session ID: {session.sessionId}
          </p>
        </div>
        {actions}
      </div>

      <div className="grid grid-cols-1 gap-6 md:grid-cols-2 xl:grid-cols-[2fr_1fr_1fr_1.5fr]">
        <div className="space-y-1">
          <h3 className={label}>Description</h3>
          <p className="max-w-prose text-body-md text-on-surface">
            {session.description}
          </p>
        </div>

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
          <h3 className={label}>Initiator</h3>
          <div className="flex items-center gap-2">
            <div className="flex size-6 items-center justify-center rounded-full bg-accent text-label-sm text-accent-foreground">
              {initials}
            </div>
            <p className="text-body-md text-on-surface">{session.initiator}</p>
          </div>
        </div>

        <div className="min-w-0 space-y-1">
          <h3 className={label}>Project Path</h3>
          <button
            onClick={handleCopyPath}
            aria-label={copied ? 'Project path copied' : 'Copy project path'}
            className="group flex w-full items-center justify-between rounded-lg border border-border bg-card p-2 shadow-card transition-standard hover:border-ring"
          >
            <code className="truncate pr-4 text-code-sm text-on-surface">
              {session.projectPath}
            </code>
            <span
              className="material-symbols-outlined text-sm transition-colors group-hover:text-primary"
              aria-hidden="true"
              style={{ color: copied ? 'var(--color-primary)' : undefined }}
            >
              {copied ? 'check' : 'content_copy'}
            </span>
          </button>
        </div>
      </div>
    </header>
  );
}
