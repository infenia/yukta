// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Card } from '@/components/ui/card';
import { formatClockTime, TIME_PLACEHOLDER } from '../lib/timeFormat';
import type { WorkflowProgress } from '../types/workflow';

function SummaryRow({
  label,
  value,
  mono = true,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="flex items-center justify-between gap-4 py-1">
      <span className="text-body-sm text-on-surface-variant">{label}</span>
      <span
        className={
          mono
            ? 'truncate text-code-sm text-on-surface'
            : 'text-label-sm font-semibold text-on-surface'
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
      <h3 className="mb-2 text-overline">Execution summary</h3>
      <SummaryRow label="Execution ID" value={progress?.executionId ?? '—'} />
      <SummaryRow label="Session ID" value={progress?.sessionId ?? sessionId} />
      <SummaryRow
        label="Status"
        value={progress?.status ?? 'NOT RUN'}
        mono={false}
      />
      <SummaryRow
        label="Start Time"
        value={
          progress ? formatClockTime(progress.startTime) : TIME_PLACEHOLDER
        }
      />
      <SummaryRow
        label="End Time"
        value={progress ? formatClockTime(progress.endTime) : TIME_PLACEHOLDER}
      />
    </Card>
  );
}
