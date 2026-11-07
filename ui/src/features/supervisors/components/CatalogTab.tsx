// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link, useSearchParams } from 'react-router';
import { CodeBlock } from '@/components/common/CodeBlock';
import { StateMessage } from '@/components/common/StateMessage';
import { buttonVariants } from '@/components/ui/button';
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
  useSupervisorTypes,
  useSupervisors,
} from '../hooks/useSupervisorTypes';
import type { SupervisorCategory, SupervisorTypeSummary } from '../types';
import { Tip } from '@/components/ui/tooltip';

const CATEGORIES: { key: SupervisorCategory; label: string }[] = [
  { key: 'GUARD', label: 'Guards' },
  { key: 'GATE', label: 'Gates' },
  { key: 'RULES', label: 'Rules' },
  { key: 'MODEL', label: 'Models' },
  { key: 'INTEGRATION', label: 'Integrations' },
  { key: 'COMPOSITION', label: 'Composition' },
  { key: 'WORKFLOW', label: 'Workflows' },
];

const KIND_LABEL = {
  SUPERVISOR: 'Supervisor',
  CONTROL_PLANE: 'Control plane',
  COMPOSITE: 'Composite',
} as const;

const defaultText = (value: unknown) =>
  value == null
    ? '—'
    : typeof value === 'object'
      ? JSON.stringify(value)
      : String(value);

function TypeDetail({ type }: { type: string }) {
  const { data, isPending, error } = useSupervisorType(type);
  if (isPending) return <StateMessage tone="loading" title="Loading…" />;
  if (error || !data) {
    return <StateMessage tone="error" title={`Failed to load ${type}.`} />;
  }
  return (
    <article className="flex min-w-0 flex-col gap-5">
      <header className="flex flex-wrap items-start gap-3">
        <div className="min-w-0 flex-1">
          <h2 className="text-headline-lg text-on-surface">
            {data.displayName}
          </h2>
          <div className="text-body-md text-on-surface-variant mt-1 flex flex-wrap items-center gap-x-3 gap-y-1">
            <span>{KIND_LABEL[data.kind]}</span>
            <code className="text-code-sm select-all">{data.type}</code>
          </div>
        </div>
        <Link
          to={`/supervisors/new?type=${encodeURIComponent(data.type)}`}
          className={buttonVariants()}
        >
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-base"
          >
            add
          </span>
          Create supervisor
        </Link>
      </header>
      <p className="text-body-md text-on-surface max-w-prose">
        {data.description}
      </p>
      {data.usagePattern && (
        <p className="text-body-md text-on-surface-variant max-w-prose">
          {data.usagePattern}
        </p>
      )}
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
            {[...data.configSchema, ...data.commonSchema].map((field) => (
              <TableRow key={field.name}>
                <TableCell className="text-code-sm">
                  {field.name}
                  {field.required && (
                    <Tip label="Required">
                      <span className="text-destructive"> *</span>
                    </Tip>
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
      <section aria-label="Example settings" className="flex flex-col gap-2">
        <h3 className="text-label-md text-on-surface-variant">Example</h3>
        <CodeBlock code={JSON.stringify(data.exampleSettings, null, 2)} />
      </section>
    </article>
  );
}

/** The catalog of supervisor types, grouped by category, with the chosen type's detail beside it. */
export function CatalogTab() {
  const types = useSupervisorTypes();
  const supervisors = useSupervisors();
  const [params, setParams] = useSearchParams();
  if (types.isPending) return <StateMessage tone="loading" title="Loading…" />;
  if (types.error || !types.data) {
    return (
      <StateMessage tone="error" title="Failed to load the supervisor types." />
    );
  }
  const all = types.data;
  const chosen = params.get('type') ?? all[0]?.type ?? null;
  const configured = (type: string) =>
    supervisors.data?.filter((s) => s.type === type).length ?? 0;
  const choose = (type: string) => {
    const next = new URLSearchParams(params);
    next.set('type', type);
    setParams(next, { replace: true });
  };
  const inCategory = (key: SupervisorCategory): SupervisorTypeSummary[] =>
    all.filter((t) => t.category === key);
  if (all.length === 0) {
    return <StateMessage title="No supervisor types are installed." />;
  }
  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,320px)_minmax(0,1fr)]">
      <nav aria-label="Supervisor catalog" className="flex flex-col gap-4">
        {CATEGORIES.filter(({ key }) => inCategory(key).length > 0).map(
          ({ key, label }) => (
            <section key={key} className="flex flex-col gap-1">
              <h3 className="text-label-md text-on-surface-variant px-2">
                {label}
              </h3>
              {inCategory(key).map((t) => (
                <button
                  key={t.type}
                  type="button"
                  aria-current={t.type === chosen ? 'page' : undefined}
                  onClick={() => choose(t.type)}
                  className={cn(
                    'flex items-center gap-3 rounded-lg border px-3 py-2 text-left',
                    t.type === chosen
                      ? 'border-primary bg-accent text-accent-foreground'
                      : 'border-border hover:bg-muted'
                  )}
                >
                  <span
                    aria-hidden="true"
                    className="material-symbols-outlined"
                  >
                    {t.icon ?? 'shield'}
                  </span>
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="text-body-md">{t.displayName}</span>
                    <span className="text-label-sm text-on-surface-variant">
                      {KIND_LABEL[t.kind]} · {configured(t.type)} configured
                    </span>
                  </span>
                </button>
              ))}
            </section>
          )
        )}
      </nav>
      {chosen && <TypeDetail type={chosen} />}
    </div>
  );
}
