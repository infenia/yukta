// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { fetchApi } from '@/lib/apiClient';

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
      <h2 id="audit-heading" className="text-on-surface text-lg font-semibold">
        Who did what
      </h2>
      {audit.error ? (
        <p role="alert" className="text-error">
          Failed to load the control audit
        </p>
      ) : audit.isPending ? (
        <p className="text-on-surface-variant">Loading…</p>
      ) : audit.data.length === 0 ? (
        <p className="text-on-surface-variant">
          No control commands recorded yet.
        </p>
      ) : (
        <table className="border-outline-variant w-full border text-left text-sm">
          <thead className="bg-surface-container-low text-on-surface-variant">
            <tr>
              <th scope="col" className="p-2 font-semibold">
                Time
              </th>
              <th scope="col" className="p-2 font-semibold">
                Actor
              </th>
              <th scope="col" className="p-2 font-semibold">
                Command
              </th>
              <th scope="col" className="p-2 font-semibold">
                Execution
              </th>
              <th scope="col" className="p-2 font-semibold">
                Result
              </th>
            </tr>
          </thead>
          <tbody className="divide-outline-variant divide-y">
            {audit.data.map((entry, index) => (
              <tr key={`${entry.time}-${entry.executionId}-${index}`}>
                <td className="text-on-surface-variant p-2">
                  {new Date(entry.time).toLocaleString()}
                </td>
                <td className="text-on-surface p-2 font-mono">
                  {entry.actor ?? 'api'}
                </td>
                <td className="text-on-surface p-2 font-mono">
                  {entry.command}
                </td>
                <td className="text-on-surface-variant p-2 font-mono">
                  {entry.executionId}
                </td>
                <td className="text-on-surface p-2">
                  {entry.applied ? 'Applied' : `Refused: ${entry.reason ?? ''}`}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
