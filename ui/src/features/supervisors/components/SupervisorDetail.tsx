// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { buttonVariants } from '@/components/ui/button';
import { CodeBlock } from '@/components/common/CodeBlock';
import { StateMessage } from '@/components/common/StateMessage';
import { KpiTile } from '@/components/dashboard/KpiTile';
import { Tabs } from '@/components/ui/tabs';
import { formatRelative } from '@/features/workflow/lib/timeFormat';
import { getSupervisionSummary } from '../api/supervision';
import {
  useAttachments,
  useOwnership,
  useSupervisorDecisions,
  useSupervisorTypes,
} from '../hooks/useSupervisorTypes';
import { decisionMaker } from '../lib/decisionMakers';
import { directControl } from '../lib/directControl';
import { parseScope } from '../lib/ownership';
import type { SupervisorView } from '../types';
import { Activity } from './Activity';
import { CredentialPanel, ExternalFlow } from './ExternalSettings';
import { HistoryTab, SettingsTab } from './SettingsHistoryTabs';
import { SupervisionMap } from './SupervisionMap';
import { HierarchyTab, OperationsTab, OwnsTab } from './SupervisorDetailTabs';

export const DETAIL_TABS = [
  'owns',
  'operations',
  'hierarchy',
  'decision-maker',
  'settings',
  'changes',
  'decisions',
] as const;
export type DetailTab = (typeof DETAIL_TABS)[number];

const LABEL: Record<DetailTab, string> = {
  owns: 'Owns',
  operations: 'Allowed operations',
  hierarchy: 'Hierarchy',
  'decision-maker': 'Decision maker',
  settings: 'Settings',
  changes: 'Changes',
  decisions: 'Decisions',
};

const BUCKETS = 24;

/** Edit link for the supervisor, and the agent or CLI commands for changes made outside the UI. */
function ChangeSupervisor({ id }: { id: string }) {
  return (
    <div className="flex flex-col gap-3">
      <div>
        <Link
          to={`/supervisors/${encodeURIComponent(id)}/edit`}
          className={buttonVariants({ variant: 'outline', size: 'sm' })}
        >
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-base"
          >
            edit
          </span>
          Edit supervisor
        </Link>
      </div>
      <details className="text-body-sm text-on-surface-variant">
        <summary className="cursor-pointer select-none">
          Other ways to change it
        </summary>
        <div className="mt-2">
          <CodeBlock
            label="Commands"
            code={[
              `MCP  save_supervisor {"id": "${id}", …}`,
              `CLI  yukta supervisors show ${id}`,
              `CLI  yukta supervisors assign ${id} <target>`,
              `API  GET /api/supervisors/${id}`,
            ].join('\n')}
          />
        </div>
      </details>
    </div>
  );
}

/** The name, what decides, its state, and the description: the same for every supervisor. */
function DetailHeader({
  title,
  engine,
  color,
  state,
  system,
  description,
  actions,
}: {
  title: string;
  engine: string;
  color: string;
  state?: 'live' | 'dry-run';
  system?: boolean;
  description?: string | null;
  actions?: ReactNode;
}) {
  return (
    <header className="flex flex-col gap-2">
      <h2 className="text-headline-lg text-on-surface">{title}</h2>
      <div className="text-body-md text-on-surface-variant flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="inline-flex items-center gap-2">
          <span
            aria-hidden="true"
            className="size-2 rounded-full"
            style={{ background: color }}
          />
          <code className="text-code-sm select-all">{engine}</code>
        </span>
        {state === 'dry-run' && (
          <span className="text-attention inline-flex items-center gap-1">
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-base"
            >
              filter_alt
            </span>
            Dry run: recorded, not applied
          </span>
        )}
        {state === 'live' && (
          <span className="text-success inline-flex items-center gap-1">
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-base"
            >
              check_circle
            </span>
            Live
          </span>
        )}
        {system && <span>Built in (read only)</span>}
      </div>
      {description && (
        <p className="text-body-md text-on-surface">{description}</p>
      )}
      {actions}
    </header>
  );
}

/** Decisions per bucket over the span they cover, for the sparkline. */
function decisionSeries(times: number[]) {
  if (times.length < 2) return [];
  const from = Math.min(...times);
  const step = Math.max(1, (Math.max(...times) - from) / BUCKETS);
  const buckets = Array.from({ length: BUCKETS }, () => 0);
  times.forEach((t) => {
    buckets[Math.min(BUCKETS - 1, Math.floor((t - from) / step))]++;
  });
  return buckets;
}

function KpiRow({
  supervisor,
  onOwns,
}: {
  supervisor: SupervisorView;
  onOwns: () => void;
}) {
  const decisions = useSupervisorDecisions(supervisor.id);
  const owned = useAttachments({ supervisor: supervisor.id });
  const times = (decisions.data ?? []).map((d) => Date.parse(d.time));
  const workflows = new Set(
    (owned.data ?? [])
      .map((a) => parseScope(a.scope))
      .filter((t) => t.workflowId)
      .map((t) => `${t.sessionId}/${t.workflowId}`)
  ).size;
  const newest = decisions.data?.[0]?.time;
  return (
    <section
      aria-label="At a glance"
      className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5"
    >
      <KpiTile
        label="Applied"
        value={String(supervisor.applied)}
        series={decisionSeries(times)}
      />
      <KpiTile label="Refused" value={String(supervisor.rejected)} />
      <KpiTile label="Rate limited" value={String(supervisor.rateLimited)} />
      <div className="border-border bg-card flex min-w-0 flex-col gap-1 rounded-xl border p-4">
        <span className="text-label-md text-on-surface-variant">Owns</span>
        <span className="text-body-md">
          {workflows} {workflows === 1 ? 'workflow' : 'workflows'} ·{' '}
          <button
            type="button"
            className="text-primary font-semibold"
            title="A target is a workflow, node or session this supervisor is assigned to"
            onClick={onOwns}
          >
            {supervisor.attachments}{' '}
            {supervisor.attachments === 1 ? 'target' : 'targets'}
          </button>
        </span>
      </div>
      <KpiTile
        label="Last decision"
        value={newest ? formatRelative(newest) : '–'}
      />
    </section>
  );
}

/** The one decision maker of this supervisor, as an instance: its settings live under Settings. */
function DecisionMakerTab({ supervisor }: { supervisor: SupervisorView }) {
  const types = useSupervisorTypes();
  const maker = decisionMaker(supervisor.type, types.data);
  return (
    <div className="flex flex-col gap-3">
      <div className="border-border bg-card text-body-md shadow-card flex flex-col gap-2 rounded-xl border p-4">
        <p className="flex items-center gap-2">
          <span
            aria-hidden="true"
            className="material-symbols-outlined"
            style={{ color: maker.color }}
          >
            {maker.icon}
          </span>
          decides with <b>{maker.name}</b>{' '}
          <code className="text-code-sm">{supervisor.type}</code>
        </p>
        <p className="text-on-surface-variant">
          It controls only the workflows this supervisor owns. To change its
          type, edit the supervisor; its settings are under Settings.
        </p>
        <div>
          <Link
            to={`/supervisors?tab=decision-makers&type=${encodeURIComponent(supervisor.type)}`}
            className={buttonVariants({ variant: 'outline', size: 'sm' })}
          >
            Browse decision maker types
          </Link>
        </div>
      </div>
      {supervisor.type === 'http' && (
        <>
          <ExternalFlow url={supervisor.settings.url} />
          <CredentialPanel id={supervisor.id} />
        </>
      )}
    </div>
  );
}

/** One configured supervisor: header, key numbers, and the tabs. */
export function SupervisorDetail({
  supervisor,
  all,
  onSelect,
  tab,
  setTab,
}: {
  supervisor: SupervisorView;
  all: SupervisorView[];
  onSelect: (id: string) => void;
  tab: DetailTab;
  setTab: (tab: DetailTab) => void;
}) {
  const types = useSupervisorTypes();
  const maker = decisionMaker(supervisor.type, types.data);
  const decisions = useSupervisorDecisions(supervisor.id);
  return (
    <article className="mx-auto flex max-w-5xl min-w-0 flex-col gap-5">
      <DetailHeader
        title={supervisor.id}
        engine={supervisor.type}
        color={maker.color}
        state={supervisor.dryRun ? 'dry-run' : 'live'}
        system={supervisor.system}
        description={supervisor.description}
      />
      <KpiRow supervisor={supervisor} onOwns={() => setTab('owns')} />
      <SupervisionMap
        supervisor={supervisor}
        all={all}
        onSelect={onSelect}
        onMore={() => setTab('owns')}
      />
      <Tabs
        ariaLabel={`${supervisor.id} details`}
        items={DETAIL_TABS.map((id) => ({
          id,
          label: LABEL[id],
          count:
            id === 'owns'
              ? supervisor.attachments
              : id === 'decisions' && decisions.data
                ? decisions.data.length
                : undefined,
        }))}
        value={tab}
        onChange={setTab}
      />
      <div role="tabpanel" aria-label={LABEL[tab]}>
        {tab === 'owns' && <OwnsTab supervisor={supervisor} />}
        {tab === 'operations' && <OperationsTab supervisor={supervisor} />}
        {tab === 'hierarchy' && (
          <HierarchyTab supervisor={supervisor} all={all} onSelect={onSelect} />
        )}
        {tab === 'decision-maker' && (
          <DecisionMakerTab supervisor={supervisor} />
        )}
        {tab === 'settings' && <SettingsTab supervisor={supervisor} />}
        {tab === 'changes' && <HistoryTab supervisor={supervisor} />}
        {tab === 'decisions' && <Activity supervisor={supervisor.id} />}
      </div>
      {!supervisor.system && <ChangeSupervisor id={supervisor.id} />}
    </article>
  );
}

const DIRECT_TABS = ['owns', 'operations', 'decision-maker'] as const;

/** Direct control in the same template: what nobody owns, and people and agents deciding. */
export function DirectControlDetail({
  tab,
  setTab,
}: {
  tab: DetailTab;
  setTab: (tab: DetailTab) => void;
}) {
  const summary = useQuery({
    queryKey: ['supervision', 'summary'],
    queryFn: getSupervisionSummary,
  });
  const first = summary.data?.unownedWorkflows[0];
  // the setting `yukta.supervisor.default.enabled` shows as the ownership map's flag
  const ownership = useOwnership(
    first?.sessionId ?? null,
    first?.workflowId ?? null
  );
  if (summary.isPending) {
    return <StateMessage tone="loading" title="Loading…" />;
  }
  if (summary.error || !summary.data) {
    return (
      <StateMessage tone="error" title="Could not load what nobody owns." />
    );
  }
  const direct = directControl(summary.data);
  const refused = ownership.data?.defaultSupervisor === false;
  const shown = DIRECT_TABS.includes(tab as (typeof DIRECT_TABS)[number])
    ? tab
    : 'owns';
  return (
    <article className="mx-auto flex max-w-5xl min-w-0 flex-col gap-5">
      <DetailHeader
        title="Direct control"
        engine="default"
        color="var(--muted-foreground)"
        description="Controls the workflows that no supervisor owns. Each run and node listed here is controlled directly, by people or agents."
      />
      <Tabs
        ariaLabel="Direct control details"
        items={DIRECT_TABS.map((id) => ({
          id,
          label: LABEL[id],
          count: id === 'owns' ? direct.owned.length : undefined,
        }))}
        value={shown}
        onChange={setTab}
      />
      <div role="tabpanel" aria-label={LABEL[shown]}>
        {shown === 'owns' &&
          (direct.owned.length === 0 ? (
            <StateMessage title="Nothing is unowned" icon="check_circle">
              Every workflow has a supervisor.
            </StateMessage>
          ) : (
            <ul className="border-border bg-card shadow-card divide-border flex flex-col divide-y rounded-xl border">
              {direct.owned.map((w) => (
                <li key={`${w.sessionId}/${w.workflowId}`} className="p-3">
                  <Link
                    to={`/sessions/${encodeURIComponent(w.sessionId)}/workflow/${encodeURIComponent(w.workflowId)}`}
                    className="text-label-md text-primary"
                  >
                    {w.sessionId} / {w.workflowId}
                  </Link>
                </li>
              ))}
            </ul>
          ))}
        {shown === 'operations' && (
          <p className="text-body-md">
            {refused
              ? 'Direct control is switched off on this server: a command on a node nobody owns is refused (HTTP 409).'
              : 'Everything: people and agents may send any command to a run or node nobody owns.'}
          </p>
        )}
        {shown === 'decision-maker' && (
          <p className="text-body-md flex items-center gap-2">
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-muted-foreground"
            >
              {direct.decisionMaker.icon}
            </span>
            decides with <b>{direct.decisionMaker.name}</b>: the inbox, the
            control bar and the API.
          </p>
        )}
      </div>
    </article>
  );
}
