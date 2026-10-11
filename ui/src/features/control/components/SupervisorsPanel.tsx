// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { fetchApi } from '@/lib/apiClient';

/** A supervisor or control-plane plugin running on the control bus. */
export interface Supervisor {
  id: string;
  kind: 'CONTROL_PLANE' | 'SUPERVISOR';
  type: string;
  order: number;
  dryRun: boolean;
  maxCommandsPerSecond: number;
  applied: number;
  rejected: number;
  recorded: number;
  rateLimited: number;
}

const getSupervisors = () => fetchApi<Supervisor[]>('/api/supervisors');

const KIND_LABEL: Record<Supervisor['kind'], string> = {
  CONTROL_PLANE: 'Control plane',
  SUPERVISOR: 'Supervisor',
};

/** Supervisors and control planes with how many commands each sent since startup. */
export function SupervisorsPanel() {
  const supervisors = useQuery({
    queryKey: ['control', 'supervisors'],
    queryFn: getSupervisors,
    refetchInterval: 5000,
  });

  return (
    <section
      aria-labelledby="supervisors-heading"
      className="flex flex-col gap-2"
    >
      <h2
        id="supervisors-heading"
        className="text-on-surface text-lg font-semibold"
      >
        Supervisors and control planes
      </h2>
      {supervisors.error ? (
        <p role="alert" className="text-error">
          Failed to load supervisors
        </p>
      ) : supervisors.isPending ? (
        <p className="text-on-surface-variant">Loading…</p>
      ) : supervisors.data.length === 0 ? (
        <p className="text-on-surface-variant">
          None running. Enable a rule supervisor or a control plane in the
          server configuration.
        </p>
      ) : (
        <table className="border-outline-variant w-full border text-left text-sm">
          <thead className="bg-surface-container-low text-on-surface-variant">
            <tr>
              <th scope="col" className="p-2 font-semibold">
                Id
              </th>
              <th scope="col" className="p-2 font-semibold">
                Kind
              </th>
              <th scope="col" className="p-2 font-semibold">
                Type
              </th>
              <th scope="col" className="p-2 font-semibold">
                Order
              </th>
              <th scope="col" className="p-2 font-semibold">
                Mode
              </th>
              <th scope="col" className="p-2 font-semibold">
                Applied
              </th>
              <th scope="col" className="p-2 font-semibold">
                Refused
              </th>
              <th scope="col" className="p-2 font-semibold">
                Recorded
              </th>
              <th scope="col" className="p-2 font-semibold">
                Dropped
              </th>
            </tr>
          </thead>
          <tbody className="divide-outline-variant divide-y">
            {supervisors.data.map((supervisor) => (
              <tr key={supervisor.id}>
                <td className="text-on-surface p-2 font-mono">
                  {supervisor.id}
                </td>
                <td className="text-on-surface p-2">
                  {KIND_LABEL[supervisor.kind]}
                </td>
                <td className="text-on-surface-variant p-2 font-mono">
                  {supervisor.type}
                </td>
                <td className="text-on-surface p-2">{supervisor.order}</td>
                <td className="text-on-surface p-2">
                  {supervisor.dryRun ? 'Dry run' : 'Live'}
                  <span className="text-on-surface-variant">
                    {' '}
                    · max {supervisor.maxCommandsPerSecond}/s
                  </span>
                </td>
                <td className="text-on-surface p-2">{supervisor.applied}</td>
                <td className="text-on-surface p-2">{supervisor.rejected}</td>
                <td className="text-on-surface p-2">{supervisor.recorded}</td>
                <td className="text-on-surface p-2">
                  {supervisor.rateLimited}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
