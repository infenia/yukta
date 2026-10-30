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
          title="Control bus"
          description="Who and what is steering runs: live nodes and their heartbeats, supervisors, workers, and every control command with who sent it."
          actions={
            <div role="tablist" className="border-border flex gap-4 border-b">
              {(['overview', 'activity'] as const).map((name) => (
                <button
                  key={name}
                  type="button"
                  role="tab"
                  aria-selected={tab === name}
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
