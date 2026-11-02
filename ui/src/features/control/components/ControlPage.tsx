// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useSearchParams } from 'react-router';
import { PageHeader } from '@/components/layout/PageHeader';
import { DashboardView } from '../dashboard/DashboardView';
import { SupervisorCockpit } from '../supervisors/SupervisorCockpit';
import { ControlAuditPanel } from './ControlAuditPanel';
import { ControlBusPanel } from './ControlBusPanel';
import { SchedulerPanel } from './SchedulerPanel';
import { InputsTile } from './InputsTile';
import { WorkersPanel } from './WorkersPanel';
import { ActivityPanel } from './ActivityPanel';
import { ControlEventsProvider } from '../context/ControlEvents';

/**
 * The tabs: the dashboard (with the detail tables under it), the live activity feed and the
 * control audit.
 */
const TABS = ['overview', 'activity', 'audit'] as const;
type Tab = (typeof TABS)[number];

/** Mission Control: how the server is doing, who is steering runs, and every control command. */
export function ControlPage() {
  const [params, setParams] = useSearchParams();
  const requested = params.get('tab');
  // an old `?execution=<id>` link (the run view's) means the audit of that run
  const tab: Tab =
    TABS.find((t) => t === requested) ??
    (params.has('execution') ? 'audit' : 'overview');
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
                  id={`control-tab-${name}`}
                  type="button"
                  role="tab"
                  aria-selected={tab === name}
                  aria-controls="control-tabpanel"
                  onClick={() => show(name)}
                  className={`text-label-md -mb-px h-9 border-b-2 px-0.5 capitalize ${
                    tab === name
                      ? 'border-primary text-on-surface'
                      : 'text-on-surface-variant hover:text-on-surface border-transparent'
                  }`}
                >
                  {name}
                </button>
              ))}
            </div>
          }
        />
        <div
          id="control-tabpanel"
          role="tabpanel"
          aria-labelledby={`control-tab-${tab}`}
          className="flex flex-col gap-4"
        >
          {tab === 'activity' && <ActivityPanel />}
          {tab === 'audit' && <ControlAuditPanel />}
          {tab === 'overview' && (
            <>
              <InputsTile />
              <DashboardView />
              {/* the detail tables: sending a node command, per-workflow limits, the supervisor
                  cockpit (#1591) and each worker's pools and plugins */}
              <ControlBusPanel />
              <SchedulerPanel />
              <SupervisorCockpit />
              <WorkersPanel />
            </>
          )}
        </div>
      </div>
    </ControlEventsProvider>
  );
}
