// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { useFallbackPoll } from '../context/ControlEvents';
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
  const poll = useFallbackPoll();
  const supervisors = useQuery({
    queryKey: ['control', 'supervisors'],
    queryFn: getSupervisors,
    refetchInterval: poll,
  });

  return (
    <section
      aria-labelledby="supervisors-heading"
      className="flex flex-col gap-2"
    >
      <h2 id="supervisors-heading" className="text-headline-md text-on-surface">
        Supervisors and control planes
      </h2>
      {supervisors.error ? (
        <StateMessage
          tone="error"
          title="Failed to load supervisors"
          onRetry={() => void supervisors.refetch()}
          compact
        />
      ) : supervisors.isPending ? (
        <StateMessage tone="loading" title="Loading…" compact />
      ) : supervisors.data.length === 0 ? (
        <StateMessage title="None running." icon="shield" compact>
          Enable a rule supervisor or a control plane in the server
          configuration.
        </StateMessage>
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Id</TableHead>
                <TableHead>Kind</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Order</TableHead>
                <TableHead>Mode</TableHead>
                <TableHead>Applied</TableHead>
                <TableHead>Refused</TableHead>
                <TableHead>Recorded</TableHead>
                <TableHead>Dropped</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {supervisors.data.map((supervisor) => (
                <TableRow key={supervisor.id}>
                  <TableCell className="text-code-sm">
                    {supervisor.id}
                  </TableCell>
                  <TableCell>{KIND_LABEL[supervisor.kind]}</TableCell>
                  <TableCell className="text-code-sm text-on-surface-variant">
                    {supervisor.type}
                  </TableCell>
                  <TableCell>{supervisor.order}</TableCell>
                  <TableCell>
                    {supervisor.dryRun ? 'Dry run' : 'Live'}
                    <span className="text-on-surface-variant">
                      {' '}
                      · max {supervisor.maxCommandsPerSecond}/s
                    </span>
                  </TableCell>
                  <TableCell>{supervisor.applied}</TableCell>
                  <TableCell>{supervisor.rejected}</TableCell>
                  <TableCell>{supervisor.recorded}</TableCell>
                  <TableCell>{supervisor.rateLimited}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableFrame>
      )}
    </section>
  );
}
