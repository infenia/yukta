// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { useFallbackPoll } from '../context/ControlEventsContext';
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

/**
 * A key for each row. The server gives an entry no id, and the newest row comes first, so an index
 * would move every key when a row arrives. The content is the key; a repeat of the same command in
 * the same instant gets a number.
 */
export function rowKeys(entries: ControlAuditEntry[]): string[] {
  const seen = new Map<string, number>();
  return entries.map((entry) => {
    const base = `${entry.time}|${entry.executionId}|${entry.command}|${entry.actor}`;
    const count = seen.get(base) ?? 0;
    seen.set(base, count + 1);
    return `${base}|${count}`;
  });
}

const getAudit = () =>
  fetchApi<ControlAuditEntry[]>('/api/control/audit?limit=50');

/** Who sent which control command, newest first. */
export function ControlAuditPanel() {
  const poll = useFallbackPoll();
  const audit = useQuery({
    queryKey: ['control', 'audit'],
    queryFn: getAudit,
    refetchInterval: poll,
  });
  const keys = audit.data ? rowKeys(audit.data) : [];

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
                <TableRow key={keys[index]}>
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
