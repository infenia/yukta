// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
import { cn } from '@/lib/utils';
import { Tip } from '@/components/ui/tooltip';
import { formatDateTime } from '@/features/workflow/lib/timeFormat';
import type { Decision } from '../types';

/** `PauseWorkflowCommand` as `Pause`. */
const commandName = (command: string) =>
  command.replace(/(Workflow|Node)?Command$/, '');

const DOT_TONE = {
  success: 'bg-success',
  attention: 'bg-attention',
  warning: 'bg-warning',
  muted: 'bg-muted-foreground/50',
} as const;

/** The dot on a timeline's line: green for applied, orange for refused, grey for recorded only. */
export function TimelineDot({ tone }: { tone: keyof typeof DOT_TONE }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'absolute top-3 -left-[4.5px] size-2 rounded-full',
        DOT_TONE[tone]
      )}
    />
  );
}

/**
 * A node name as a small mono chip. With `onClick` it is a button; `mine` marks the nodes of the
 * supervisor being looked at, and `owner` names who owns it when that is someone else.
 */
export function NodeChip({
  id,
  mine,
  owner,
  onClick,
}: {
  id: string;
  mine?: boolean;
  owner?: string;
  onClick?: () => void;
}) {
  const tone = cn(
    'text-code-sm transition-standard inline-flex items-center gap-1.5 rounded-lg border px-2 py-1',
    mine === false
      ? 'border-border bg-surface-container-low text-on-surface-variant'
      : mine
        ? 'border-primary/40 bg-primary/10 text-on-surface'
        : 'border-border bg-surface-container-low text-on-surface'
  );
  const dot = (
    <span
      aria-hidden="true"
      className={cn(
        'size-1.5 shrink-0 rounded-full',
        mine === false ? 'bg-muted-foreground/50' : 'bg-primary/60'
      )}
    />
  );
  const chip = onClick ? (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        tone,
        'hover:border-primary/40 hover:bg-primary/5 cursor-pointer'
      )}
    >
      {dot}
      {id}
    </button>
  ) : (
    <span className={tone}>
      {dot}
      {id}
    </span>
  );
  return owner ? <Tip label={`Owned by ${owner}`}>{chip}</Tip> : chip;
}

/**
 * What supervisors did, newest first, one line per action: the command, where it went, the
 * outcome and the reason. `showSupervisor` names who did it, for a list that mixes supervisors.
 */
export function ActionsTimeline({
  actions,
  loading,
  empty,
  showSupervisor = false,
  nodeLabel = (d) => (d.nodeId ? `node ${d.nodeId}` : 'whole run'),
}: {
  actions: Decision[];
  loading: boolean;
  /** Said when there are no actions. */
  empty: string;
  showSupervisor?: boolean;
  /** Where an action went; a node's own tab says "this node". */
  nodeLabel?: (action: Decision) => string;
}) {
  return (
    <>
      {actions.length === 0 && (
        <p className="text-body-sm text-on-surface-variant">
          {loading ? 'Loading…' : empty}
        </p>
      )}
      <ol className="border-border flex flex-col border-l">
        {actions.map((d) => (
          <li
            key={`${d.time}-${d.supervisorId}-${d.command}-${d.executionId}-${d.nodeId ?? ''}`}
            className="relative flex flex-col gap-0.5 py-1.5 pl-3.5"
          >
            <TimelineDot
              tone={d.outcome === 'applied' ? 'success' : 'attention'}
            />
            <div className="flex items-baseline justify-between gap-2">
              <span className="text-body-md text-on-surface font-medium">
                {commandName(d.command)}
                <span className="text-label-sm text-on-surface-variant ml-1.5 font-normal">
                  {nodeLabel(d)} · {d.outcome}
                </span>
              </span>
              <span className="text-label-sm text-on-surface-variant shrink-0">
                {formatDateTime(d.time)}
              </span>
            </div>
            {showSupervisor && (
              <Link
                to={`/supervisors?supervisor=${encodeURIComponent(d.supervisorId)}`}
                className="text-label-sm text-primary w-fit"
              >
                {d.supervisorId}
              </Link>
            )}
            {(d.because ?? d.reason) && (
              <p className="text-body-sm text-on-surface-variant">
                {d.because ?? d.reason}
              </p>
            )}
          </li>
        ))}
      </ol>
    </>
  );
}

/**
 * The actions section of a supervision view: a card with its title and the count, then the
 * timeline, or the line that says nothing has happened yet. The run sheet and the node tab share it.
 */
export function ActionsCard({
  title,
  actions,
  loading,
  empty,
  showSupervisor,
  nodeLabel,
}: {
  title: string;
  actions: Decision[];
  loading: boolean;
  empty: string;
  showSupervisor?: boolean;
  nodeLabel?: (action: Decision) => string;
}) {
  return (
    <section
      aria-label="Supervisor actions"
      className="border-border bg-muted/30 flex flex-col gap-2 rounded-xl border p-3"
    >
      <div className="flex items-baseline justify-between gap-2">
        <h3 className="text-overline text-on-surface-variant">{title}</h3>
        {actions.length > 0 && (
          <span className="text-label-sm text-on-surface-variant">
            {actions.length} {actions.length === 1 ? 'action' : 'actions'}
          </span>
        )}
      </div>
      <ActionsTimeline
        actions={actions}
        loading={loading}
        empty={empty}
        showSupervisor={showSupervisor}
        nodeLabel={nodeLabel}
      />
    </section>
  );
}
