// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { formatClockTime, TIME_PLACEHOLDER } from '../lib/timeFormat';
import type { WorkflowProgress } from '../types/workflow';

function SummaryRow({ label, value, mono = true }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-4 py-1">
      <span className="text-xs text-on-surface-variant">{label}</span>
      <span
        className={
          mono ? 'truncate font-mono text-xs text-on-surface' : 'text-xs font-semibold text-on-surface'
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
    <div className="border border-outline-variant p-4" data-testid="execution-summary">
      <h3 className="mb-2 text-xs font-semibold tracking-wider text-on-surface">
        EXECUTION SUMMARY
      </h3>
      <SummaryRow label="Execution ID" value={progress?.executionId ?? '—'} />
      <SummaryRow label="Session ID" value={progress?.sessionId ?? sessionId} />
      <SummaryRow label="Status" value={progress?.status ?? 'NOT RUN'} mono={false} />
      <SummaryRow
        label="Start Time"
        value={progress ? formatClockTime(progress.startTime) : TIME_PLACEHOLDER}
      />
      <SummaryRow
        label="End Time"
        value={progress ? formatClockTime(progress.endTime) : TIME_PLACEHOLDER}
      />
    </div>
  );
}
