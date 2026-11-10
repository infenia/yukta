// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
import { StateMessage } from '@/components/common/StateMessage';
import { cn } from '@/lib/utils';
import {
  useAttachments,
  useOwnership,
  useSupervisorType,
} from '../hooks/useSupervisorTypes';
import { ownerLabel, parseScope, targetLabel } from '../lib/ownership';
import { HierarchyGraph } from './HierarchyGraph';
import { operationRows, type Reach } from '../lib/operations';
import type { AttachmentView, SupervisorView } from '../types';

const SOURCE: Record<AttachmentView['source'], string> = {
  MANUAL: 'assigned by hand or by an agent',
  DEFINITION: 'declared in the definition',
  SYSTEM: 'set by the server',
};

/** The nodes of one workflow with their owners, this supervisor's highlighted. */
function WorkflowOwners({
  supervisorId,
  assignment,
}: {
  supervisorId: string;
  assignment: AttachmentView;
}) {
  const target = parseScope(assignment.scope);
  const ownership = useOwnership(target.sessionId, target.workflowId);
  return (
    <div className="border-border bg-card shadow-card rounded-xl border p-4">
      <div className="flex flex-wrap items-center gap-2">
        {target.sessionId ? (
          <Link
            to={`/sessions/${encodeURIComponent(target.sessionId)}/workflow/${encodeURIComponent(String(target.workflowId))}`}
            className="text-label-md text-primary"
          >
            {target.sessionId} / {target.workflowId}
          </Link>
        ) : (
          <span className="text-label-md">{target.workflowId}</span>
        )}
        <span className="text-body-sm text-muted-foreground">
          {target.kind === 'run'
            ? 'run level'
            : target.selectors.length === 0
              ? 'whole workflow'
              : `by ${target.selectors.join(', ')}`}{' '}
          · {SOURCE[assignment.source]}
        </span>
        {(!assignment.enabled || assignment.conflict) && (
          <span className="text-body-sm text-error inline-flex items-center gap-1">
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-base"
            >
              block
            </span>
            {assignment.conflict
              ? 'Disabled: overlaps another owner'
              : 'Disabled'}
          </span>
        )}
        <span className="flex-1" />
        {ownership.data && (
          <span className="text-body-sm text-muted-foreground">
            Run level: {ownerLabel(ownership.data.run)}
          </span>
        )}
      </div>
      {ownership.isPending && target.sessionId && (
        <p className="text-body-sm text-muted-foreground mt-2">
          Loading the nodes…
        </p>
      )}
      {ownership.error && (
        <p className="text-body-sm text-error mt-2">
          Could not load the owners of this workflow.
        </p>
      )}
      {ownership.data && (
        <ul className="mt-2 flex flex-wrap gap-2">
          {Object.entries(ownership.data.nodes).map(([node, owner]) => {
            const mine = owner === supervisorId;
            return (
              <li
                key={node}
                className={cn(
                  'text-body-sm inline-flex items-center gap-2 rounded-full border px-3 py-1',
                  mine
                    ? 'border-primary bg-accent text-accent-foreground'
                    : 'border-border bg-card text-on-surface-variant'
                )}
              >
                {mine && (
                  <span
                    aria-hidden="true"
                    className="material-symbols-outlined text-base"
                  >
                    check_circle
                  </span>
                )}
                <code className="text-code-sm">{node}</code>
                <span className={mine ? undefined : 'text-muted-foreground'}>
                  {mine ? 'owned here' : ownerLabel(owner)}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

/** What a supervisor owns, workflow by workflow; ownership is changed by agents, not here. */
export function OwnsTab({ supervisor }: { supervisor: SupervisorView }) {
  const { data, isPending, error } = useAttachments({
    supervisor: supervisor.id,
  });
  if (isPending)
    return <StateMessage tone="loading" title="Loading what it owns…" />;
  if (error)
    return <StateMessage tone="error" title="Failed to load what it owns." />;
  if (data.length === 0) {
    return (
      <StateMessage
        title={`${supervisor.id} owns nothing yet`}
        icon="account_tree"
      >
        An agent assigns it with{' '}
        <code className="text-code-sm">yukta supervisors assign</code> or MCP; a
        definition with its <code className="text-code-sm">assignments</code>.
      </StateMessage>
    );
  }
  const workflows = data.filter((a) => {
    const t = parseScope(a.scope);
    return (t.kind === 'workflow' || t.kind === 'run') && t.workflowId;
  });
  const others = data.filter((a) => !workflows.includes(a));
  return (
    <div className="flex flex-col gap-3">
      <p className="text-body-md text-on-surface-variant">
        Each node has one owner. A node nobody owns is controlled by people
        through Default (people).
      </p>
      {workflows.map((a) => (
        <WorkflowOwners
          key={a.id}
          supervisorId={supervisor.id}
          assignment={a}
        />
      ))}
      {others.length > 0 && (
        <ul className="text-body-md flex flex-col gap-1">
          {others.map((a) => (
            <li key={a.id}>{targetLabel(parseScope(a.scope))}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

const REACH: Record<Reach, { icon: string; text: string; tone: string }> = {
  all: { icon: 'check_circle', text: 'Yes', tone: 'text-success' },
  some: { icon: 'rule', text: 'Some', tone: 'text-attention' },
  none: { icon: 'remove', text: 'No', tone: 'text-muted-foreground' },
};

const ReachCell = ({ reach }: { reach: Reach }) => (
  <td className="px-3 py-2">
    <span className={cn('inline-flex items-center gap-1', REACH[reach].tone)}>
      <span aria-hidden="true" className="material-symbols-outlined text-base">
        {REACH[reach].icon}
      </span>
      {REACH[reach].text}
    </span>
  </td>
);

/** What it may send, what it sends on its own, and what people may still send through it. */
export function OperationsTab({ supervisor }: { supervisor: SupervisorView }) {
  const type = useSupervisorType(supervisor.type);
  if (type.isPending) return <StateMessage tone="loading" title="Loading…" />;
  const rows = operationRows(
    supervisor.settings,
    type.data?.defaultAllowedCommands ?? null
  );
  return (
    <div className="flex flex-col gap-2">
      <div className="border-border bg-card shadow-card overflow-x-auto rounded-xl border">
        <table className="text-body-md w-full">
          <thead className="bg-muted">
            <tr>
              {[
                'Commands',
                'It may send',
                'Acts on its own',
                'People can, through it',
              ].map((h) => (
                <th
                  key={h}
                  className="text-label-md text-on-surface-variant px-3 py-2 text-left"
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.label} className="border-border border-t">
                <td className="px-3 py-2">{row.label}</td>
                <ReachCell reach={row.send} />
                <ReachCell reach={row.onItsOwn} />
                <ReachCell reach={row.people} />
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="text-body-sm text-muted-foreground">
        “Acts on its own”: what it may send with no event to react to. “People
        can”: what people may still do on what it owns, through it. Settings{' '}
        <code className="text-code-sm">allowedCommands</code>,{' '}
        <code className="text-code-sm">initiative</code> and{' '}
        <code className="text-code-sm">operatorAccess</code>.
      </p>
    </div>
  );
}

const MODES: Record<string, string> = {
  ORDERED: 'Asks in order: the first answer decides',
  ALL: 'Asks all: every one must agree',
  ANY: 'Asks all at once: the first answer decides',
};
const KINDS: [string, string][] = [
  ['APPROVAL', 'Approvals'],
  ['INPUT', 'Input requests'],
  ['CONSULT', 'Consultations'],
];

/** Who it reports to and how it asks them; who reports to it. */
export function HierarchyTab({
  supervisor,
  all,
  onSelect,
}: {
  supervisor: SupervisorView;
  all: SupervisorView[];
  onSelect: (id: string) => void;
}) {
  const parents = supervisor.parents ?? [];
  const children = all.filter((s) => (s.parents ?? []).includes(supervisor.id));
  const link = (id: string) => (
    <button type="button" className="text-primary" onClick={() => onSelect(id)}>
      {id}
    </button>
  );
  return (
    <div className="flex flex-col gap-3">
      <HierarchyGraph supervisor={supervisor} all={all} />
      <div className="border-border bg-card text-body-md shadow-card rounded-xl border p-4">
        <p>
          <b>Reports to: </b>
          {parents.length === 0
            ? 'nobody; people answer what it asks in the inbox'
            : parents.map((p, i) => (
                <span key={p}>
                  {i > 0 && ', '}
                  {link(p)}
                </span>
              ))}
        </p>
        <p className="mt-1">
          <b>Reports to it: </b>
          {children.length === 0
            ? 'nobody'
            : children.map((c, i) => (
                <span key={c.id}>
                  {i > 0 && ', '}
                  {link(c.id)}
                </span>
              ))}
        </p>
      </div>
      {parents.length > 0 && (
        <div className="border-border bg-card shadow-card rounded-xl border p-4">
          <h3 className="text-label-md mb-2">How it asks its parents</h3>
          <dl className="grid gap-2 sm:grid-cols-3">
            {KINDS.map(([key, label]) => (
              <div key={key}>
                <dt className="text-label-md">{label}</dt>
                <dd className="text-body-sm text-on-surface-variant">
                  {MODES[supervisor.delegation?.[key]?.mode ?? 'ORDERED'] ??
                    supervisor.delegation?.[key]?.mode}
                </dd>
              </div>
            ))}
          </dl>
        </div>
      )}
      <p className="text-body-sm text-muted-foreground">
        A supervisor may have several parents; a cycle is refused when it is
        saved.
      </p>
    </div>
  );
}
