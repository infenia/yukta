// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { cn } from '@/lib/utils';
import { Tip } from '@/components/ui/tooltip';
import { StateMessage } from '@/components/common/StateMessage';
import { useMe } from '@/lib/identity';
import {
  useOwnership,
  useSupervision,
  useSupervisorDecisions,
  useSupervisors,
  useSupervisorTypes,
} from '../hooks/useSupervisorTypes';
import { controlledBy } from './RequireControl';
import { ActionsCard } from './ActionsTimeline';
import { SupervisorCard } from './SupervisorCard';

/** The node commands the server names, in the order the node actions show them. */
const NODE_COMMANDS: [key: string, label: string, icon: string][] = [
  ['pauseNode', 'Pause', 'pause'],
  ['resumeNode', 'Resume', 'play_arrow'],
  ['stopNode', 'Stop', 'stop'],
  ['skipNode', 'Skip', 'skip_next'],
  ['stepNode', 'Step', 'step'],
];

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
      <SupervisorCard
        owner={owner}
        supervisor={supervisor}
        typeName={typeName}
        label={direct ? 'Control' : 'Supervised by'}
        scopeIcon={viaRun ? 'account_tree' : 'hub'}
        scope={
          direct
            ? 'Nobody supervises this node: people and agents with the right role send its commands directly.'
            : viaRun
              ? 'Controls the whole run, including this node'
              : 'Controls this node'
        }
      />

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
        <ActionsCard
          title="Actions taken here"
          actions={actions}
          loading={decisions.isPending}
          empty="No actions taken yet by this supervisor."
          nodeLabel={(d) => (d.nodeId ? 'this node' : 'whole run')}
        />
      )}
    </div>
  );
}
