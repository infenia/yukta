// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { Card } from '@/components/ui/card';
import { DateTimeStamp } from './DateTimeStamp';
import { toStatusDisplay } from '../lib/statusMapper';
import type { WorkflowProgress } from '../types/workflow';

function SummaryRow({
  label,
  value,
  mono = true,
}: {
  label: string;
  value: ReactNode;
  mono?: boolean;
}) {
  return (
    <div className="flex items-start justify-between gap-4 py-1">
      <span className="text-body-sm text-on-surface-variant">{label}</span>
      <span
        className={
          mono
            ? 'text-code-sm text-on-surface text-right break-all'
            : 'text-label-sm text-on-surface font-semibold'
        }
      >
        {value}
      </span>
    </div>
  );
}

/** The bordered EXECUTION SUMMARY key/value block of the progress panel. */
export function ExecutionSummaryBlock({
  progress,
  sessionId,
}: {
  progress: WorkflowProgress | null;
  sessionId: string;
}) {
  return (
    <Card className="p-4" data-testid="execution-summary">
      <h3 className="text-overline mb-2">Execution summary</h3>
      <SummaryRow label="Execution ID" value={progress?.executionId ?? '—'} />
      <SummaryRow label="Session ID" value={progress?.sessionId ?? sessionId} />
      <SummaryRow
        label="Status"
        value={toStatusDisplay(progress?.status).label}
        mono={false}
      />
      <SummaryRow
        label="Start Time"
        value={<DateTimeStamp iso={progress?.startTime} />}
      />
      <SummaryRow
        label="End Time"
        value={<DateTimeStamp iso={progress?.endTime} />}
      />
    </Card>
  );
}
