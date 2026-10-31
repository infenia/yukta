// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Panel } from '@/components/dashboard/Tile';
import { StateMessage } from '@/components/common/StateMessage';
import { InlineError } from '@/components/common/InlineError';
import type { SupervisorProfile } from '../api/supervisors';
import { AttachDialog } from './AttachDialog';
import { ProfileDialog } from './ProfileDialog';
import { SupervisorCard } from './SupervisorCard';
import {
  useBindings,
  useDecisions,
  useDeleteProfile,
  useProfiles,
  useRunningSupervisors,
} from './useSupervisorData';

const outcomeClass: Record<string, string> = {
  applied: 'text-success',
  rejected: 'text-destructive',
  'dry-run': 'text-primary',
  'rate-limited': 'text-warning',
};

/** The newest decisions of every supervisor, newest first. */
function DecisionFeed() {
  const decisions = useDecisions();
  return (
    <Panel title="Recent decisions">
      {decisions.isPending ? (
        <StateMessage compact tone="loading" title="Loading" />
      ) : decisions.error ? (
        <StateMessage
          compact
          tone="error"
          title="Could not load"
          onRetry={() => void decisions.refetch()}
        >
          {decisions.error.message}
        </StateMessage>
      ) : decisions.data.length === 0 ? (
        <StateMessage compact title="No supervisor has decided anything yet" />
      ) : (
        <ul className="flex flex-col gap-1 text-body-sm">
          {decisions.data.map((d) => (
            <li
              key={`${d.time}-${d.supervisorId}-${d.executionId}-${d.command}`}
              className="flex flex-wrap gap-x-2"
            >
              <time className="num text-on-surface-variant" dateTime={d.time}>
                {new Date(d.time).toLocaleTimeString()}
              </time>
              <span className="font-mono">{d.supervisorId}</span>
              <span className={outcomeClass[d.outcome]}>{d.outcome}</span>
              <span>{d.command}</span>
              <span className="text-on-surface-variant">{d.executionId}</span>
              {d.reason && (
                <span className="text-on-surface-variant">({d.reason})</span>
              )}
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

/** The profiles, each with edit and delete (a profile a binding uses cannot be deleted). */
function ProfileList({
  onEdit,
}: {
  onEdit: (profile: SupervisorProfile) => void;
}) {
  const profiles = useProfiles();
  const remove = useDeleteProfile();
  return (
    <Panel title="Profiles">
      {profiles.isPending ? (
        <StateMessage compact tone="loading" title="Loading" />
      ) : profiles.error ? (
        <StateMessage
          compact
          tone="error"
          title="Could not load"
          onRetry={() => void profiles.refetch()}
        >
          {profiles.error.message}
        </StateMessage>
      ) : profiles.data.length === 0 ? (
        <StateMessage compact title="No profile yet" />
      ) : (
        <ul className="flex flex-col gap-1">
          {profiles.data.map((p) => (
            <li key={p.id} className="flex items-center justify-between gap-2">
              <span className="text-body-md">
                <span className="font-mono">{p.id}</span>{' '}
                <span className="text-on-surface-variant">{p.type}</span>
              </span>
              <span className="flex gap-1">
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => onEdit(p)}
                  aria-label={`Edit profile ${p.id}`}
                >
                  Edit
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={remove.isPending}
                  onClick={() => remove.mutate(p.id)}
                  aria-label={`Delete profile ${p.id}`}
                >
                  Delete
                </Button>
              </span>
            </li>
          ))}
        </ul>
      )}
      {remove.error && <InlineError message={remove.error.message} />}
    </Panel>
  );
}

/**
 * The supervisor cockpit: a card for every supervisor, the profiles to attach, and what the
 * supervisors decided. Replaces the old table of supervisors.
 */
export function SupervisorCockpit() {
  const running = useRunningSupervisors();
  const bindings = useBindings();
  const profiles = useProfiles();
  const [editing, setEditing] = useState<SupervisorProfile | 'new' | null>(
    null
  );
  const [attaching, setAttaching] = useState(false);

  const attached = bindings.data ?? [];
  const live = running.data ?? [];
  const bound = new Set(attached.map((b) => b.id));
  const startup = live.filter((s) => !bound.has(s.id));
  const error = running.error ?? bindings.error;

  return (
    <section
      aria-labelledby="supervisors-title"
      className="flex flex-col gap-3"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="supervisors-title" className="text-headline-md text-on-surface">
          Supervisors
        </h2>
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => setEditing('new')}>
            New profile
          </Button>
          <Button onClick={() => setAttaching(true)}>Attach supervisor</Button>
        </div>
      </div>
      {running.isPending || bindings.isPending ? (
        <StateMessage compact tone="loading" title="Loading supervisors" />
      ) : error ? (
        <StateMessage
          compact
          tone="error"
          title="Could not load supervisors"
          onRetry={() => {
            void running.refetch();
            void bindings.refetch();
          }}
        >
          {error.message}
        </StateMessage>
      ) : attached.length + startup.length === 0 ? (
        <StateMessage compact title="No supervisors">
          Create a profile, then attach it to everything, one session or one
          workflow.
        </StateMessage>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {attached.map((b) => (
            <SupervisorCard
              key={b.id}
              binding={b}
              profile={profiles.data?.find((p) => p.id === b.profileId)}
              running={live.find((s) => s.id === b.id)}
            />
          ))}
          {startup.map((s) => (
            <SupervisorCard key={s.id} running={s} />
          ))}
        </div>
      )}
      <div className="grid gap-3 lg:grid-cols-2">
        <ProfileList onEdit={setEditing} />
        <DecisionFeed />
      </div>
      {editing && (
        <ProfileDialog
          profile={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
        />
      )}
      {attaching && <AttachDialog onClose={() => setAttaching(false)} />}
    </section>
  );
}
