// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useSearchParams } from 'react-router';
import { PageHeader } from '@/components/layout/PageHeader';
import { Tabs } from '@/components/ui/tabs';
import { DashboardView } from '../dashboard/DashboardView';
import { ControlAuditPanel } from './ControlAuditPanel';
import { WorkersPanel } from './WorkersPanel';
import { ActivityPanel } from './ActivityPanel';
import { ControlEventsProvider } from '../context/ControlEvents';

/** The tabs: the dashboard, the live activity feed and the control audit. */
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
          title="Dashboard"
          description="How the server is doing, what is running now, and every control command with who sent it."
        />
        <Tabs
          ariaLabel="Dashboard sections"
          idPrefix="control"
          items={TABS.map((id) => ({
            id,
            label: id[0].toUpperCase() + id.slice(1),
          }))}
          value={tab}
          onChange={show}
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
              <DashboardView />
              <WorkersPanel />
            </>
          )}
        </div>
      </div>
    </ControlEventsProvider>
  );
}
