// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { CodeBlock } from '@/components/common/CodeBlock';
import { StateMessage } from '@/components/common/StateMessage';
import { Input } from '@/components/ui/input';
import {
  Table,
  TableBody,
  TableCell,
  TableFrame,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { cn } from '@/lib/utils';
import {
  useSupervisorType,
  useSupervisorTypePresets,
  useSupervisorTypeUsage,
  useSupervisorTypes,
} from '../hooks/useSupervisorTypes';
import {
  DECISION_MAKER_CATEGORIES,
  categoryColor,
} from '../lib/decisionMakers';
import type { SupervisorTypeDetails } from '../types';

/** What each signal a decision maker is told means. */
const SIGNALS: Record<string, string> = {
  EVENT: 'What happens on the nodes and runs the supervisor owns',
  TICK: 'A timer, for rules that act on their own',
  REVIEW: 'A command someone sent, for it to allow or refuse',
  DELEGATED: 'A supervisor under it asks it to decide',
  CONSULTED: 'A supervisor under it asks for its opinion',
  STARTED: 'It was started or restarted, with what it owns',
};
const SIGNAL_NAME: Record<string, string> = {
  EVENT: 'Events',
  TICK: 'Ticks',
  REVIEW: 'Reviews',
  DELEGATED: 'Delegations',
  CONSULTED: 'Consultations',
  STARTED: 'Started',
};

const TABS = ['Signals', 'Settings', 'Presets', 'Example', 'Used by'] as const;
type Tab = (typeof TABS)[number];

const defaultText = (value: unknown) =>
  value == null
    ? '—'
    : typeof value === 'object'
      ? JSON.stringify(value)
      : String(value);

function Settings({ data }: { data: SupervisorTypeDetails }) {
  if (data.configSchema.length === 0) {
    return (
      <p className="text-body-md text-on-surface-variant">
        It has no settings of its own; only the settings every supervisor has
        (allowed commands, acts on its own, people can…).
      </p>
    );
  }
  return (
    <TableFrame>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Setting</TableHead>
            <TableHead>Type</TableHead>
            <TableHead>Default</TableHead>
            <TableHead>What it does</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {data.configSchema.map((field) => (
            <TableRow key={field.name}>
              <TableCell className="text-code-sm">
                {field.name}
                {field.required && (
                  <span className="text-destructive"> (required)</span>
                )}
              </TableCell>
              <TableCell className="text-code-sm text-on-surface-variant">
                {field.type.toLowerCase()}
              </TableCell>
              <TableCell className="text-code-sm text-on-surface-variant">
                {defaultText(field.defaultValue)}
              </TableCell>
              <TableCell>{field.description}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableFrame>
  );
}

function Presets({ type }: { type: string }) {
  const { data, isPending, error } = useSupervisorTypePresets(type);
  if (isPending)
    return <StateMessage tone="loading" title="Loading presets…" />;
  if (error)
    return <StateMessage tone="error" title="Failed to load the presets." />;
  if (data.length === 0) return <StateMessage title="No presets" compact />;
  return (
    <ul className="flex flex-col gap-3">
      {data.map((p) => (
        <li key={p.name} className="flex flex-col gap-1">
          <b className="text-label-md">{p.name}</b>
          <span className="text-body-sm text-on-surface-variant">
            {p.description}
          </span>
          <CodeBlock code={JSON.stringify(p.settings, null, 2)} />
        </li>
      ))}
    </ul>
  );
}

function UsedBy({ type }: { type: string }) {
  const { data, isPending, error } = useSupervisorTypeUsage(type);
  if (isPending) return <StateMessage tone="loading" title="Loading…" />;
  if (error)
    return <StateMessage tone="error" title="Failed to load who uses it." />;
  if (data.length === 0) {
    return <StateMessage title="No supervisor is wired to it" compact />;
  }
  return (
    <div className="flex flex-col gap-2">
      <ul className="flex flex-wrap gap-2">
        {data.map((id) => (
          <li
            key={id}
            className="border-border bg-card inline-flex items-center rounded-full border px-3 py-1"
          >
            <Link
              to={`/supervisors?supervisor=${encodeURIComponent(id)}`}
              className="text-body-sm text-primary"
            >
              {id}
            </Link>
          </li>
        ))}
      </ul>
      <p className="text-body-sm text-muted-foreground">
        A supervisor has one decision maker; change it on the supervisor.
      </p>
    </div>
  );
}

function DecisionMakerDetail({ type }: { type: string }) {
  const { data, isPending, error } = useSupervisorType(type);
  const [tab, setTab] = useState<Tab>('Signals');
  if (isPending) return <StateMessage tone="loading" title="Loading…" />;
  if (error)
    return <StateMessage tone="error" title={`Failed to load ${type}.`} />;
  const category = DECISION_MAKER_CATEGORIES.find(
    (c) => c.key === data.category
  );
  const color = categoryColor(data.category);
  const signals = data.signals ?? [];
  return (
    <article className="mx-auto flex max-w-5xl min-w-0 flex-col gap-5">
      <header>
        <h2 className="text-headline-lg text-on-surface">{data.displayName}</h2>
        <div className="text-body-md text-on-surface-variant mt-1 flex flex-wrap items-center gap-x-3 gap-y-1">
          <span className="inline-flex items-center gap-2">
            <span
              aria-hidden="true"
              className="size-2 rounded-full"
              style={{ background: color }}
            />
            {category?.label ?? data.category}
          </span>
          <code className="text-code-sm select-all">{data.type}</code>
          {data.canInitiate && <span>can act on its own</span>}
        </div>
      </header>
      <p className="text-body-md text-on-surface max-w-prose">
        {data.description}
      </p>
      {data.usagePattern && (
        <p className="text-body-md text-on-surface-variant max-w-prose">
          {data.usagePattern}
        </p>
      )}
      <section
        aria-label="How it connects"
        className="border-border bg-card text-body-md shadow-card flex flex-col gap-2 rounded-xl border p-4 md:flex-row md:items-center md:gap-6"
      >
        <p>
          <span className="text-muted-foreground">Told </span>
          <b>
            {signals.map((s) => SIGNAL_NAME[s] ?? s).join(', ') || 'events'}
          </b>
        </p>
        <p className="flex items-center gap-2">
          <span
            aria-hidden="true"
            className="material-symbols-outlined"
            style={{ color }}
          >
            arrow_forward
          </span>
          <b>{data.displayName}</b>
        </p>
        <p>
          <span className="text-muted-foreground">Sends </span>
          <b>commands, answers and verdicts</b>
        </p>
      </section>
      <div
        role="tablist"
        aria-label={`${data.displayName} details`}
        className="border-border flex flex-wrap gap-x-5 border-b"
      >
        {TABS.map((name) => (
          <button
            key={name}
            type="button"
            role="tab"
            aria-selected={tab === name}
            onClick={() => setTab(name)}
            className={cn(
              'text-label-md -mb-px shrink-0 border-b-2 py-2',
              tab === name
                ? 'border-primary text-on-surface'
                : 'text-on-surface-variant hover:text-on-surface border-transparent'
            )}
          >
            {name}
          </button>
        ))}
      </div>
      <div role="tabpanel" aria-label={tab}>
        {tab === 'Signals' && (
          <ul className="flex flex-col gap-1">
            {signals.map((s) => (
              <li key={s} className="text-body-md">
                <b>{SIGNAL_NAME[s] ?? s}</b>{' '}
                <span className="text-on-surface-variant">
                  {SIGNALS[s] ?? ''}
                </span>
              </li>
            ))}
          </ul>
        )}
        {tab === 'Settings' && <Settings data={data} />}
        {tab === 'Presets' && <Presets type={type} />}
        {tab === 'Example' && (
          <CodeBlock code={JSON.stringify(data.exampleSettings, null, 2)} />
        )}
        {tab === 'Used by' && <UsedBy type={type} />}
      </div>
    </article>
  );
}

/** The decision makers a supervisor can be wired to, laid out like Plugins. */
export function DecisionMakersTab() {
  const types = useSupervisorTypes();
  const [params, setParams] = useSearchParams();
  const [filter, setFilter] = useState('');
  if (types.isPending) return <StateMessage tone="loading" title="Loading…" />;
  if (types.error) {
    return (
      <StateMessage tone="error" title="Failed to load the decision makers." />
    );
  }
  const all = types.data;
  if (all.length === 0) {
    return (
      <StateMessage title="No decision makers are installed">
        The catalog is empty: check the server&apos;s plugins.
      </StateMessage>
    );
  }
  const chosen = params.get('type') ?? all[0].type;
  const choose = (type: string) => {
    const next = new URLSearchParams(params);
    next.set('type', type);
    setParams(next, { replace: true });
  };
  const needle = filter.trim().toLowerCase();
  const known = DECISION_MAKER_CATEGORIES.map((c) => c.key as string);
  const groups = [
    ...DECISION_MAKER_CATEGORIES,
    { key: 'OTHER', label: 'Other', color: categoryColor(undefined) },
  ];
  return (
    <div className="flex flex-col gap-6 md:flex-row">
      <nav
        aria-label="Decision makers"
        className="flex shrink-0 flex-col gap-4 md:sticky md:top-18 md:max-h-[calc(100vh-5.5rem)] md:w-70 md:self-start md:overflow-y-auto"
      >
        <Input
          type="search"
          aria-label="Filter decision makers"
          placeholder="Filter by name or ID"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        {groups.map(({ key, label, color }) => {
          const items = all.filter(
            (t) =>
              (key === 'OTHER'
                ? !known.includes(t.category)
                : t.category === key) &&
              `${t.type} ${t.displayName}`.toLowerCase().includes(needle)
          );
          if (items.length === 0) return null;
          return (
            <section key={key}>
              <h3 className="text-label-md text-on-surface mb-1 flex items-center gap-2">
                <span
                  aria-hidden="true"
                  className="size-2 rounded-full"
                  style={{ background: color }}
                />
                {label}
                <span className="text-body-sm text-muted-foreground tabular-nums">
                  {items.length}
                </span>
              </h3>
              <ul className="flex flex-col gap-0.5">
                {items.map((t) => (
                  <li key={t.type}>
                    <button
                      type="button"
                      aria-current={t.type === chosen}
                      onClick={() => choose(t.type)}
                      className={cn(
                        'text-body-md transition-standard w-full rounded-lg px-3 py-1.5 text-left',
                        t.type === chosen
                          ? 'bg-accent text-accent-foreground font-semibold'
                          : 'text-on-surface-variant hover:bg-muted hover:text-on-surface'
                      )}
                    >
                      <span className="block">{t.displayName}</span>
                      <span className="text-code-sm block text-[11px] opacity-75">
                        {t.type}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          );
        })}
      </nav>
      <div className="min-w-0 flex-1">
        <DecisionMakerDetail key={chosen} type={chosen} />
      </div>
    </div>
  );
}
