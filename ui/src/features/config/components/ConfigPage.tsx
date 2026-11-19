// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Fragment, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { Callout } from '@/components/common/Callout';
import { CodeBlock } from '@/components/common/CodeBlock';
import { StateMessage } from '@/components/common/StateMessage';
import { PageHeader } from '@/components/layout/PageHeader';
import { Tabs } from '@/components/ui/tabs';
import { CapacityPanel } from '@/features/control/dashboard/CapacityPanel';
import { Input } from '@/components/ui/input';
import { NativeSelect } from '@/components/ui/native-select';
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
  const [open, setOpen] = useState<string | null>(null);

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return CATALOG.filter(
      (o) =>
        (!area || o.area === area) &&
        (!when || o.appliesWhen === when) &&
        (!native || o.native) &&
        (!q || `${o.key} ${envName(o)} ${o.purpose}`.toLowerCase().includes(q))
    );
  }, [query, area, when, native]);

  return (
    <div>
      <Callout title="How configuration works" className="mb-4">
        Most options are read once at start, so change them and restart. A value
        is taken from, in order: a command-line argument, an environment
        variable, then <code>application.yaml</code>. Options marked{' '}
        <em>Live</em> change without a restart. A few are read only as{' '}
        <code>-D</code> JVM system properties. In the native binary and the
        Docker image, some choices are fixed when the image is built.
      </Callout>
      <div className="mb-3 flex flex-wrap gap-2">
        <Input
          aria-label="Search options"
          placeholder="Search by key, variable or purpose"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="max-w-xs"
        />
        <NativeSelect
          aria-label="Area"
          value={area}
          onChange={(e) => setArea(e.target.value)}
        >
          <option value="">All areas</option>
          {AREAS.map((a) => (
            <option key={a}>{a}</option>
          ))}
        </NativeSelect>
        <NativeSelect
          aria-label="Applies"
          value={when}
          onChange={(e) => setWhen(e.target.value)}
        >
          <option value="">Any change type</option>
          <option value="startup">Needs a restart</option>
          <option value="runtime">Changes live</option>
        </NativeSelect>
        <NativeSelect
          aria-label="Mode"
          value={native}
          onChange={(e) => setNative(e.target.value)}
        >
          <option value="">JVM, native and Docker</option>
          <option value="native">Differs in native and Docker</option>
        </NativeSelect>
      </div>
      {rows.length === 0 ? (
        <StateMessage tone="neutral" title="No options match.">
          Clear the search or a filter.
        </StateMessage>
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Option</TableHead>
                <TableHead>Default</TableHead>
                <TableHead>Applies</TableHead>
                <TableHead>Area</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((o) => {
                const expanded = open === o.key;
                return (
                  <Fragment key={o.key}>
                    <TableRow>
                      <TableCell>
                        <button
                          type="button"
                          aria-expanded={expanded}
                          onClick={() => setOpen(expanded ? null : o.key)}
                          className="text-code-sm text-left font-medium"
                        >
                          {o.key}
                        </button>
                        <p className="text-body-sm text-on-surface-variant">
                          {o.purpose}
                        </p>
                      </TableCell>
                      <TableCell className="text-code-sm">
                        {o.default}
                      </TableCell>
                      <TableCell>{WHEN_LABEL[o.appliesWhen]}</TableCell>
                      <TableCell>{o.area}</TableCell>
                    </TableRow>
                    {expanded && (
                      <TableRow>
                        <TableCell colSpan={4}>
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

/** What can change without a restart. Everything else in the catalog is read once at start. */
function RuntimeTab() {
  return (
    <div className="max-w-2xl space-y-4">
      <Callout title="Changes here are live, but not saved">
        These limits apply at once and are lost when the server restarts; it
        starts again with <code>yukta.execution.max-concurrent</code> and{' '}
        <code>yukta.execution.max-concurrent-per-workflow</code>. Every other
        option needs a restart.
      </Callout>
      <CapacityPanel live />
    </div>
  );
}

type Tab = 'options' | 'runtime';
const TABS = [
  { id: 'options', label: 'Options' },
  { id: 'runtime', label: 'Runtime' },
] as const;

/** Every way to configure Yukta, and the few settings that change while it runs. */
export function ConfigPage() {
  const [params, setParams] = useSearchParams();
  const tab: Tab = params.get('tab') === 'runtime' ? 'runtime' : 'options';
  const choose = (next: Tab) => {
    const updated = new URLSearchParams(params);
    if (next === 'options') updated.delete('tab');
    else updated.set('tab', next);
    setParams(updated, { replace: true });
  };
  return (
    <div>
      <PageHeader
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
        className="pt-4"
      >
        {tab === 'runtime' ? <RuntimeTab /> : <OptionCatalog />}
      </div>
    </div>
  );
}
