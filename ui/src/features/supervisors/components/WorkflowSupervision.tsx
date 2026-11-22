// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { REQUIRED_ROLE, useMe, type Permission } from '@/lib/identity';
import type { Capability } from '../api/supervision';
import type { SupervisorView } from '../types';
import { cn } from '@/lib/utils';
import { inputFieldClassName } from '@/components/ui/native-select';
import { Callout } from '@/components/common/Callout';
import { Tip } from '@/components/ui/tooltip';
import { StateMessage } from '@/components/common/StateMessage';
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

/** The nodes of each owner, in the order the owners first appear. */
function byOwner(nodes: Record<string, string>) {
  const groups = new Map<string, string[]>();
  for (const [node, owner] of Object.entries(nodes)) {
    groups.set(owner, [...(groups.get(owner) ?? []), node]);
  }
  return [...groups.entries()];
}

/** An icon in a tinted tile, the way `StateMessage` draws its icon. */
function Tile({
  icon,
  tone,
}: {
  icon: string;
  tone: 'primary' | 'success' | 'muted';
}) {
  const color = {
    primary: 'bg-primary/10 text-primary',
    success: 'bg-success/10 text-success',
    muted: 'bg-muted text-on-surface-variant',
  }[tone];
  return (
    <span
      aria-hidden="true"
      className={`flex size-10 shrink-0 items-center justify-center rounded-xl ${color}`}
    >
      <span className="material-symbols-outlined">{icon}</span>
    </span>
  );
}

/** The commands of a run in the order the run controls show them, with the role each needs. */
const COMMANDS: {
  key: string;
  label: string;
  icon: string;
  perm: Permission;
  of: 'workflow' | 'run';
}[] = [
  {
    key: 'start',
    icon: 'add',
    label: 'Start a new run',
    perm: 'startRuns',
    of: 'workflow',
  },
  {
    key: 'pause',
    icon: 'pause',
    label: 'Pause',
    perm: 'controlRuns',
    of: 'run',
  },
  {
    key: 'resume',
    icon: 'play_arrow',
    label: 'Resume',
    perm: 'controlRuns',
    of: 'run',
  },
  { key: 'stop', icon: 'stop', label: 'Stop', perm: 'controlRuns', of: 'run' },
  {
    key: 'restart',
    icon: 'replay',
    label: 'Restart',
    perm: 'controlRuns',
    of: 'run',
  },
  {
    key: 'priority',
    icon: 'low_priority',
    label: 'Change priority',
    perm: 'controlRuns',
    of: 'run',
  },
  {
    key: 'recover',
    icon: 'restore',
    label: 'Recover',
    perm: 'controlRuns',
    of: 'run',
  },
];

/**
 * What you can do: the commands you may send, then the ones you may not, each with its reason.
 * A command is refused when the supervisor that owns it does not let people send it, or when your
 * role is too low; otherwise it is allowed, directly or through a supervisor.
 */
function Commands({
  workflow,
  run,
  hasRun,
}: {
  workflow: Record<string, Capability> | undefined;
  run: Record<string, Capability> | undefined;
  hasRun: boolean;
}) {
  const me = useMe();
  const rows = COMMANDS.flatMap((command) => {
    const capability = (command.of === 'run' ? run : workflow)?.[command.key];
    if (!capability) return [];
    const roleBlocked = Boolean(me && !me.permissions[command.perm]);
    let detail = '';
    if (!capability.allowed) {
      detail = capability.reason ?? controlledBy(capability.via);
    } else if (roleBlocked) {
      detail = `Needs ${REQUIRED_ROLE[command.perm]}; your key is ${me?.role}.`;
    }
    return [
      {
        ...command,
        ok: capability.allowed && !roleBlocked,
        via: capability.via,
        detail,
      },
    ];
  });
  const can = rows.filter((row) => row.ok);
  const cannot = rows.filter((row) => !row.ok);
  return (
    <section aria-label="Run commands" className="flex flex-col gap-2">
      <div className="flex items-baseline justify-between gap-2">
        <h3 className="text-overline text-on-surface-variant">
          What you can do with this run
        </h3>
        {rows.length > 0 && (
          <span className="text-label-sm text-on-surface-variant">
            {can.length} of {rows.length} allowed
          </span>
        )}
      </div>
      {rows.length === 0 && (
        <p className="text-body-sm text-on-surface-variant">
          {hasRun
            ? 'The server did not say which commands are allowed.'
            : 'Open a run to see which commands you can send to it.'}
        </p>
      )}
      {can.length > 0 && (
        <div className="flex flex-col gap-1.5">
          <ul className="flex flex-wrap gap-1.5">
            {can.map(({ key, label, icon, via }) => (
              <li
                key={key}
                className="border-success/30 bg-success/5 hover:bg-success/10 transition-standard flex size-12 items-center justify-center rounded-lg border"
              >
                <Tip
                  label={via === 'default' ? label : `${label} · via ${via}`}
                >
                  <span className="bg-success/15 text-success flex size-7 items-center justify-center rounded-md">
                    <span
                      aria-hidden="true"
                      className="material-symbols-outlined text-base"
                    >
                      {icon}
                    </span>
                    <span className="sr-only">Allowed: </span>
                    <span className="sr-only">{label}</span>
                    {via !== 'default' && (
                      <span className="sr-only"> via {via}</span>
                    )}
                  </span>
                </Tip>
              </li>
            ))}
          </ul>
        </div>
      )}
      {cannot.length > 0 && (
        <div className="flex flex-col gap-1.5">
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
        </div>
      )}
    </section>
  );
}

/** Node names as chips, with a search once there are many. */
function NodeList({
  ids,
  onNode,
}: {
  ids: string[];
  /** Centers the graph on a node when its chip is pressed. */
  onNode?: (nodeId: string) => void;
}) {
  const [query, setQuery] = useState('');
  const needle = query.trim().toLowerCase();
  const shown = ids.filter((id) => id.toLowerCase().includes(needle));
  return (
    <div className="flex flex-col gap-3">
      {ids.length > 12 && (
        <input
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Find a node…"
          aria-label="Find a node"
          className={inputFieldClassName}
        />
      )}
      <ul className="flex flex-wrap gap-1.5">
        {shown.map((id) => (
          <li key={id}>
            {onNode ? (
              <button
                type="button"
                onClick={() => onNode(id)}
                className="text-code-sm border-border bg-surface-container-low text-on-surface transition-standard hover:border-primary/40 hover:bg-primary/5 inline-flex cursor-pointer items-center gap-1.5 rounded-lg border px-2 py-1"
              >
                <span
                  aria-hidden="true"
                  className="bg-primary/60 size-1.5 shrink-0 rounded-full"
                />
                {id}
              </button>
            ) : (
              <span className="text-code-sm border-border bg-surface-container-low text-on-surface transition-standard inline-flex items-center gap-1.5 rounded-lg border px-2 py-1">
                <span
                  aria-hidden="true"
                  className="bg-primary/60 size-1.5 shrink-0 rounded-full"
                />
                {id}
              </span>
            )}
          </li>
        ))}
      </ul>
      {shown.length === 0 && (
        <p className="text-body-sm text-on-surface-variant">
          No node matches “{query}”.
        </p>
      )}
    </div>
  );
}

const count = (n: number) => `${n} ${n === 1 ? 'node' : 'nodes'}`;

/**
 * One controller of the workflow: the supervisor card as the node tab draws it (its type, what it
 * is for, what it reports to), then what it controls: the run itself and/or nodes, the nodes
 * behind a collapsed row that centers the graph on one when pressed.
 */
export function Controller({
  owner,
  supervisor,
  typeName,
  runLevel,
  ids,
  onNode,
}: {
  owner: string;
  supervisor: SupervisorView | undefined;
  typeName: string | undefined;
  runLevel: boolean;
  ids: string[];
  onNode?: (nodeId: string) => void;
}) {
  const controls = [
    runLevel && 'the whole run',
    ids.length > 0 && count(ids.length),
  ]
    .filter(Boolean)
    .join(' and ');
  const line = `Controls ${controls}`;
  const [open, setOpen] = useState(true);
  return (
    <SupervisorCard
      owner={owner}
      supervisor={supervisor}
      typeName={typeName}
      label={runLevel ? 'Supervised by' : 'Also controlling'}
      scopeIcon={runLevel ? 'account_tree' : 'hub'}
      scope={
        ids.length > 0 ? (
          // a fixed-height row: the icon stays put when the nodes open below it
          <button
            type="button"
            aria-expanded={open}
            onClick={() => setOpen((value) => !value)}
            className="flex w-full cursor-pointer items-center justify-between gap-1 text-left"
          >
            <span>{line}</span>
            <span
              aria-hidden="true"
              className={cn(
                'material-symbols-outlined text-on-surface-variant transition-transform',
                open && 'rotate-180'
              )}
            >
              expand_more
            </span>
          </button>
        ) : (
          line
        )
      }
    >
      {ids.length > 0 && open && (
        <div className="pt-1">
          <NodeList ids={ids} onNode={onNode} />
        </div>
      )}
    </SupervisorCard>
  );
}

/** Who controls the run and its nodes, and what you can do with the run. */
export function SupervisionPanel({
  sessionId,
  workflowId,
  executionId,
  onNode,
}: {
  sessionId: string;
  workflowId: string;
  executionId: string | null;
  /** Centers the graph on a node when its chip is pressed. */
  onNode?: (nodeId: string) => void;
}) {
  const ownership = useOwnership(sessionId, workflowId);
  const run = useSupervision({ execution: executionId }, Boolean(executionId));
  const start = useSupervision({ session: sessionId, workflow: workflowId });
  const supervisors = useSupervisors();
  const types = useSupervisorTypes();
  if (ownership.isPending) {
    return <StateMessage tone="loading" title="Loading who owns what…" />;
  }
  if (ownership.error) {
    return (
      <StateMessage tone="error" title="Could not load who owns this workflow.">
        The run controls use your role only; the server still checks every
        command.
      </StateMessage>
    );
  }
  const { run: runOwner, nodes, defaultSupervisor } = ownership.data;
  const byNodes = byOwner(nodes);
  const nobody = !defaultSupervisor && runOwner === 'default';
  const idsOf = (owner: string) =>
    byNodes.find(([o]) => o === owner)?.[1] ?? [];
  // the run's controller on top, then those that only control nodes
  const others = byNodes.map(([owner]) => owner).filter((o) => o !== runOwner);
  const controller = (owner: string) => {
    const supervisor = supervisors.data?.find((s) => s.id === owner);
    return (
      <Controller
        key={owner}
        owner={owner}
        supervisor={supervisor}
        typeName={
          types.data?.find((t) => t.type === supervisor?.type)?.displayName
        }
        runLevel={owner === runOwner}
        ids={idsOf(owner)}
        onNode={onNode}
      />
    );
  };
  return (
    <div className="flex flex-col gap-4">
      {nobody ? (
        <Callout kind="warning" title="Nobody can control this workflow">
          Direct control is off and no supervisor owns {workflowId}. An ADMIN
          can turn Direct control on or assign a supervisor.
        </Callout>
      ) : (
        <section aria-label="Run controller" className="flex flex-col gap-2">
          <h3 className="text-overline text-on-surface-variant">
            Run controlled by
          </h3>
          {controller(runOwner)}
        </section>
      )}
      <Commands
        workflow={start.data}
        run={run.data}
        hasRun={Boolean(executionId)}
      />
      {others.length > 0 && (
        <section aria-label="Other controllers" className="flex flex-col gap-2">
          <h3 className="text-overline text-on-surface-variant">
            {others.length === 1
              ? 'Also controlling nodes'
              : `${others.length} others controlling nodes`}
          </h3>
          {others.map(controller)}
        </section>
      )}
    </div>
  );
}

/** The newest actions on this run (or, with no run chosen, on this workflow) from every supervisor. */
function RunActions({
  workflowId,
  executionId,
}: {
  workflowId: string;
  executionId: string | null;
}) {
  const decisions = useSupervisorDecisions();
  const actions = (decisions.data ?? [])
    .filter((d) =>
      executionId ? d.executionId === executionId : d.workflowId === workflowId
    )
    .slice(0, 50);
  return (
    <ActionsCard
      title={`Actions taken on this ${executionId ? 'run' : 'workflow'}`}
      actions={actions}
      loading={decisions.isPending}
      empty="No supervisor has acted here yet."
      showSupervisor
    />
  );
}

/**
 * Who controls this workflow and what they did, as a side sheet over the progress panel (as the
 * node sheet is); `?supervision=1` opens it (the toolbar, a node's owner icon, the palette, a link).
 * It is not modal: the graph and the run controls stay usable beside it.
 */
export function WorkflowSupervision({
  sessionId,
  workflowId,
  executionId,
  onNode,
}: {
  sessionId: string;
  workflowId: string;
  executionId: string | null;
  /** Centers the graph on a node when its chip is pressed. */
  onNode?: (nodeId: string) => void;
}) {
  const [params, setParams] = useSearchParams();
  const open = params.has('supervision');
  const close = () => {
    const next = new URLSearchParams(params);
    next.delete('supervision');
    setParams(next, { replace: true });
  };
  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      const next = new URLSearchParams(params);
      next.delete('supervision');
      setParams(next, { replace: true });
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, params, setParams]);
  if (!open) return null;
  return (
    <aside
      role="dialog"
      aria-modal="false"
      aria-label="Who controls this workflow"
      data-testid="supervision-sheet"
      className="border-border bg-background animate-in fade-in-0 slide-in-from-right-4 absolute inset-0 z-10 flex flex-col gap-4 overflow-y-auto border-l p-4 duration-150"
    >
      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <Tile icon="shield_person" tone="primary" />
          <div className="min-w-0">
            <h2 className="text-headline-md text-on-surface">Supervision</h2>
            <p className="text-label-md text-on-surface-variant">
              Who controls this {executionId ? 'run' : 'workflow'}
            </p>
          </div>
        </div>
        <button
          type="button"
          onClick={close}
          aria-label="Close supervision"
          className="text-on-surface-variant transition-standard hover:bg-muted hover:text-on-surface flex size-8 shrink-0 items-center justify-center rounded-lg"
        >
          <span aria-hidden="true" className="material-symbols-outlined">
            close
          </span>
        </button>
      </div>
      <SupervisionPanel
        sessionId={sessionId}
        workflowId={workflowId}
        executionId={executionId}
        onNode={onNode}
      />
      <RunActions workflowId={workflowId} executionId={executionId} />
    </aside>
  );
}
