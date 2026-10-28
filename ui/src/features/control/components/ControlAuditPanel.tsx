// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { fetchApi } from '@/lib/apiClient';
import { StateMessage } from '@/components/common/StateMessage';
import {
  Table,
  TableBody,
  TableCell,
  TableFrame,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';

/** A control command and what became of it. */
export interface ControlAuditEntry {
  time: string;
  executionId: string;
  command: string;
  actor: string | null;
  applied: boolean;
  reason: string | null;
}

const getAudit = () =>
  fetchApi<ControlAuditEntry[]>('/api/control/audit?limit=50');

/** Who sent which control command, newest first. */
export function ControlAuditPanel() {
  const audit = useQuery({
    queryKey: ['control', 'audit'],
    queryFn: getAudit,
    refetchInterval: 5000,
  });

  return (
    <section aria-labelledby="audit-heading" className="flex flex-col gap-2">
      <h2 id="audit-heading" className="text-headline-md text-on-surface">
        Who did what
      </h2>
      {audit.error ? (
        <StateMessage
          tone="error"
          title="Failed to load the control audit"
          onRetry={() => void audit.refetch()}
          compact
        />
      ) : audit.isPending ? (
        <StateMessage tone="loading" title="Loading…" compact />
      ) : audit.data.length === 0 ? (
        <StateMessage
          title="No control commands recorded yet."
          icon="history"
          compact
        />
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Time</TableHead>
                <TableHead>Actor</TableHead>
                <TableHead>Command</TableHead>
                <TableHead>Execution</TableHead>
                <TableHead>Result</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {audit.data.map((entry, index) => (
                <TableRow key={`${entry.time}-${entry.executionId}-${index}`}>
                  <TableCell className="text-on-surface-variant">
                    {new Date(entry.time).toLocaleString()}
                  </TableCell>
                  <TableCell className="text-code-sm">
                    {entry.actor ?? 'api'}
                  </TableCell>
                  <TableCell className="text-code-sm">
                    {entry.command}
                  </TableCell>
                  <TableCell className="text-code-sm text-on-surface-variant">
                    {entry.executionId}
                  </TableCell>
                  <TableCell>
                    {entry.applied
                      ? 'Applied'
                      : `Refused: ${entry.reason ?? ''}`}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableFrame>
      )}
    </section>
  );
}
