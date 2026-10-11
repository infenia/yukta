// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Badge } from '@/components/ui/badge';
import type { SessionDetails } from '../types/session';

interface SessionInfoPanelProps {
  session: SessionDetails;
}

function getInitials(initiator: string): string {
  const parts = initiator.trim().split(/\s+/);
  if (parts.length >= 2) {
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }
  return initiator.substring(0, 2).toUpperCase();
}

export function SessionInfoPanel({ session }: SessionInfoPanelProps) {
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

  return (
    <header className="border-outline-variant bg-surface-container border-b p-8 py-12">
      <div className="mx-auto max-w-6xl">
        <div className="mb-6 flex items-start justify-between">
          <div className="space-y-1">
            <h1 className="text-on-surface text-3xl font-bold tracking-tight">
              {session.name}
            </h1>
            <p className="text-on-surface-variant font-mono text-sm">
              Session ID: {session.sessionId}
            </p>
          </div>
        </div>

        <div className="grid grid-cols-1 gap-12 md:grid-cols-3">
          <div className="space-y-6 md:col-span-2">
            <div className="mb-4 space-y-1">
              <h3 className="text-on-surface-variant text-xs font-bold tracking-widest uppercase">
                Description
              </h3>
              <p className="text-on-surface max-w-2xl text-sm leading-relaxed">
                {session.description}
              </p>
            </div>

            <div className="space-y-2">
              <h3 className="text-on-surface-variant text-xs font-bold tracking-widest uppercase">
                Tags
              </h3>
              <div className="flex flex-wrap gap-2">
                {session.tags.map((tag) => (
                  <Badge
                    key={tag}
                    variant="outline"
                    className="bg-secondary-container text-on-secondary-container text-xs font-semibold uppercase"
                  >
                    {tag}
                  </Badge>
                ))}
              </div>
            </div>
          </div>

          <div className="bg-surface-container-high space-y-6 p-6">
            <div className="space-y-1">
              <h3 className="text-on-surface-variant text-xs font-bold tracking-widest uppercase">
                Initiator
              </h3>
              <div className="flex items-center gap-2">
                <div className="bg-primary-container text-on-primary-container flex h-6 w-6 items-center justify-center text-xs font-bold">
                  {initials}
                </div>
                <p className="text-on-surface text-sm font-medium">
                  {session.initiator}
                </p>
              </div>
            </div>

            <div className="space-y-1">
              <h3 className="text-on-surface-variant text-xs font-bold tracking-widest uppercase">
                Project Path
              </h3>
              <button
                onClick={handleCopyPath}
                aria-label={
                  copied ? 'Project path copied' : 'Copy project path'
                }
                className="group border-outline-variant bg-surface hover:bg-surface-variant flex items-center justify-between border p-2"
              >
                <code className="text-on-surface truncate pr-4 text-xs">
                  {session.projectPath}
                </code>
                <span
                  className="material-symbols-outlined group-hover:text-primary text-sm transition-colors"
                  aria-hidden="true"
                  style={{ color: copied ? 'var(--color-primary)' : undefined }}
                >
                  {copied ? 'check' : 'content_copy'}
                </span>
              </button>
            </div>
          </div>
        </div>
      </div>
    </header>
  );
}
