// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { StateMessage } from '@/components/common/StateMessage';
import { useMe } from '@/lib/identity';
import {
  useOwnership,
  useSupervision,
  useSupervisors,
  useSupervisorTypes,
} from '../hooks/useSupervisorTypes';
import { controlledBy } from './RequireControl';
import { Controller } from './WorkflowSupervision';

/** The node commands the server names, in the order the node actions show them. */
const NODE_COMMANDS: [key: string, label: string][] = [
  ['pauseNode', 'Pause'],
  ['resumeNode', 'Resume'],
  ['stopNode', 'Stop'],
  ['skipNode', 'Skip'],
  ['stepNode', 'Step'],
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
  const owner = ownership.data.nodes[nodeId] ?? 'default';
  const supervisor = supervisors.data?.find((s) => s.id === owner);
  const rows = NODE_COMMANDS.flatMap(([key, label]) => {
    const capability = capabilities.data?.[key];
    if (!capability) return [];
    const roleBlocked = Boolean(me && !me.permissions.controlRuns);
    return [
      {
        key,
        label,
        ok: capability.allowed && !roleBlocked,
        detail: capability.allowed
          ? `Needs a higher role; your key is ${me?.role}.`
          : (capability.reason ?? controlledBy(capability.via)),
      },
    ];
  });
  const can = rows.filter((row) => row.ok);
  const cannot = rows.filter((row) => !row.ok);
  return (
    <div className="flex flex-col gap-4">
      <Controller
        owner={owner}
        supervisor={supervisor}
        typeName={
          types.data?.find((t) => t.type === supervisor?.type)?.displayName
        }
        runLevel={false}
        ids={[]}
        hideIcon
        summary={
          owner === 'default'
            ? 'Nobody supervises this node: people and agents with the right role send its commands directly.'
            : 'Controls this node'
        }
      />
      <section aria-label="Node commands" className="flex flex-col gap-3">
        <h3 className="text-overline text-on-surface-variant">
          What you can do with this node
        </h3>
        {rows.length === 0 && (
          <p className="text-body-sm text-on-surface-variant">
            {executionId
              ? 'The server did not say which commands are allowed.'
              : 'Open a run to see which commands you can send to this node.'}
          </p>
        )}
        {can.length > 0 && (
          <ul className="flex flex-wrap gap-1.5">
            {can.map(({ key, label }) => (
              <li
                key={key}
                className="border-success/30 bg-success/10 text-label-md text-success inline-flex items-center gap-1 rounded-full border py-1 pr-2.5 pl-2"
              >
                <span
                  aria-hidden="true"
                  className="material-symbols-outlined text-base"
                >
                  check
                </span>
                <span className="sr-only">Allowed: </span>
                {label}
              </li>
            ))}
          </ul>
        )}
        {cannot.length > 0 && (
          <ul className="flex flex-col gap-1.5">
            {cannot.map(({ key, label, detail }) => (
              <li
                key={key}
                className="border-destructive/30 bg-destructive/5 flex items-start gap-2.5 rounded-lg border p-2.5"
              >
                <span
                  aria-hidden="true"
                  className="material-symbols-outlined text-destructive mt-px text-lg"
                >
                  lock
                </span>
                <span className="sr-only">Not allowed: </span>
                <div className="min-w-0">
                  <div className="text-body-md text-on-surface font-medium">
                    {label}
                  </div>
                  <div className="text-body-sm text-on-surface-variant">
                    {detail}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
