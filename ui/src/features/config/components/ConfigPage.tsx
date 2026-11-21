// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Fragment, useMemo, useState } from 'react';
import { cn } from '@/lib/utils';
import { useSearchParams } from 'react-router';
import { Callout } from '@/components/common/Callout';
import { CodeBlock } from '@/components/common/CodeBlock';
import { StateMessage } from '@/components/common/StateMessage';
import { PageHeader } from '@/components/layout/PageHeader';
import { Tabs } from '@/components/ui/tabs';
import { PackagingGuide } from './PackagingGuide';
import { SecurityGuide } from './SecurityGuide';
import { Input } from '@/components/ui/input';
import { Select } from '@/components/ui/select';
import { Badge } from '@/components/ui/badge';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import {
  Table,
  TableBody,
  TableCell,
  TableFrame,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import {
  AREAS,
  CATALOG,
  envName,
  type AppliesWhen,
  type ConfigOption,
} from '../lib/catalog';
import { howToSet } from '../lib/howToSet';
import { useConfigValues } from '../hooks/useConfigValues';

/** Roomy cells that wrap instead of scrolling sideways. */
const CELL = 'px-5';
const WRAP = 'px-5 py-3.5 align-top whitespace-normal';
const NOWRAP = 'px-5 py-3.5 align-top whitespace-nowrap';
/** A value column: wraps only a value too long for it. */
const VALUE = `${WRAP} text-code-sm wrap-anywhere`;

const WHEN_LABEL: Record<AppliesWhen, string> = {
  startup: 'Restart',
  runtime: 'Live',
  build: 'Build',
};

function Details({ option }: { option: ConfigOption }) {
  return (
    <div className="grid gap-3 py-1 md:grid-cols-2">
      <div className="space-y-2">
        <p className="text-body-md">{option.purpose}</p>
        {option.native && (
          <Callout kind="warning" compact title="Native image and Docker">
            {option.native}
          </Callout>
        )}
      </div>
      <div className="space-y-2">
        {howToSet(option).map(({ label, code }) => (
          <div key={label}>
            <p className="text-overline text-on-surface-variant mb-1">
              {label}
            </p>
            <CodeBlock code={code} label={`${label} for ${option.key}`} />
          </div>
        ))}
      </div>
    </div>
  );
}

/** The catalog of startup options: what each does, when a change applies and how to set it. */
function OptionCatalog() {
  const [query, setQuery] = useState('');
  const [area, setArea] = useState('');
  const [when, setWhen] = useState('');
  const [native, setNative] = useState('');
  const [set, setSet] = useState('');
  const [open, setOpen] = useState<string | null>(null);

  const live = useConfigValues();
  const values = live.status === 'ready' ? live.values : null;

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return CATALOG.filter(
      (o) =>
        (!area || o.area === area) &&
        (!when || o.appliesWhen === when) &&
        (!native || o.native) &&
        (!set || (values?.get(o.key)?.source ?? 'default') !== 'default') &&
        (!q || `${o.key} ${envName(o)} ${o.purpose}`.toLowerCase().includes(q))
    );
  }, [query, area, when, native, set, values]);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Input
          aria-label="Search options"
          placeholder="Search by key, variable or purpose"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="h-9 w-full sm:w-80"
        />
        <Select
          ariaLabel="Area"
          value={area}
          onValueChange={setArea}
          options={[
            { value: '', label: 'All areas' },
            ...AREAS.map((a) => ({ value: a, label: a })),
          ]}
          className="h-9 w-44"
        />
        <Select
          ariaLabel="Applies"
          value={when}
          onValueChange={setWhen}
          options={[
            { value: '', label: 'Any change type' },
            { value: 'startup', label: 'Needs a restart' },
            { value: 'runtime', label: 'Changes live' },
          ]}
          className="h-9 w-44"
        />
        <Select
          ariaLabel="Mode"
          value={native}
          onValueChange={setNative}
          options={[
            { value: '', label: 'JVM, native and Docker' },
            { value: 'native', label: 'Differs in native and Docker' },
          ]}
          className="h-9 w-56"
        />
        {values && (
          <Select
            ariaLabel="Value"
            value={set}
            onValueChange={setSet}
            options={[
              { value: '', label: 'Any value' },
              { value: 'set', label: 'Set on this server' },
            ]}
            className="h-9 w-44"
          />
        )}
        <Popover>
          <PopoverTrigger
            aria-label="How configuration works"
            className="border-border text-primary hover:bg-primary/10 transition-standard focus-visible:ring-ring/30 ml-auto flex h-9 w-9 cursor-pointer items-center justify-center rounded-full border outline-none focus-visible:ring-3"
          >
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-xl"
            >
              info
            </span>
          </PopoverTrigger>
          <PopoverContent align="end" className="w-96 space-y-2 p-4">
            <p className="text-label-md">How configuration works</p>
            <p className="text-body-md text-on-surface-variant">
              Most options are read once at start, so change them and restart. A
              value is taken from, in order: a command-line argument, an
              environment variable, then <code>application.yaml</code>. Options
              marked <em>Live</em> change without a restart. A few are read only
              as <code>-D</code> JVM system properties. In the native binary and
              the Docker image, some choices are fixed when the image is built.
            </p>
          </PopoverContent>
        </Popover>
      </div>
      {live.status === 'denied' && (
        <Callout compact>Current values need an ADMIN key.</Callout>
      )}
      {live.status === 'loading' && (
        <p className="text-body-sm text-on-surface-variant">
          Loading current values…
        </p>
      )}
      {live.status === 'error' && (
        <Callout kind="warning" compact>
          Could not load the current values.
        </Callout>
      )}
      {rows.length === 0 ? (
        <StateMessage tone="neutral" title="No options match.">
          Clear the search or a filter.
        </StateMessage>
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className={`${CELL} whitespace-normal`}>
                  Option
                  <span className="text-on-surface-variant ml-2 font-normal normal-case">
                    Select a row for how to set it
                  </span>
                </TableHead>
                <TableHead className={`${CELL}`}>Default</TableHead>
                {values && (
                  <TableHead className={`${CELL}`}>Current value</TableHead>
                )}
                {values && <TableHead className={`${CELL}`}>Source</TableHead>}
                <TableHead className={`${CELL}`}>Applies</TableHead>
                <TableHead className={`${CELL}`}>Area</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((o) => {
                const expanded = open === o.key;
                const now = values?.get(o.key);
                return (
                  <Fragment key={o.key}>
                    <TableRow
                      onClick={() => setOpen(expanded ? null : o.key)}
                      className="group cursor-pointer"
                    >
                      <TableCell className={`${WRAP} min-w-80`}>
                        <div className="flex items-start gap-2">
                          <span
                            aria-hidden="true"
                            className={cn(
                              'material-symbols-outlined text-on-surface-variant group-hover:text-primary transition-standard shrink-0 text-xl',
                              expanded && 'text-primary rotate-180'
                            )}
                          >
                            expand_more
                          </span>
                          <div className="min-w-0">
                            {/* the row handles the click; the button is the keyboard and screen reader target */}
                            <button
                              type="button"
                              aria-expanded={expanded}
                              className="text-code-sm group-hover:text-primary text-left font-medium wrap-anywhere"
                            >
                              {o.key}
                            </button>
                            <p className="text-body-sm text-on-surface-variant mt-1">
                              {o.purpose}
                            </p>
                          </div>
                        </div>
                      </TableCell>
                      <TableCell className={VALUE}>{o.default}</TableCell>
                      {values && (
                        <TableCell className={VALUE}>
                          {now ? (now.value ?? 'Not set') : '—'}
                        </TableCell>
                      )}
                      {values && (
                        <TableCell className={`${NOWRAP} text-body-sm`}>
                          {now ? (
                            now.source === 'default' ? (
                              <span className="text-on-surface-variant">
                                Default
                              </span>
                            ) : (
                              now.source
                            )
                          ) : (
                            '—'
                          )}
                        </TableCell>
                      )}
                      <TableCell className={NOWRAP}>
                        <Badge
                          variant={
                            o.appliesWhen === 'runtime'
                              ? 'default'
                              : 'secondary'
                          }
                        >
                          {WHEN_LABEL[o.appliesWhen]}
                        </Badge>
                      </TableCell>
                      <TableCell className={`${NOWRAP} text-body-sm`}>
                        {o.area}
                      </TableCell>
                    </TableRow>
                    {expanded && (
                      <TableRow>
                        <TableCell
                          colSpan={values ? 6 : 4}
                          className="bg-muted/30 px-5 py-4 whitespace-normal"
                        >
                          <Details option={o} />
                        </TableCell>
                      </TableRow>
                    )}
                  </Fragment>
                );
              })}
            </TableBody>
          </Table>
        </TableFrame>
      )}
    </div>
  );
}

type Tab = 'options' | 'security' | 'modes';
const TABS = [
  { id: 'options', label: 'Options' },
  { id: 'security', label: 'Security' },
  { id: 'modes', label: 'Run modes' },
] as const;

/** Every way to configure Yukta, and the few settings that change while it runs. */
export function ConfigPage() {
  const [params, setParams] = useSearchParams();
  const requested = params.get('tab');
  const tab: Tab =
    requested === 'modes' || requested === 'security' ? requested : 'options';
  const choose = (next: Tab) => {
    const updated = new URLSearchParams(params);
    if (next === 'options') updated.delete('tab');
    else updated.set('tab', next);
    setParams(updated, { replace: true });
  };
  return (
    <div className="px-4 py-6 sm:px-6">
      <PageHeader
        backdrop
        title="Configuration"
        description="Every option that configures Yukta: what it does, when a change takes effect and how to set it."
      />
      <Tabs
        items={TABS}
        value={tab}
        onChange={choose}
        ariaLabel="Configuration sections"
        idPrefix="config"
      />
      <div
        id="config-tabpanel"
        role="tabpanel"
        aria-labelledby={`config-tab-${tab}`}
        className="pt-6"
      >
        {tab === 'options' && <OptionCatalog />}
        {tab === 'security' && <SecurityGuide />}
        {tab === 'modes' && <PackagingGuide />}
      </div>
    </div>
  );
}
