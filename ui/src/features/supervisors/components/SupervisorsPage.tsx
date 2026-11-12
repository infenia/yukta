// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link, Navigate, useSearchParams } from 'react-router';
import { PageHeader } from '@/components/layout/PageHeader';
import { buttonVariants } from '@/components/ui/button';
import { Sheet, SheetContent, SheetTitle } from '@/components/ui/sheet';
import { DecisionMakersTab } from './DecisionMakersTab';
import { ConflictsBanner } from './ConflictsBanner';
import { SupervisorsBrowser } from './SupervisorsBrowser';

/**
 * Supervisors: the list and the detail of each. The decision maker types open in a sheet
 * (`?tab=decision-makers&type=<id>`, the old link); the activity lives on the Control page.
 */
export function SupervisorsPage() {
  const [params, setParams] = useSearchParams();
  if (params.get('tab') === 'activity') {
    return <Navigate to="/control?tab=activity" replace />;
  }
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
      <ConflictsBanner />
      <SupervisorsBrowser />
      <Sheet open={typesOpen} onOpenChange={setTypesOpen}>
        <SheetContent className="sm:w-[min(72rem,92vw)]">
          <SheetTitle>Decision maker types</SheetTitle>
          <DecisionMakersTab />
        </SheetContent>
      </Sheet>
    </div>
  );
}
