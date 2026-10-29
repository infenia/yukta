// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useSearchParams } from 'react-router';
import { PageHeader } from '@/components/layout/PageHeader';
import { SupervisorsPanel } from './SupervisorsPanel';
import { ControlAuditPanel } from './ControlAuditPanel';
import { ControlBusPanel } from './ControlBusPanel';
import { SchedulerPanel } from './SchedulerPanel';
import { WorkersPanel } from './WorkersPanel';
import { ActivityPanel } from './ActivityPanel';
import { ControlEventsProvider } from '../context/ControlEvents';

/** The control bus: its nodes (heartbeat, commands), supervisors, workers and the audit log. */
export function ControlPage() {
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') === 'activity' ? 'activity' : 'overview';
  const show = (next: string) => {
    const updated = new URLSearchParams(params);
    if (next === 'overview') updated.delete('tab');
    else updated.set('tab', next);
    setParams(updated, { replace: true });
  };
  return (
    <ControlEventsProvider>
      <div className="flex flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
        <PageHeader
          title="Control Bus"
          description="Nodes registered on the control bus, supervisors, workers, who sent which command, and the live activity feed."
        />
        <div role="tablist" className="flex gap-1">
          {(['overview', 'activity'] as const).map((name) => (
            <button
              key={name}
              type="button"
              role="tab"
              aria-selected={tab === name}
              onClick={() => show(name)}
              className={`text-label-md rounded-lg px-3 py-1 capitalize ${
                tab === name
                  ? 'bg-muted text-on-surface'
                  : 'text-on-surface-variant hover:bg-muted'
              }`}
            >
              {name}
            </button>
          ))}
        </div>
        {tab === 'activity' ? (
          <ActivityPanel />
        ) : (
          <>
            <ControlBusPanel />
            <SchedulerPanel />
            <SupervisorsPanel />
            <WorkersPanel />
            <ControlAuditPanel />
          </>
        )}
      </div>
    </ControlEventsProvider>
  );
}
