// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link, Navigate, useSearchParams } from 'react-router';
import { cn } from '@/lib/utils';
import { PageHeader } from '@/components/layout/PageHeader';
import { buttonVariants } from '@/components/ui/button';
import { Sheet, SheetContent, SheetTitle } from '@/components/ui/sheet';
import { DecisionMakersTab } from './DecisionMakersTab';
import { SupervisionOverview } from './SupervisionOverview';
import { SupervisorsBrowser } from './SupervisorsBrowser';

const LENSES = [
  { id: 'supervisors', label: 'By supervisor', icon: 'shield_person' },
  { id: 'map', label: 'Map', icon: 'hub' },
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
      className="border-border bg-muted/50 inline-flex w-fit rounded-xl border p-0.5"
    >
      {LENSES.map((lens) => (
        <Link
          key={lens.id}
          to={hrefFor(lens.id)}
          replace
          aria-current={value === lens.id ? 'page' : undefined}
          className={cn(
            'text-label-md transition-standard flex items-center gap-1.5 rounded-lg px-3 py-1.5',
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
 * Supervisors: the list and the detail of each. The decision maker types open in a sheet
 * (`?tab=decision-makers&type=<id>`, the old link); the activity lives on the Control page.
 */
export function SupervisorsPage() {
  const [params, setParams] = useSearchParams();
  if (params.get('tab') === 'activity') {
    return <Navigate to="/control?tab=activity" replace />;
  }
  const map = params.get('view') === 'map';
  const typesOpen = params.get('tab') === 'decision-makers';
  const setTypesOpen = (open: boolean) => {
    const next = new URLSearchParams(params);
    if (open) next.set('tab', 'decision-makers');
    else {
      next.delete('tab');
      next.delete('type');
    }
    setParams(next, { replace: true });
  };
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
            <button
              type="button"
              onClick={() => setTypesOpen(true)}
              className={buttonVariants({ variant: 'outline' })}
            >
              Decision maker types
            </button>
            <Link to="/supervisors/new" className={buttonVariants()}>
              New supervisor
            </Link>
          </div>
        }
      />
      <LensSwitch
        value={map ? 'map' : 'supervisors'}
        hrefFor={(lens) => {
          const next = new URLSearchParams(params);
          if (lens === 'map') {
            next.set('view', 'map');
            next.delete('supervisor');
            next.delete('section');
          } else next.delete('view');
          return `/supervisors?${next}`;
        }}
      />
      {map ? <SupervisionOverview /> : <SupervisorsBrowser />}
      <Sheet open={typesOpen} onOpenChange={setTypesOpen}>
        <SheetContent className="sm:w-[min(72rem,92vw)]">
          <SheetTitle>Decision maker types</SheetTitle>
          <DecisionMakersTab />
        </SheetContent>
      </Sheet>
    </div>
  );
}
