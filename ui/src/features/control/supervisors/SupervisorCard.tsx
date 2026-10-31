// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Donut } from '@/components/dashboard/Gauges';
import { Button } from '@/components/ui/button';
import { InlineError } from '@/components/common/InlineError';
import { ApiError } from '@/lib/apiClient';
import {
  scopeLabel,
  type Supervisor,
  type SupervisorBinding,
  type SupervisorProfile,
} from '../api/supervisors';
import { useAttach, useDetach, useReplaceProfile } from './useSupervisorData';

const failure = (error: Error | null) =>
  error instanceof ApiError && error.status === 403
    ? 'This needs an ADMIN key.'
    : error?.message;

/**
 * One supervisor: where it applies, what it decided (applied, refused, only recorded, held back by
 * the rate limit), and for one attached at run time the dry-run switch, enable switch and detach.
 * One configured at startup can be read but not changed here.
 */
export function SupervisorCard({
  binding,
  profile,
  running,
}: {
  /** The binding that attached it; absent for one configured at startup. */
  binding?: SupervisorBinding;
  profile?: SupervisorProfile;
  /** Its live numbers; absent while a binding is disabled. */
  running?: Supervisor;
}) {
  const attach = useAttach();
  const detach = useDetach();
  const replace = useReplaceProfile();
  const [confirming, setConfirming] = useState(false);
  const id = binding?.id ?? running?.id ?? '';
  const scope = binding?.scope ?? scopeOfRunning(running);
  const enabled = binding?.enabled ?? true;
  const error = attach.error ?? detach.error ?? replace.error;

  return (
    <article
      aria-label={`Supervisor ${id}`}
      className="flex min-w-0 flex-col gap-3 rounded-xl border border-border bg-card p-4"
    >
      <header className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="truncate text-label-md font-mono text-on-surface">
            {id}
          </h3>
          <p className="text-body-sm text-on-surface-variant">
            {profile?.type ?? running?.type ?? ''}
            {running?.kind === 'CONTROL_PLANE' && ' (control plane)'}
          </p>
        </div>
        <div className="flex flex-col items-end gap-1">
          <span className="rounded-full bg-accent px-2 py-0.5 text-label-sm text-accent-foreground">
            {scopeLabel(scope)}
          </span>
          {!enabled && (
            <span className="rounded-full bg-muted px-2 py-0.5 text-label-sm">
              Disabled
            </span>
          )}
          {running?.dryRun && (
            <span className="rounded-full bg-muted px-2 py-0.5 text-label-sm">
              Dry run
            </span>
          )}
        </div>
      </header>
      {running ? (
        <Donut
          segments={[
            { label: 'Applied', value: running.applied, tone: 'success' },
            { label: 'Rejected', value: running.rejected, tone: 'danger' },
            { label: 'Dry-run', value: running.recorded, tone: 'primary' },
            {
              label: 'Rate-limited',
              value: running.rateLimited,
              tone: 'warning',
            },
          ]}
        />
      ) : (
        <p className="text-body-sm text-on-surface-variant">Not running.</p>
      )}
      {binding ? (
        <div className="flex flex-wrap items-center gap-3">
          {profile && (
            <label className="flex items-center gap-2 text-body-md">
              <input
                type="checkbox"
                role="switch"
                checked={profile.dryRun}
                disabled={replace.isPending}
                onChange={(e) =>
                  replace.mutate({ ...profile, dryRun: e.target.checked })
                }
              />
              Dry run
            </label>
          )}
          <label className="flex items-center gap-2 text-body-md">
            <input
              type="checkbox"
              role="switch"
              checked={enabled}
              disabled={attach.isPending}
              onChange={(e) =>
                attach.mutate({
                  id: binding.id,
                  profileId: binding.profileId,
                  scope: binding.scope,
                  enabled: e.target.checked,
                })
              }
            />
            Enabled
          </label>
          {confirming ? (
            <span className="flex items-center gap-2">
              <Button
                size="sm"
                variant="destructive"
                disabled={detach.isPending}
                onClick={() => detach.mutate(binding.id)}
              >
                Confirm detach
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={() => setConfirming(false)}
              >
                Keep
              </Button>
            </span>
          ) : (
            <Button
              size="sm"
              variant="outline"
              onClick={() => setConfirming(true)}
            >
              Detach
            </Button>
          )}
        </div>
      ) : (
        <p className="text-body-sm text-on-surface-variant">
          Configured at startup.
        </p>
      )}
      {error && <InlineError message={failure(error) ?? null} />}
    </article>
  );
}

function scopeOfRunning(running?: Supervisor): string {
  if (!running || running.scope === 'GLOBAL') return 'global';
  if (running.scope === 'SESSION') return `session:${running.scopeSessionId}`;
  return running.scopeSessionId
    ? `workflow:${running.scopeSessionId}/${running.scopeWorkflowId}`
    : `workflow:${running.scopeWorkflowId}`;
}
