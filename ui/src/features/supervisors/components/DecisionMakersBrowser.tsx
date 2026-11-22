// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useRef, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import { CodeBlock } from '@/components/common/CodeBlock';
import { IconButton } from '@/components/common/IconButton';
import { StateMessage } from '@/components/common/StateMessage';
import { Input } from '@/components/ui/input';
import { MultiSelect } from '@/components/ui/select';
import {
  Table,
  TableBody,
  TableCell,
  TableFrame,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Tip } from '@/components/ui/tooltip';
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
  decisionMaker,
} from '../lib/decisionMakers';
import type { SupervisorTypeDetails } from '../types';
import { ListToggle, useListDisclosure } from './ListDisclosure';
import { Chip, SupervisorShell } from './SupervisorCard';

/** What each signal a decision maker is told means; shown as the tooltip of its tile. */
const SIGNALS: Record<string, { name: string; icon: string; means: string }> = {
  EVENT: {
    name: 'Events',
    icon: 'notifications',
    means: 'What happens on the nodes and runs the supervisor owns',
  },
  TICK: {
    name: 'Ticks',
    icon: 'schedule',
    means: 'A timer, for rules that act on their own',
  },
  REVIEW: {
    name: 'Reviews',
    icon: 'rule',
    means: 'A command someone sent, for it to allow or refuse',
  },
  DELEGATED: {
    name: 'Delegations',
    icon: 'shield_person',
    means: 'A supervisor under it asks it to decide',
  },
  CONSULTED: {
    name: 'Consultations',
    icon: 'person',
    means: 'A supervisor under it asks for its opinion',
  },
  STARTED: {
    name: 'Started',
    icon: 'play_arrow',
    means: 'It was started or restarted, with what it owns',
  },
};

const defaultText = (value: unknown) =>
  value == null
    ? '—'
    : typeof value === 'object'
      ? JSON.stringify(value)
      : String(value);

/** A part of the page: an overline heading, then its content (the same as a supervisor's page). */
function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="flex flex-col gap-2">
      <h3 className="text-overline text-on-surface-variant">{title}</h3>
      {children}
    </section>
  );
}

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
    <ul className="flex flex-wrap gap-2">
      {data.map((id) => (
        <li key={id}>
          <Chip>
            <Link
              to={`/supervisors?supervisor=${encodeURIComponent(id)}`}
              className="text-primary"
            >
              {id}
            </Link>
          </Chip>
        </li>
      ))}
    </ul>
  );
}

/** One signal as an icon tile; what it means is its tooltip. */
function SignalTile({ signal }: { signal: string }) {
  const known = SIGNALS[signal];
  return (
    <Tip label={known ? `${known.name}: ${known.means}` : signal}>
      <span className="border-border bg-card shadow-card flex size-12 items-center justify-center rounded-lg border">
        <span
          aria-hidden="true"
          className="bg-primary/10 text-primary flex size-7 items-center justify-center rounded-md"
        >
          <span className="material-symbols-outlined text-lg">
            {known?.icon ?? 'notifications'}
          </span>
        </span>
        <span className="sr-only">{known?.name ?? signal}</span>
      </span>
    </Tip>
  );
}

/** One decision maker: its card, then what it is told, what it is set with, and who uses it. */
function DecisionMakerDetail({ type }: { type: string }) {
  const { data, isPending, error } = useSupervisorType(type);
  const catalog = useSupervisorTypes();
  if (isPending) return <StateMessage tone="loading" title="Loading…" />;
  if (error)
    return <StateMessage tone="error" title={`Failed to load ${type}.`} />;
  const maker = decisionMaker(type, catalog.data);
  const category = DECISION_MAKER_CATEGORIES.find(
    (c) => c.key === data.category
  );
  const signals = data.signals ?? [];
  return (
    <article className="flex min-w-0 flex-col gap-4">
      <SupervisorShell direct={false} className="p-4">
        <div className="relative flex items-start gap-3">
          <span
            aria-hidden="true"
            className="bg-card ring-border shadow-card flex size-12 shrink-0 items-center justify-center rounded-xl ring-1"
            style={{ color: maker.color }}
          >
            <span className="material-symbols-outlined text-[26px]!">
              {maker.icon}
            </span>
          </span>
          <div className="min-w-0 flex-1">
            <p className="text-overline text-on-surface-variant">
              Decision maker
            </p>
            <h2 className="text-headline-md text-on-surface break-all">
              {data.displayName}
            </h2>
            <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
              <Chip>
                <span
                  aria-hidden="true"
                  className="mr-1 inline-block size-1.5 rounded-full align-middle"
                  style={{ background: maker.color }}
                />
                {category?.label ?? data.category}
              </Chip>
              <code className="text-code-sm text-on-surface-variant select-all">
                {data.type}
              </code>
              {data.canInitiate && <Chip tone="success">Acts on its own</Chip>}
            </div>
          </div>
          {data.usagePattern && (
            <Tip label={data.usagePattern}>
              <span
                role="img"
                aria-label="How it is used"
                className="material-symbols-outlined text-on-surface-variant"
              >
                info
              </span>
            </Tip>
          )}
        </div>
        <p className="text-body-md text-on-surface relative mt-3 max-w-prose">
          {data.description}
        </p>
      </SupervisorShell>
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="flex min-w-0 flex-col gap-4">
          <Section title="Settings">
            <Settings data={data} />
          </Section>
          <Section title="Presets">
            <Presets type={type} />
          </Section>
          <details className="border-border bg-card shadow-card rounded-xl border p-4">
            <summary className="text-overline text-on-surface-variant cursor-pointer select-none">
              Example
            </summary>
            <div className="mt-3">
              <CodeBlock code={JSON.stringify(data.exampleSettings, null, 2)} />
            </div>
          </details>
        </div>
        <div className="flex min-w-0 flex-col gap-4">
          <Section title="Signals">
            <div className="flex flex-wrap items-center gap-1.5">
              {signals.map((s) => (
                <SignalTile key={s} signal={s} />
              ))}
              <span
                aria-hidden="true"
                className="material-symbols-outlined text-on-surface-variant"
              >
                arrow_forward
              </span>
              <Tip
                label={`${data.displayName} sends commands, answers and verdicts`}
              >
                <span
                  className="bg-card ring-border shadow-card flex size-12 items-center justify-center rounded-lg ring-1"
                  style={{ color: maker.color }}
                >
                  <span
                    aria-hidden="true"
                    className="material-symbols-outlined text-xl"
                  >
                    {maker.icon}
                  </span>
                  <span className="sr-only">{data.displayName}</span>
                </span>
              </Tip>
            </div>
          </Section>
          <Section title="Used by">
            <UsedBy type={type} />
          </Section>
        </div>
      </div>
    </article>
  );
}

/** The decision makers a supervisor can be wired to: a list, and the chosen one in the supervisor page's language. */
export function DecisionMakersBrowser() {
  const types = useSupervisorTypes();
  const [params, setParams] = useSearchParams();
  const [filter, setFilter] = useState('');
  const [categories, setCategories] = useState<string[]>([]);
  const detailRef = useRef<HTMLDivElement>(null);
  // the detail has its heading once its entry is read (the same query the detail runs)
  const wanted =
    types.data?.find((t) => t.type === params.get('type'))?.type ??
    types.data?.[0]?.type ??
    null;
  const entry = useSupervisorType(wanted, wanted !== null);
  const phone = useListDisclosure(
    params.get('type'),
    detailRef,
    !entry.isPending
  );
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
  // an old link may name a type the catalog does not list: show the first one
  const chosen =
    all.find((t) => t.type === params.get('type'))?.type ?? all[0].type;
  const choose = (type: string) => {
    phone.onPick();
    const next = new URLSearchParams(params);
    next.set('type', type);
    setParams(next, { replace: true });
  };
  const needle = filter.trim().toLowerCase();
  const known = DECISION_MAKER_CATEGORIES.map((c) => c.key as string);
  const groupOf = (category: string) =>
    known.includes(category) ? category : 'OTHER';
  const options = [
    ...DECISION_MAKER_CATEGORIES.map((c) => ({ value: c.key, label: c.label })),
    { value: 'OTHER', label: 'Other' },
  ];
  const order = options.map((o) => o.value);
  const shown = all
    .filter(
      (t) =>
        `${t.type} ${t.displayName}`.toLowerCase().includes(needle) &&
        (categories.length === 0 || categories.includes(groupOf(t.category)))
    )
    .sort(
      (a, b) =>
        order.indexOf(groupOf(a.category)) -
          order.indexOf(groupOf(b.category)) ||
        a.displayName.localeCompare(b.displayName)
    );
  return (
    <div className="flex flex-col gap-4 md:flex-row">
      <div className="flex shrink-0 flex-col gap-3 md:sticky md:top-18 md:max-h-[calc(100vh-5.5rem)] md:w-64 md:self-start md:overflow-y-auto xl:w-72">
        <ListToggle
          label={`All decision makers (${all.length})`}
          open={phone.open}
          controls="decision-maker-list"
          onToggle={phone.toggle}
        />
        <div className="flex flex-col gap-2">
          <Input
            type="search"
            aria-label="Filter decision makers"
            placeholder="Search by name or type"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
          <MultiSelect
            ariaLabel="Filter by category"
            placeholder="Any category"
            values={categories}
            onValuesChange={setCategories}
            options={options}
          />
        </div>
        <nav
          id="decision-maker-list"
          aria-label="Decision makers"
          className={cn('flex flex-col gap-2', phone.listClass)}
        >
          <ul className="flex flex-col gap-0.5">
            {shown.map((t) => {
              const color = categoryColor(t.category);
              return (
                <li key={t.type}>
                  <button
                    type="button"
                    aria-current={t.type === chosen}
                    onClick={() => choose(t.type)}
                    className={cn(
                      'transition-standard flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left',
                      t.type === chosen
                        ? 'bg-accent text-accent-foreground'
                        : 'text-on-surface-variant hover:bg-muted hover:text-on-surface'
                    )}
                  >
                    <span
                      aria-hidden="true"
                      className="bg-card ring-border flex size-8 shrink-0 items-center justify-center rounded-lg ring-1"
                      style={{ color }}
                    >
                      <span className="material-symbols-outlined text-lg">
                        {decisionMaker(t.type, all).icon}
                      </span>
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="text-body-md block truncate font-medium">
                        {t.displayName}
                      </span>
                      <code className="text-code-sm block truncate text-[11px]">
                        {t.type}
                      </code>
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </nav>
        {shown.length === 0 && (
          <StateMessage compact icon="search" title="No decision maker matches">
            Try another name or clear the filters.
            <IconButton
              icon="close"
              label="Clear filters"
              onClick={() => {
                setFilter('');
                setCategories([]);
              }}
            />
          </StateMessage>
        )}
      </div>
      <div ref={detailRef} className="min-w-0 flex-1">
        <DecisionMakerDetail key={chosen} type={chosen} />
      </div>
    </div>
  );
}
