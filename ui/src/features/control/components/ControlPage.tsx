// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useSearchParams } from 'react-router';
import { PageHeader } from '@/components/layout/PageHeader';
import { DashboardView } from '../dashboard/DashboardView';
import { SupervisorCockpit } from '../supervisors/SupervisorCockpit';
import { ControlAuditPanel } from './ControlAuditPanel';
import { ControlBusPanel } from './ControlBusPanel';
import { SchedulerPanel } from './SchedulerPanel';
import { WorkersPanel } from './WorkersPanel';
import { ActivityPanel } from './ActivityPanel';
import { ControlEventsProvider } from '../context/ControlEvents';

/** The tabs: the dashboard with the tables and audit log under it, and the live activity feed. */
const TABS = ['overview', 'activity'] as const;
type Tab = (typeof TABS)[number];

/** Mission Control: how the server is doing, who is steering runs, and every control command. */
export function ControlPage() {
  const [params, setParams] = useSearchParams();
  const requested = params.get('tab');
  const tab: Tab = TABS.find((t) => t === requested) ?? 'overview';
  const show = (next: Tab) => {
    const updated = new URLSearchParams(params);
    if (next === 'overview') updated.delete('tab');
    else updated.set('tab', next);
    setParams(updated, { replace: true });
  };
  return (
    <ControlEventsProvider>
      <div className="flex flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
        <PageHeader
          backdrop
          title="Control bus"
          description="How the server is doing, who and what is steering runs, and every control command with who sent it."
          actions={
            <div role="tablist" className="border-border flex gap-4 border-b">
              {TABS.map((name) => (
                <button
                  key={name}
                  type="button"
                  role="tab"
                  aria-selected={tab === name}
                  onClick={() => show(name)}
                  className={`-mb-px h-9 border-b-2 px-0.5 text-label-md capitalize ${
                    tab === name
                      ? 'border-primary text-on-surface'
                      : 'border-transparent text-on-surface-variant hover:text-on-surface'
                  }`}
                >
                  {name}
                </button>
              ))}
            </div>
          }
        />
        {tab === 'activity' && <ActivityPanel />}
        {tab === 'overview' && (
          <>
            <DashboardView />
            {/* ponytail: the tables below stay until a tables-as-dialogs pass replaces them */}
            <ControlBusPanel />
            <SchedulerPanel />
            <SupervisorCockpit />
            <WorkersPanel />
            <ControlAuditPanel />
          </>
        )}
      </div>
    </ControlEventsProvider>
  );
}
