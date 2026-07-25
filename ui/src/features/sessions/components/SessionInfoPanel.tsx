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
    <header className="border-b border-outline-variant bg-surface-container p-8 py-12">
      <div className="mx-auto max-w-6xl">
        <div className="mb-6 flex items-start justify-between">
          <div className="space-y-1">
            <h1 className="text-3xl font-bold tracking-tight text-on-surface">{session.name}</h1>
            <p className="font-mono text-sm text-on-surface-variant">Session ID: {session.sessionId}</p>
          </div>
        </div>

        <div className="grid grid-cols-1 gap-12 md:grid-cols-3">
          <div className="space-y-6 md:col-span-2">
            <div className="mb-4 space-y-1">
              <h3 className="text-xs font-bold uppercase tracking-widest text-on-surface-variant">
                Description
              </h3>
              <p className="max-w-2xl text-sm leading-relaxed text-on-surface">
                {session.description}
              </p>
            </div>

            <div className="space-y-2">
              <h3 className="text-xs font-bold uppercase tracking-widest text-on-surface-variant">
                Tags
              </h3>
              <div className="flex flex-wrap gap-2">
                {session.tags.map((tag) => (
                  <Badge key={tag} variant="outline" className="bg-secondary-container text-on-secondary-container text-[11px] font-semibold uppercase">
                    {tag}
                  </Badge>
                ))}
              </div>
            </div>
          </div>

          <div className="space-y-6 bg-surface-container-high p-6">
            <div className="space-y-1">
              <h3 className="text-xs font-bold uppercase tracking-widest text-on-surface-variant">
                Initiator
              </h3>
              <div className="flex items-center gap-2">
                <div className="flex h-6 w-6 items-center justify-center bg-primary-container text-[10px] font-bold text-on-primary-container">
                  {initials}
                </div>
                <p className="text-sm font-medium text-on-surface">{session.initiator}</p>
              </div>
            </div>

            <div className="space-y-1">
              <h3 className="text-xs font-bold uppercase tracking-widest text-on-surface-variant">
                Project Path
              </h3>
              <button
                onClick={handleCopyPath}
                aria-label={copied ? "Project path copied" : "Copy project path"}
                className="group flex items-center justify-between border border-outline-variant bg-surface p-2 hover:bg-surface-variant"
              >
                <code className="truncate pr-4 text-xs text-on-surface">{session.projectPath}</code>
                <span className="material-symbols-outlined text-sm transition-colors group-hover:text-primary" aria-hidden="true"
                  style={{ color: copied ? 'var(--color-primary)' : undefined }}>
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
