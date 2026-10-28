// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { PageHeader } from '@/components/layout/PageHeader';
import { SupervisorsPanel } from './SupervisorsPanel';
import { ControlAuditPanel } from './ControlAuditPanel';
import { ControlBusPanel } from './ControlBusPanel';
import { WorkersPanel } from './WorkersPanel';

/** The control bus: its nodes (heartbeat, commands), supervisors, workers and the audit log. */
export function ControlPage() {
  return (
    <div className="flex flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
      <PageHeader
        title="Control Bus"
        description="Nodes registered on the control bus, supervisors, workers and who sent which command."
      />
      <ControlBusPanel />
      <SupervisorsPanel />
      <WorkersPanel />
      <ControlAuditPanel />
    </div>
  );
}
