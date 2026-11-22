// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link, Navigate, useSearchParams } from 'react-router';
import { cn } from '@/lib/utils';
import { PageHeader } from '@/components/layout/PageHeader';
import { buttonVariants } from '@/components/ui/button';
import { DecisionMakersBrowser } from './DecisionMakersBrowser';
import { SupervisionOverview } from './SupervisionOverview';
import { SupervisorsBrowser } from './SupervisorsBrowser';
import { WorkflowsBrowser } from './WorkflowsBrowser';

const LENSES = [
  { id: 'supervisors', label: 'By supervisor', icon: 'shield_person' },
  { id: 'workflows', label: 'By workflow', icon: 'account_tree' },
  { id: 'map', label: 'Map', icon: 'hub' },
  { id: 'decision-makers', label: 'Decision makers', icon: 'category' },
] as const;

/** The ways to look at supervision, as links: the lens is in the URL. */
function LensSwitch({
  value,
  hrefFor,
}: {
  value: (typeof LENSES)[number]['id'];
  hrefFor: (lens: (typeof LENSES)[number]['id']) => string;
}) {
  return (
    <nav
      aria-label="Supervision views"
      className="border-border bg-muted/50 inline-flex w-fit max-w-full overflow-x-auto rounded-xl border p-0.5"
    >
      {LENSES.map((lens) => (
        <Link
          key={lens.id}
          to={hrefFor(lens.id)}
          replace
          aria-current={value === lens.id ? 'page' : undefined}
          className={cn(
            'text-label-md transition-standard flex shrink-0 items-center gap-1.5 rounded-lg px-3 py-1.5 whitespace-nowrap',
            value === lens.id
              ? 'bg-card text-on-surface shadow-card'
              : 'text-on-surface-variant hover:text-on-surface'
          )}
        >
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-base"
          >
            {lens.icon}
          </span>
          {lens.label}
        </Link>
      ))}
    </nav>
  );
}

/**
 * Supervisors: the list and the detail of each, or the same supervision seen by workflow, as a map,
 * or by decision maker type (`?view=`). The old `?tab=decision-makers&type=<id>` link goes to the
 * last; the activity lives on the Control page.
 */
export function SupervisorsPage() {
  const [params] = useSearchParams();
  if (params.get('tab') === 'activity') {
    return <Navigate to="/control?tab=activity" replace />;
  }
  if (params.get('tab') === 'decision-makers') {
    const next = new URLSearchParams(params);
    next.delete('tab');
    next.set('view', 'decision-makers');
    return <Navigate to={`/supervisors?${next}`} replace />;
  }
  const view =
    LENSES.find((l) => l.id === params.get('view'))?.id ?? 'supervisors';
  return (
    <div className="flex flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
      <PageHeader
        backdrop
        title="Supervisors"
        description={
          <>
            Who watches your workflows, what each may do, and who they answer
            to. Usually set up by an AI agent over MCP, the CLI or the API. What
            they did is on the{' '}
            <Link to="/control?tab=activity" className="text-primary">
              Control activity
            </Link>
            .
          </>
        }
        actions={
          <div className="flex flex-wrap gap-2">
            <Link to="/supervisors/new" className={buttonVariants()}>
              <span
                aria-hidden="true"
                className="material-symbols-outlined text-base"
              >
                add
              </span>
              New supervisor
            </Link>
          </div>
        }
      />
      <LensSwitch
        value={view}
        hrefFor={(lens) => {
          const next = new URLSearchParams(params);
          // each lens keeps only its own selection
          for (const key of [
            'supervisor',
            'section',
            'session',
            'workflow',
            'type',
          ]) {
            next.delete(key);
          }
          if (lens === 'supervisors') next.delete('view');
          else next.set('view', lens);
          return `/supervisors?${next}`;
        }}
      />
      {view === 'map' && <SupervisionOverview />}
      {view === 'workflows' && <WorkflowsBrowser />}
      {view === 'supervisors' && <SupervisorsBrowser />}
      {view === 'decision-makers' && <DecisionMakersBrowser />}
    </div>
  );
}
