// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Tip } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { copyIcon, copyTone, useCopyCommand } from '../hooks/useCopyCommand';
import { formatDuration } from '../lib/timeFormat';
import { toStatusDisplay } from '../lib/statusMapper';
import { DateTimeStamp, Stamp } from './DateTimeStamp';
import { PanelSection } from './PanelSection';
import type { WorkflowProgress } from '../types/workflow';

/** An identifier on its own line, in mono, with a copy icon that confirms in place. */
function CopyValue({ label, value }: { label: string; value: string }) {
  const { status, copy } = useCopyCommand();
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-overline text-on-surface-variant">{label}</dt>
      <dd className="flex items-start justify-between gap-2">
        <span className="text-code-sm text-on-surface min-w-0 break-all">
          {value}
        </span>
        <Tip
          label={
            status === 'copied'
              ? 'Copied'
              : status === 'failed'
                ? 'Copy failed'
                : `Copy ${label.toLowerCase()}`
          }
        >
          <button
            type="button"
            aria-label={`Copy ${label.toLowerCase()}`}
            onClick={() => copy(value)}
            className={cn(
              'transition-standard flex size-7 shrink-0 items-center justify-center rounded-lg',
              status
                ? copyTone(status)
                : 'text-on-surface-variant hover:bg-muted hover:text-on-surface'
            )}
          >
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-base"
            >
              {copyIcon(status)}
            </span>
          </button>
        </Tip>
      </dd>
    </div>
  );
}

/** What identifies the run and when it ran, as two foldable sections like the node panel's. */
export function ExecutionSummaryBlock({
  progress,
  sessionId,
}: {
  progress: WorkflowProgress | null;
  sessionId: string;
}) {
  return (
    <div data-testid="execution-summary">
      <PanelSection title="Execution summary">
        <dl className="flex flex-col gap-3">
          <CopyValue
            label="Execution ID"
            value={progress?.executionId ?? '—'}
          />
          <CopyValue
            label="Session ID"
            value={progress?.sessionId ?? sessionId}
          />
          <Stamp label="Status">
            {toStatusDisplay(progress?.status).label}
          </Stamp>
        </dl>
      </PanelSection>
      <div className="mt-3">
        <PanelSection title="Timing">
          <dl className="flex flex-wrap gap-x-6 gap-y-2">
            <Stamp label="Start">
              <DateTimeStamp iso={progress?.startTime} inline />
            </Stamp>
            <Stamp label="End">
              <DateTimeStamp iso={progress?.endTime} inline />
            </Stamp>
            <Stamp label="Dur">
              {formatDuration(progress?.startTime, progress?.endTime)}
            </Stamp>
          </dl>
        </PanelSection>
      </div>
    </div>
  );
}
