// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import { REQUIRED_ROLE, useMe, type Permission } from '@/lib/identity';
import type { Capability } from '../api/supervision';
import type { SupervisorView } from '../types';
import { Card } from '@/components/ui/card';
import { inputFieldClassName } from '@/components/ui/native-select';
import { Callout } from '@/components/common/Callout';
import { Modal } from '@/components/common/Modal';
import { iconButton } from '@/components/ui/iconTone';
import { Tip } from '@/components/ui/tooltip';
import { StateMessage } from '@/components/common/StateMessage';
import {
  useOwnership,
  useSupervision,
  useSupervisors,
  useSupervisorTypes,
} from '../hooks/useSupervisorTypes';
import { ownerLabel } from '../lib/ownership';
import { ConflictsBanner } from './ConflictsBanner';
import { controlledBy } from './RequireControl';

const supervisorLink = (owner: string) =>
  owner === 'default' ? (
    'Default (people)'
  ) : (
    <Link
      to={`/supervisors?supervisor=${encodeURIComponent(owner)}`}
      className="text-primary"
    >
      {owner}
    </Link>
  );

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

/** A small panel with an overline label, the unit of the dialog. */
function Panel({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Card className="gap-2 p-4">
      <h3 className="text-overline text-on-surface-variant">{label}</h3>
      {children}
    </Card>
  );
}

/** The commands of a run in the order the run controls show them, with the role each needs. */
const COMMANDS: {
  key: string;
  label: string;
  perm: Permission;
  of: 'workflow' | 'run';
}[] = [
  { key: 'start', label: 'Start a new run', perm: 'startRuns', of: 'workflow' },
  { key: 'pause', label: 'Pause', perm: 'controlRuns', of: 'run' },
  { key: 'resume', label: 'Resume', perm: 'controlRuns', of: 'run' },
  { key: 'stop', label: 'Stop', perm: 'controlRuns', of: 'run' },
  { key: 'restart', label: 'Restart', perm: 'controlRuns', of: 'run' },
  { key: 'priority', label: 'Change priority', perm: 'controlRuns', of: 'run' },
  { key: 'recover', label: 'Recover', perm: 'controlRuns', of: 'run' },
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
    <Panel label="What you can do with this run">
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
            {can.map(({ key, label, via }) => (
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
                {via !== 'default' && (
                  <span className="text-on-surface-variant font-normal">
                    via {via}
                  </span>
                )}
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
    </Panel>
  );
}

/** Node names as chips, with a search once there are many. */
function NodeList({ ids }: { ids: string[] }) {
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
          <li
            key={id}
            className="text-code-sm border-border bg-muted/50 text-on-surface rounded-full border px-2 py-0.5"
          >
            {id}
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

/** A small rounded label next to a name. */
function Pill({
  children,
  tone = 'muted',
}: {
  children: ReactNode;
  tone?: 'muted' | 'attention';
}) {
  const color =
    tone === 'attention'
      ? 'border-attention/30 bg-attention/10 text-attention'
      : 'border-border bg-muted text-on-surface-variant';
  return (
    <span className={`text-label-sm rounded-full border px-2 py-px ${color}`}>
      {children}
    </span>
  );
}

const count = (n: number) => `${n} ${n === 1 ? 'node' : 'nodes'}`;

/**
 * One controller of the workflow: the supervisor as the server describes it (its type, what it
 * is for, whether it only records, what it reports to), then what it controls: the run itself
 * and/or nodes, the nodes behind a collapsed row.
 */
function Controller({
  owner,
  supervisor,
  typeName,
  runLevel,
  ids,
}: {
  owner: string;
  supervisor: SupervisorView | undefined;
  typeName: string | undefined;
  runLevel: boolean;
  ids: string[];
}) {
  const parents = supervisor?.parents ?? [];
  const controls = [
    runLevel && 'the whole run',
    ids.length > 0 && count(ids.length),
  ]
    .filter(Boolean)
    .join(' and ');
  const line = (
    <span className="text-body-sm text-on-surface-variant">
      Controls {controls}
    </span>
  );
  return (
    <Card className="gap-3 p-4">
      <div className="flex items-start gap-3">
        <Tile
          icon={owner === 'default' ? 'person' : 'shield_person'}
          tone="primary"
        />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <span className="text-body-md text-on-surface font-medium">
              {supervisorLink(owner)}
            </span>
            {typeName && <Pill>{typeName}</Pill>}
            {supervisor?.dryRun && (
              <Tip label="Records the commands it would send and applies none">
                <span>
                  <Pill tone="attention">Dry run</Pill>
                </span>
              </Tip>
            )}
            {supervisor?.system && <Pill>Built in</Pill>}
          </div>
          {supervisor?.description && (
            <p className="text-body-sm text-on-surface-variant">
              {supervisor.description}
            </p>
          )}
          {parents.length > 0 && (
            <p className="text-body-sm text-on-surface-variant">
              Reports to{' '}
              {parents.map((parent, index) => (
                <span key={parent}>
                  {index > 0 && ', '}
                  {supervisorLink(parent)}
                </span>
              ))}
            </p>
          )}
        </div>
      </div>
      {ids.length === 0 ? (
        line
      ) : (
        <details className="group">
          <summary className="flex cursor-pointer list-none items-center gap-1">
            {line}
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-on-surface-variant transition-transform group-open:rotate-180"
            >
              expand_more
            </span>
          </summary>
          <div className="pt-3">
            <NodeList ids={ids} />
          </div>
        </details>
      )}
    </Card>
  );
}

/** Who controls the run and its nodes, and what you can do with the run. */
export function SupervisionPanel({
  sessionId,
  workflowId,
  executionId,
}: {
  sessionId: string;
  workflowId: string;
  executionId: string | null;
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
      />
    );
  };
  return (
    <div className="flex flex-col gap-4">
      <ConflictsBanner session={sessionId} workflow={workflowId} />
      {nobody ? (
        <Callout kind="warning" title="Nobody can control this workflow">
          Default (people) is off and no supervisor owns {workflowId}. An ADMIN
          can turn Default on or assign a supervisor.
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

/** The run's owner on the workflow page, opening who owns what; `?supervision=1` opens it at once. */
export function WorkflowSupervision({
  sessionId,
  workflowId,
  executionId,
}: {
  sessionId: string;
  workflowId: string;
  executionId: string | null;
}) {
  // the dialog lives in the URL, so the palette (and a link) can open it on a page already shown
  const [params, setParams] = useSearchParams();
  const open = params.has('supervision');
  const show = (yes: boolean) => {
    const next = new URLSearchParams(params);
    if (yes) next.set('supervision', '1');
    else next.delete('supervision');
    setParams(next, { replace: !yes });
  };
  const { data } = useOwnership(sessionId, workflowId);
  return (
    <>
      <Tip label={`Controlled by: ${ownerLabel(data?.run)}`}>
        <button
          type="button"
          onClick={() => show(true)}
          aria-label={`Run owner: ${ownerLabel(data?.run)}`}
          className={iconButton('primary')}
        >
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-base"
          >
            shield_person
          </span>
        </button>
      </Tip>
      {open && (
        <Modal
          title="Who controls this workflow"
          onClose={() => show(false)}
          wide
        >
          <SupervisionPanel
            sessionId={sessionId}
            workflowId={workflowId}
            executionId={executionId}
          />
        </Modal>
      )}
    </>
  );
}
