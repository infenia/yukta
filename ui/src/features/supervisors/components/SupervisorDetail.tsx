// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, type ReactNode } from 'react';
import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { IconLink } from '@/components/common/IconButton';
import { CodeBlock } from '@/components/common/CodeBlock';
import { StateMessage } from '@/components/common/StateMessage';
import { Callout } from '@/components/common/Callout';
import {
  formatDateTime,
  formatRelative,
} from '@/features/workflow/lib/timeFormat';
import { getSupervisionSummary } from '../api/supervision';
import { getRules } from '../api/supervisors';
import {
  useOwnership,
  useSupervisorDecisions,
  useSupervisorTypes,
} from '../hooks/useSupervisorTypes';
import { decisionMaker } from '../lib/decisionMakers';
import { useOwnedNodes } from '../hooks/useOwnedNodes';
import { directControl } from '../lib/directControl';
import type { SupervisorView } from '../types';
import { Activity } from './Activity';
import { HistoryTab, SettingsTab } from './SettingsHistoryTabs';
import { SupervisionMap } from './SupervisionMap';
import { Chip, OwnerLink, SupervisorShell } from './SupervisorCard';
import { HierarchyTab, OperationsTab, OwnsTab } from './SupervisorDetailTabs';
import { Tip } from '@/components/ui/tooltip';

/** The parts of a supervisor's page; `?section=` scrolls to one. */
export const DETAIL_SECTIONS = [
  'owns',
  'operations',
  'hierarchy',
  'decision-maker',
  'changes',
  'decisions',
] as const;
export type DetailSection = (typeof DETAIL_SECTIONS)[number];

/** Scrolls to a section of the page when one is named in the URL; nothing otherwise. */
function useScrollToSection(section: DetailSection | null) {
  useEffect(() => {
    if (section) {
      document
        .getElementById(`section-${section}`)
        ?.scrollIntoView?.({ block: 'start' });
    }
  }, [section]);
}

/** A part of the page: an overline heading with an optional count, then its content. */
function Section({
  id,
  title,
  count,
  children,
}: {
  id: DetailSection;
  title: string;
  count?: number;
  children: ReactNode;
}) {
  return (
    <section
      id={`section-${id}`}
      aria-label={title}
      className="flex scroll-mt-20 flex-col gap-2"
    >
      <h3 className="text-overline text-on-surface-variant flex items-center gap-2">
        {title}
        {count !== undefined && <span className="tabular-nums">{count}</span>}
      </h3>
      {children}
    </section>
  );
}

/** The link to the edit page, in the header of the supervisor. */
function EditLink({ id }: { id: string }) {
  return (
    <IconLink
      to={`/supervisors/${encodeURIComponent(id)}/edit`}
      icon="edit"
      label="Edit supervisor"
      tone="primary"
    />
  );
}

/** The agent or CLI commands for changes made outside the UI. */
function OtherWays({ id }: { id: string }) {
  return (
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
  );
}

/** The header card of Direct control: the same shell as a supervisor's, without a supervisor behind it. */
function DirectHero({ description }: { description: string }) {
  return (
    <SupervisorShell direct className="p-4">
      <div className="relative flex items-start gap-3">
        <span
          aria-hidden="true"
          className="bg-muted text-on-surface-variant shadow-card ring-border flex size-12 shrink-0 items-center justify-center rounded-xl ring-1"
        >
          <span className="material-symbols-outlined text-[26px]!">person</span>
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-overline text-on-surface-variant">
            Direct control
          </p>
          <h2 className="text-headline-md text-on-surface">Direct control</h2>
          <p className="text-body-md text-on-surface mt-1 max-w-prose">
            {description}
          </p>
        </div>
      </div>
    </SupervisorShell>
  );
}

/** The five numbers of a supervisor, as one quiet strip inside its header card. */
function StatStrip({
  supervisor,
  onOwns,
}: {
  supervisor: SupervisorView;
  onOwns: () => void;
}) {
  const decisions = useSupervisorDecisions(supervisor.id);
  const owned = useOwnedNodes(supervisor.id);
  const newest = decisions.data?.[0]?.time;
  const stat = (label: string, tip: string, value: ReactNode) => (
    <div className="flex min-w-0 flex-col gap-0.5">
      <dt className="text-overline text-on-surface-variant">
        <Tip label={tip}>
          <span>{label}</span>
        </Tip>
      </dt>
      <dd className="text-headline-md num text-on-surface">{value}</dd>
    </div>
  );
  return (
    <section
      aria-label="At a glance"
      className="border-border/60 relative mt-3 border-t pt-3"
    >
      <dl className="grid grid-cols-2 gap-x-4 gap-y-3 sm:grid-cols-3 lg:grid-cols-5">
        {stat(
          'Applied',
          'Commands it sent that took effect',
          supervisor.applied
        )}
        {stat(
          'Refused',
          'Commands the server refused: not allowed, out of scope or not the owner',
          supervisor.rejected
        )}
        {stat(
          'Rate limited',
          'Commands held back because it sent too many too fast',
          supervisor.rateLimited
        )}
        {stat(
          'Owns',
          `A target is a workflow, node or session this supervisor is assigned to. It has ${supervisor.attachments} ${supervisor.attachments === 1 ? 'target' : 'targets'}.`,
          <span className="text-body-md">
            {owned.workflows} {owned.workflows === 1 ? 'workflow' : 'workflows'}{' '}
            ·{' '}
            <button
              type="button"
              className="text-primary font-semibold"
              onClick={onOwns}
            >
              {owned.count}
              {owned.partial ? '+' : ''} {owned.count === 1 ? 'node' : 'nodes'}
            </button>
          </span>
        )}
        {stat(
          'Last decision',
          newest ? formatDateTime(newest) : 'No decision yet',
          newest ? formatRelative(newest) : '–'
        )}
      </dl>
    </section>
  );
}

/** The supervisor card of the node tab and the run sheet, at page scale: who it is, its state, what it is for, and its numbers. */
function SupervisorHero({
  supervisor,
  typeName,
  onOwns,
  actions,
}: {
  supervisor: SupervisorView;
  typeName: string;
  onOwns: () => void;
  actions?: ReactNode;
}) {
  const parents = supervisor.parents ?? [];
  return (
    <SupervisorShell direct={false} className="p-4">
      <div className="relative flex items-start gap-3">
        <span
          aria-hidden="true"
          className="bg-primary/15 text-primary shadow-card ring-primary/30 flex size-12 shrink-0 items-center justify-center rounded-xl ring-1"
        >
          <span className="material-symbols-outlined text-[26px]!">
            shield_person
          </span>
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-overline text-on-surface-variant">Supervisor</p>
          <h2 className="text-headline-md text-on-surface break-all">
            {supervisor.id}
          </h2>
          <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
            <Chip>{typeName}</Chip>
            <code className="text-code-sm text-on-surface-variant select-all">
              {supervisor.type}
            </code>
            {supervisor.dryRun ? (
              <Chip tone="attention">Dry run</Chip>
            ) : (
              <Chip tone="success">Live</Chip>
            )}
            {supervisor.system && <Chip>Built in (read only)</Chip>}
            {parents.slice(0, 2).map((parent) => (
              <Chip key={parent}>
                Reports to <OwnerLink owner={parent} />
              </Chip>
            ))}
            {parents.length > 2 && <Chip>+{parents.length - 2}</Chip>}
          </div>
        </div>
        {actions && <div className="flex gap-2 max-sm:w-full">{actions}</div>}
      </div>
      {supervisor.description && (
        <p className="text-body-md text-on-surface relative mt-3 max-w-prose">
          {supervisor.description}
        </p>
      )}
      <StatStrip supervisor={supervisor} onOwns={onOwns} />
    </SupervisorShell>
  );
}

const text = (value: unknown) =>
  Array.isArray(value) ? value.join(', ') : String(value ?? '');

function Facts({ rows }: { rows: [string, ReactNode][] }) {
  return (
    <dl className="border-border bg-card text-body-md shadow-card grid gap-x-6 gap-y-2 rounded-xl border p-4 sm:grid-cols-[auto_1fr]">
      {rows.map(([name, value]) => (
        <div key={name} className="contents">
          <dt className="text-label-md text-on-surface-variant">{name}</dt>
          <dd className="min-w-0 wrap-break-word">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** A rules supervisor: how many rules and which version, and where they are read and changed. */
function RulesFacts({ supervisor }: { supervisor: SupervisorView }) {
  const rules = useQuery({
    queryKey: ['supervisors', 'rules', supervisor.id],
    queryFn: () => getRules(supervisor.id),
  });
  const count = rules.data?.rules.rules?.length;
  return (
    <>
      <Facts
        rows={[
          [
            'Rules',
            rules.isPending
              ? 'Loading…'
              : rules.data
                ? `${count ?? 0} · version ${rules.data.version}`
                : 'Could not be read',
          ],
          [
            'Endpoint',
            <code key="e" className="text-code-sm">
              GET/PUT /api/supervisors/{supervisor.id}/engine/rules
            </code>,
          ],
        ]}
      />
      <div className="flex gap-2">
        {!supervisor.system && (
          <IconLink
            to={`/supervisors/${encodeURIComponent(supervisor.id)}/edit`}
            icon="edit"
            label="Edit the rules"
            tone="primary"
          />
        )}
      </div>
    </>
  );
}

/** What the engine of this supervisor is, in the terms of its own type. */
function EngineFacts({ supervisor }: { supervisor: SupervisorView }) {
  const s = supervisor.settings;
  if (supervisor.type === 'rules') {
    return <RulesFacts supervisor={supervisor} />;
  }
  if (supervisor.type === 'http') {
    // how it connects and its credential are in the settings below, once
    return (
      <Facts
        rows={[
          [
            'Posts to',
            s.url ? text(s.url) : 'nothing: the system reads the signals',
          ],
          [
            'Agent API',
            <code key="a" className="text-code-sm">
              /api/agent/v1
            </code>,
          ],
        ]}
      />
    );
  }
  if (supervisor.type === 'workflow') {
    const session = text(s.sessionId);
    const workflow = text(s.workflowId);
    return (
      <Facts
        rows={[
          [
            'Decision workflow',
            session && workflow ? (
              <Link
                to={`/sessions/${encodeURIComponent(session)}/workflow/${encodeURIComponent(workflow)}`}
                className="text-primary"
              >
                {session} / {workflow}
              </Link>
            ) : (
              'not set'
            ),
          ],
          ['Starts on', text(s.events) || '–'],
          ['At most at once', text(s.maxConcurrent ?? 2)],
          ['Time per run', `${text(s.timeout ?? 60)} s`],
        ]}
      />
    );
  }
  if (supervisor.type === 'process') {
    return (
      <Facts
        rows={[
          [
            'Program',
            <code key="c" className="text-code-sm">
              {text(s.command) || 'not set'}
            </code>,
          ],
          ['Arguments', text(s.args).replace(/\n/g, ' ') || 'none'],
        ]}
      />
    );
  }
  return null;
}

/** The one decision maker of this supervisor, as an instance, with its settings. */
function DecisionMakerTab({ supervisor }: { supervisor: SupervisorView }) {
  const types = useSupervisorTypes();
  const maker = decisionMaker(supervisor.type, types.data);
  // a type the catalog does not list (a plugin's) has no page to open
  const listed = types.data?.some((t) => t.type === supervisor.type) === true;
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
          type, edit the supervisor.
        </p>
        {listed && (
          <div>
            <IconLink
              to={`/supervisors?view=decision-makers&type=${encodeURIComponent(supervisor.type)}`}
              icon="menu_book"
              label="Browse decision maker types"
            />
          </div>
        )}
      </div>
      <EngineFacts supervisor={supervisor} />
      <SettingsTab supervisor={supervisor} />
    </div>
  );
}

/** One configured supervisor: header and numbers on top, then a main column and a rail of what it may do. */
export function SupervisorDetail({
  supervisor,
  all,
  onSelect,
  section,
  setSection,
}: {
  supervisor: SupervisorView;
  all: SupervisorView[];
  onSelect: (id: string) => void;
  /** The part named in the URL, to scroll to. */
  section: DetailSection | null;
  setSection: (section: DetailSection) => void;
}) {
  const types = useSupervisorTypes();
  const maker = decisionMaker(supervisor.type, types.data);
  const decisions = useSupervisorDecisions(supervisor.id);
  useScrollToSection(section);
  return (
    <article className="flex min-w-0 flex-col gap-4">
      <SupervisorHero
        supervisor={supervisor}
        typeName={maker.name}
        onOwns={() => setSection('owns')}
        actions={!supervisor.system && <EditLink id={supervisor.id} />}
      />
      {supervisor.dryRun && (
        <Callout kind="warning" compact title="Dry run: recorded, not applied">
          It records the commands it would send and applies none.
          {!supervisor.system && ' Turn dry run off in Edit supervisor.'}
        </Callout>
      )}
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="flex min-w-0 flex-col gap-4">
          <SupervisionMap
            supervisor={supervisor}
            all={all}
            onSelect={onSelect}
            onMore={() => setSection('owns')}
          />
          <Section id="owns" title="Owns" count={supervisor.attachments}>
            <OwnsTab supervisor={supervisor} />
          </Section>
          <Section
            id="decisions"
            title="Decisions"
            count={decisions.data?.length}
          >
            <Activity supervisor={supervisor.id} />
          </Section>
        </div>
        <div className="flex min-w-0 flex-col gap-4">
          <Section id="operations" title="Allowed operations">
            <OperationsTab supervisor={supervisor} />
          </Section>
          <Section id="hierarchy" title="Hierarchy">
            <HierarchyTab
              supervisor={supervisor}
              all={all}
              onSelect={onSelect}
            />
          </Section>
          <Section id="decision-maker" title="Decision maker">
            <DecisionMakerTab supervisor={supervisor} />
          </Section>
        </div>
      </div>
      <details
        id="section-changes"
        aria-label="Changes"
        open={section === 'changes' ? true : undefined}
        className="border-border bg-card shadow-card scroll-mt-20 rounded-xl border p-4"
      >
        <summary className="text-overline text-on-surface-variant cursor-pointer select-none">
          Changes
        </summary>
        <div className="mt-3">
          <HistoryTab supervisor={supervisor} />
        </div>
      </details>
      {!supervisor.system && <OtherWays id={supervisor.id} />}
    </article>
  );
}

/** Direct control in the same template: what nobody owns, and people and agents deciding. */
export function DirectControlDetail({
  section,
}: {
  section: DetailSection | null;
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
  useScrollToSection(section);
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
  return (
    <article className="flex min-w-0 flex-col gap-4">
      <DirectHero description="Controls the workflows that no supervisor owns. Each run and node listed here is controlled directly, by people or agents." />
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_22rem]">
        <Section id="owns" title="Owns" count={direct.owned.length}>
          {direct.owned.length === 0 ? (
            <StateMessage title="Nothing is unowned" icon="check_circle">
              Every workflow has a supervisor.
            </StateMessage>
          ) : (
            <ul className="divide-border border-border bg-card shadow-card flex flex-col divide-y rounded-xl border">
              {direct.owned.map((w) => (
                <li
                  key={`${w.sessionId}/${w.workflowId}`}
                  className="flex items-center gap-2 p-3"
                >
                  <span
                    aria-hidden="true"
                    className="material-symbols-outlined text-primary text-lg"
                  >
                    account_tree
                  </span>
                  <Link
                    to={`/supervisors?view=workflows&session=${encodeURIComponent(w.sessionId)}&workflow=${encodeURIComponent(w.workflowId)}`}
                    className="text-label-md text-primary"
                  >
                    {w.sessionId} / {w.workflowId}
                  </Link>
                  <span className="flex-1" />
                  <Tip label="Open the workflow">
                    <Link
                      to={`/sessions/${encodeURIComponent(w.sessionId)}/workflow/${encodeURIComponent(w.workflowId)}?supervision=1`}
                      aria-label={`Open ${w.sessionId} / ${w.workflowId}`}
                      className="text-on-surface-variant hover:text-primary flex"
                    >
                      <span
                        aria-hidden="true"
                        className="material-symbols-outlined text-base"
                      >
                        open_in_new
                      </span>
                    </Link>
                  </Tip>
                </li>
              ))}
            </ul>
          )}
        </Section>
        <div className="flex min-w-0 flex-col gap-4">
          <Section id="operations" title="Allowed operations">
            <p className="text-body-md">
              {refused
                ? 'Direct control is switched off on this server: a command on a node nobody owns is refused (HTTP 409).'
                : 'Everything: people and agents may send any command to a run or node nobody owns.'}
            </p>
          </Section>
          <Section id="decision-maker" title="Decision maker">
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
          </Section>
        </div>
      </div>
    </article>
  );
}
