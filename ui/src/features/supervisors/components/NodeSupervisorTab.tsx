// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
import type { ReactNode } from 'react';
import { cn } from '@/lib/utils';
import { Tip } from '@/components/ui/tooltip';
import { StateMessage } from '@/components/common/StateMessage';
import { useMe } from '@/lib/identity';
import { formatDateTime } from '@/features/workflow/lib/timeFormat';
import {
  useOwnership,
  useSupervision,
  useSupervisorDecisions,
  useSupervisors,
  useSupervisorTypes,
} from '../hooks/useSupervisorTypes';
import { controlledBy } from './RequireControl';

/** The node commands the server names, in the order the node actions show them. */
const NODE_COMMANDS: [key: string, label: string, icon: string][] = [
  ['pauseNode', 'Pause', 'pause'],
  ['resumeNode', 'Resume', 'play_arrow'],
  ['stopNode', 'Stop', 'stop'],
  ['skipNode', 'Skip', 'skip_next'],
  ['stepNode', 'Step', 'step'],
];

/** A small rounded label next to a name. */
function Chip({
  children,
  tone = 'muted',
}: {
  children: ReactNode;
  tone?: 'muted' | 'attention';
}) {
  return (
    <span
      className={cn(
        'text-label-sm rounded-full border px-2 py-px',
        tone === 'attention'
          ? 'border-attention/30 bg-attention/10 text-attention'
          : 'border-border bg-background/60 text-on-surface-variant'
      )}
    >
      {children}
    </span>
  );
}

/** The owner as a link to its page, or plain words for Direct control. */
const OwnerLink = ({ owner }: { owner: string }) =>
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

/** Who controls one node, as the node drawer's Supervisor tab, and what you can do with it. */
export function NodeSupervisorTab({
  sessionId,
  workflowId,
  executionId,
  nodeId,
}: {
  sessionId: string;
  workflowId: string;
  executionId: string | null;
  nodeId: string;
}) {
  const me = useMe();
  const ownership = useOwnership(sessionId, workflowId);
  const supervisors = useSupervisors();
  const types = useSupervisorTypes();
  const decisions = useSupervisorDecisions();
  const capabilities = useSupervision(
    { execution: executionId, node: nodeId },
    Boolean(executionId)
  );
  if (ownership.isPending) {
    return <StateMessage tone="loading" compact title="Loading…" />;
  }
  if (ownership.error) {
    return (
      <StateMessage
        tone="error"
        compact
        title="Could not load who controls this node."
      />
    );
  }
  const nodeOwner = ownership.data.nodes[nodeId] ?? 'default';
  // a supervisor that owns the whole run carries its nodes too
  const viaRun = nodeOwner === 'default' && ownership.data.run !== 'default';
  const owner = viaRun ? ownership.data.run : nodeOwner;
  const direct = owner === 'default';
  const supervisor = supervisors.data?.find((s) => s.id === owner);
  const typeName = types.data?.find(
    (t) => t.type === supervisor?.type
  )?.displayName;
  const parents = supervisor?.parents ?? [];
  // what this supervisor did here: commands for this node, and for its run
  const actions = (decisions.data ?? [])
    .filter(
      (d) =>
        d.supervisorId === owner &&
        d.workflowId === workflowId &&
        (d.nodeId
          ? d.nodeId === nodeId
          : !executionId || d.executionId === executionId)
    )
    .slice(0, 5);
  const rows = NODE_COMMANDS.flatMap(([key, label, icon]) => {
    const capability = capabilities.data?.[key];
    if (!capability) return [];
    const roleBlocked = Boolean(me && !me.permissions.controlRuns);
    return [
      {
        key,
        label,
        icon,
        ok: capability.allowed && !roleBlocked,
        detail: capability.allowed
          ? `Needs a higher role; your key is ${me?.role}.`
          : (capability.reason ?? controlledBy(capability.via)),
      },
    ];
  });
  const allowed = rows.filter((row) => row.ok).length;
  return (
    <div className="flex flex-col gap-4">
      <section
        aria-label="Supervisor"
        className={cn(
          'relative overflow-hidden rounded-xl border p-3',
          direct
            ? 'border-border bg-muted/30'
            : 'border-primary/25 from-primary/15 via-primary/5 bg-linear-to-br to-transparent'
        )}
      >
        {!direct && (
          <span
            aria-hidden="true"
            className="bg-primary/20 pointer-events-none absolute -top-12 -right-12 size-36 rounded-full blur-3xl"
          />
        )}
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
            <p className="text-overline text-on-surface-variant">
              {direct ? 'Control' : 'Supervised by'}
            </p>
            <h3 className="text-body-md text-on-surface font-semibold break-all">
              <OwnerLink owner={owner} />
            </h3>
            {(typeName || supervisor?.dryRun || supervisor?.system) && (
              <div className="mt-1 flex flex-wrap items-center gap-1.5">
                {typeName && <Chip>{typeName}</Chip>}
                {supervisor?.dryRun && <Chip tone="attention">Dry run</Chip>}
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
          <p className="text-body-sm text-on-surface flex items-center gap-2">
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-primary text-lg"
            >
              {viaRun ? 'account_tree' : 'hub'}
            </span>
            {direct
              ? 'Nobody supervises this node: people and agents with the right role send its commands directly.'
              : viaRun
                ? 'Controls the whole run, including this node'
                : 'Controls this node'}
          </p>
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
        </div>
      </section>

      <section aria-label="Node commands" className="flex flex-col gap-2">
        <div className="flex items-baseline justify-between gap-2">
          <h3 className="text-overline text-on-surface-variant">
            What you can do with this node
          </h3>
          {rows.length > 0 && (
            <span className="text-label-sm text-on-surface-variant">
              {allowed} of {rows.length} allowed
            </span>
          )}
        </div>
        {rows.length === 0 && (
          <p className="text-body-sm text-on-surface-variant">
            {executionId
              ? 'The server did not say which commands are allowed.'
              : 'Open a run to see which commands you can send to this node.'}
          </p>
        )}
        <ul className="flex flex-wrap gap-1.5">
          {rows.map(({ key, label, icon, ok, detail }) => (
            <li
              key={key}
              className={cn(
                'transition-standard flex flex-col gap-1 rounded-lg border',
                ok
                  ? 'border-success/30 bg-success/5 hover:bg-success/10 size-12 items-center justify-center'
                  : 'border-border bg-muted/30 w-full p-2'
              )}
            >
              <div className="flex items-center gap-2 self-stretch">
                <Tip label={ok ? label : undefined}>
                  <span
                    className={cn(
                      'flex size-7 shrink-0 items-center justify-center rounded-md',
                      ok
                        ? 'bg-success/15 text-success mx-auto'
                        : 'bg-muted text-on-surface-variant'
                    )}
                  >
                    <span
                      aria-hidden="true"
                      className="material-symbols-outlined text-base"
                    >
                      {icon}
                    </span>
                    {ok && (
                      <>
                        <span className="sr-only">Allowed: </span>
                        <span className="sr-only">{label}</span>
                      </>
                    )}
                  </span>
                </Tip>
                {!ok && (
                  <>
                    <span className="sr-only">Not allowed: </span>
                    <span className="text-body-md text-on-surface flex-1 font-medium">
                      {label}
                    </span>
                    <span
                      aria-hidden="true"
                      className="material-symbols-outlined text-on-surface-variant text-lg"
                    >
                      lock
                    </span>
                  </>
                )}
              </div>
              {!ok && (
                <p className="text-body-sm text-on-surface-variant pl-9">
                  {detail}
                </p>
              )}
            </li>
          ))}
        </ul>
      </section>

      {!direct && (
        <section
          aria-label="Supervisor actions"
          className="flex flex-col gap-2"
        >
          <h3 className="text-overline text-on-surface-variant">
            Actions taken here
          </h3>
          {actions.length === 0 && (
            <p className="text-body-sm text-on-surface-variant">
              {decisions.isPending
                ? 'Loading…'
                : 'No actions taken yet by this supervisor.'}
            </p>
          )}
          <ol className="border-border flex flex-col border-l">
            {actions.map((d) => (
              <li
                key={`${d.time}-${d.command}-${d.executionId}-${d.nodeId ?? ''}`}
                className="relative flex flex-col gap-0.5 py-1.5 pl-3.5"
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    'absolute top-3 -left-[4.5px] size-2 rounded-full',
                    d.outcome === 'applied' ? 'bg-success' : 'bg-attention'
                  )}
                />
                <div className="flex items-baseline justify-between gap-2">
                  <span className="text-body-md text-on-surface font-medium">
                    {d.command.replace(/(Workflow|Node)?Command$/, '')}
                    <span className="text-label-sm text-on-surface-variant ml-1.5 font-normal">
                      {d.nodeId ? 'this node' : 'whole run'} · {d.outcome}
                    </span>
                  </span>
                  <span className="text-label-sm text-on-surface-variant shrink-0">
                    {formatDateTime(d.time)}
                  </span>
                </div>
                {(d.because ?? d.reason) && (
                  <p className="text-body-sm text-on-surface-variant">
                    {d.because ?? d.reason}
                  </p>
                )}
              </li>
            ))}
          </ol>
        </section>
      )}
    </div>
  );
}
