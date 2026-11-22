// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link } from 'react-router';
import { Button } from '@/components/ui/button';
import { InlineError } from '@/components/common/InlineError';
import { RequirePermission } from '@/components/common/RequirePermission';
import { Tip } from '@/components/ui/tooltip';
import { StateMessage } from '@/components/common/StateMessage';
import { cn } from '@/lib/utils';
import {
  useAttachments,
  useOwnership,
  useSetAttachmentEnabled,
  useSupervisorType,
} from '../hooks/useSupervisorTypes';
import { NodeChip } from './ActionsTimeline';
import { Chip } from './SupervisorCard';
import { ownerLabel, parseScope, targetLabel } from '../lib/ownership';
import {
  operationRows,
  type OperationRow,
  type Reach,
} from '../lib/operations';
import type { AttachmentView, SupervisorView } from '../types';

const SOURCE: Record<AttachmentView['source'], { label: string; tip: string }> =
  {
    MANUAL: { label: 'By hand', tip: 'Assigned by hand or by an agent' },
    DEFINITION: {
      label: 'In the definition',
      tip: 'Declared in the workflow definition',
    },
    SYSTEM: { label: 'Set by the server', tip: 'Set by the server' },
  };

/** Turns an assignment on or off; only the ones made by hand can be, the others say where to change them. */
function AttachmentToggle({ assignment }: { assignment: AttachmentView }) {
  const toggle = useSetAttachmentEnabled();
  const [confirm, setConfirm] = useState(false);
  const target = targetLabel(parseScope(assignment.scope));
  if (assignment.source !== 'MANUAL') {
    return (
      <Tip
        label={
          assignment.source === 'DEFINITION'
            ? 'Declared in the workflow definition: change it there'
            : 'Set by the server: it cannot be changed here'
        }
      >
        <span
          aria-hidden="true"
          className="material-symbols-outlined text-on-surface-variant text-lg"
        >
          lock
        </span>
      </Tip>
    );
  }
  const apply = (enabled: boolean) =>
    toggle.mutate(
      { id: assignment.id, enabled },
      { onSettled: () => setConfirm(false) }
    );
  if (confirm) {
    return (
      <div
        role="alertdialog"
        aria-label="Disable the assignment?"
        className="border-border bg-muted/40 flex flex-wrap items-center gap-2 rounded-lg border px-2 py-1"
      >
        <span className="text-body-sm text-on-surface">
          Its nodes go back to the next owner or Direct control.
        </span>
        <Button
          size="sm"
          variant="destructive"
          autoFocus
          disabled={toggle.isPending}
          onClick={() => apply(false)}
        >
          Disable now
        </Button>
        <Button size="sm" variant="ghost" onClick={() => setConfirm(false)}>
          Cancel
        </Button>
      </div>
    );
  }
  return (
    <div className="flex flex-col items-end gap-1">
      <RequirePermission perm="editSessions">
        <Tip
          label={
            assignment.enabled
              ? 'Stop this supervisor owning these nodes'
              : 'Let this supervisor own these nodes again'
          }
        >
          <Button
            size="sm"
            variant="ghost"
            aria-label={`${assignment.enabled ? 'Disable' : 'Enable'} ${assignment.supervisorId} on ${target}`}
            disabled={toggle.isPending}
            onClick={() =>
              assignment.enabled ? setConfirm(true) : apply(true)
            }
          >
            {toggle.isPending
              ? 'Saving…'
              : assignment.enabled
                ? 'Disable'
                : 'Enable'}
          </Button>
        </Tip>
      </RequirePermission>
      <InlineError
        message={toggle.error ? (toggle.error as Error).message : null}
      />
    </div>
  );
}

const MAX_CHIPS = 12;

/** One assignment: where it applies, how it was made, whether it is on, and its nodes with their owners. */
export function AssignmentCard({
  supervisorId,
  assignment,
}: {
  supervisorId: string;
  assignment: AttachmentView;
}) {
  const target = parseScope(assignment.scope);
  const ownership = useOwnership(target.sessionId, target.workflowId);
  const [all, setAll] = useState(false);
  const source = SOURCE[assignment.source];
  const scope =
    target.kind === 'run'
      ? 'Run level'
      : target.selectors.length === 0
        ? 'Whole workflow'
        : `Nodes: ${target.selectors.join(', ')}`;
  const nodes = Object.entries(ownership.data?.nodes ?? {});
  const shown = all ? nodes : nodes.slice(0, MAX_CHIPS);
  return (
    <section
      aria-label={`${targetLabel(target)} assignment`}
      className="border-border bg-card shadow-card flex flex-col gap-2.5 rounded-xl border p-3"
    >
      <div className="flex flex-wrap items-center gap-2">
        <span
          aria-hidden="true"
          className="material-symbols-outlined text-primary text-lg"
        >
          {target.kind === 'run' || target.selectors.length === 0
            ? 'account_tree'
            : 'hub'}
        </span>
        {target.sessionId ? (
          <Link
            to={`/sessions/${encodeURIComponent(target.sessionId)}/workflow/${encodeURIComponent(String(target.workflowId))}`}
            className="text-label-md text-primary hover:underline"
          >
            {target.sessionId} / {target.workflowId}
          </Link>
        ) : (
          <span className="text-label-md">{target.workflowId}</span>
        )}
        <Chip>{scope}</Chip>
        <Tip label={source.tip}>
          <span>
            <Chip>{source.label}</Chip>
          </span>
        </Tip>
        {!assignment.enabled && <Chip tone="attention">Disabled</Chip>}
        <span className="flex-1" />
        <AttachmentToggle assignment={assignment} />
      </div>
      {ownership.data && (
        <p className="text-label-sm text-on-surface-variant">
          Run level: {ownerLabel(ownership.data.run)}
        </p>
      )}
      {ownership.isPending && target.sessionId && (
        <p className="text-body-sm text-muted-foreground">Loading the nodes…</p>
      )}
      {ownership.error && (
        <p className="text-body-sm text-error">
          Could not load the owners of this workflow.
        </p>
      )}
      {nodes.length > 0 && (
        <ul
          className={cn(
            'flex flex-wrap gap-1.5',
            !assignment.enabled && 'opacity-60'
          )}
        >
          {shown.map(([node, owner]) => {
            const mine = owner === supervisorId;
            return (
              <li key={node}>
                <NodeChip
                  id={node}
                  mine={mine}
                  owner={mine ? undefined : ownerLabel(owner)}
                />
              </li>
            );
          })}
          {nodes.length > MAX_CHIPS && !all && (
            <li>
              <Button size="sm" variant="ghost" onClick={() => setAll(true)}>
                +{nodes.length - MAX_CHIPS} more
              </Button>
            </li>
          )}
        </ul>
      )}
    </section>
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
      <p className="text-body-sm text-on-surface-variant">
        Each node has one owner. Nodes nobody owns are under Direct control.
      </p>
      <div className="grid gap-3 xl:grid-cols-2">
        {workflows.map((a) => (
          <AssignmentCard
            key={a.id}
            supervisorId={supervisor.id}
            assignment={a}
          />
        ))}
      </div>
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

const TILE = {
  all: {
    box: 'border-success/30 bg-success/5',
    glyph: 'bg-success/15 text-success',
    verb: 'Allowed',
  },
  some: {
    box: 'border-attention/30 bg-attention/5',
    glyph: 'bg-attention/15 text-attention',
    verb: 'Partly allowed',
  },
} as const;

/** One operation group as an icon tile: green when all of its commands are allowed, orange when some are. */
function OperationTile({
  label,
  icon,
  reach,
}: {
  label: string;
  icon: string;
  reach: 'all' | 'some';
}) {
  const tone = TILE[reach];
  return (
    <Tip label={reach === 'all' ? label : `${label}: some of its commands`}>
      <span
        className={cn(
          'flex size-12 items-center justify-center rounded-lg border',
          tone.box
        )}
      >
        <span
          aria-hidden="true"
          className={cn(
            'flex size-7 items-center justify-center rounded-md',
            tone.glyph
          )}
        >
          <span className="material-symbols-outlined text-lg">{icon}</span>
        </span>
        <span className="sr-only">
          {tone.verb}: {label}
        </span>
      </span>
    </Tip>
  );
}

/** What one question about a supervisor allows: tiles for the groups it may, one line for the rest. */
function OperationGroup({
  title,
  tip,
  rows,
  pick,
}: {
  title: string;
  tip: string;
  rows: OperationRow[];
  pick: (row: OperationRow) => Reach;
}) {
  const allowed = rows.filter((r) => pick(r) !== 'none');
  const blocked = rows.filter((r) => pick(r) === 'none');
  return (
    <section aria-label={title} className="flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <h4 className="text-overline text-on-surface-variant">
          <Tip label={tip}>
            <span>{title}</span>
          </Tip>
        </h4>
        <span className="text-label-sm text-on-surface-variant tabular-nums">
          {allowed.length} of {rows.length}
        </span>
      </div>
      {allowed.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {allowed.map((row) => (
            <OperationTile
              key={row.label}
              label={row.label}
              icon={row.icon}
              reach={pick(row) as 'all' | 'some'}
            />
          ))}
        </div>
      )}
      <p className="text-body-sm text-on-surface-variant flex items-center gap-1.5">
        <span
          aria-hidden="true"
          className="material-symbols-outlined text-base"
        >
          lock
        </span>
        {blocked.length === 0
          ? 'Nothing is blocked'
          : allowed.length === 0
            ? `Nothing: ${blocked.map((r) => r.label).join(', ')}`
            : `Not allowed: ${blocked.map((r) => r.label).join(', ')}`}
      </p>
    </section>
  );
}

/** What it may send, what it sends on its own, and what people may still send through it. */
export function OperationsTab({ supervisor }: { supervisor: SupervisorView }) {
  const type = useSupervisorType(supervisor.type, !supervisor.system);
  if (type.isPending && !supervisor.system)
    return <StateMessage tone="loading" title="Loading…" />;
  const rows = operationRows(
    supervisor.settings,
    type.data?.defaultAllowedCommands ?? null
  );
  return (
    <div className="flex flex-col gap-4">
      <OperationGroup
        title="It may send"
        tip="Setting allowedCommands"
        rows={rows}
        pick={(r) => r.send}
      />
      <OperationGroup
        title="Acts on its own"
        tip="What it may send with no event to react to (setting initiative)"
        rows={rows}
        pick={(r) => r.onItsOwn}
      />
      <OperationGroup
        title="People can, through it"
        tip="What people may still send on what it owns, through it (setting operatorAccess)"
        rows={rows}
        pick={(r) => r.people}
      />
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
