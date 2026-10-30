// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { CodeBlock } from '@/components/common/CodeBlock';
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
import { getHeartbeats, type BusNode } from '../api/controlBus';
import { relativeTime } from './WorkersPanel';
import { SendCommandDialog } from './SendCommandDialog';

/** Every node on the control bus with the age of its last heartbeat, and a command to send it. */
export function ControlBusPanel() {
  const [target, setTarget] = useState<BusNode | null>(null);
  const [response, setResponse] = useState<unknown>(undefined);
  const beats = useQuery({
    queryKey: ['control', 'heartbeats'],
    queryFn: getHeartbeats,
    refetchInterval: 5000,
  });

  if (beats.error) {
    return (
      <StateMessage
        tone="error"
        title="Failed to load nodes"
        onRetry={() => void beats.refetch()}
      />
    );
  }
  if (beats.isPending) {
    return <StateMessage tone="loading" title="Loading…" compact />;
  }
  if (beats.data.length === 0) {
    return (
      <StateMessage title="No active nodes.">
        Nodes appear here once a workflow is running.
      </StateMessage>
    );
  }

  // The server's order changes between polls; a fixed one keeps rows from jumping
  const rows = [...beats.data].sort(
    (a, b) =>
      a.workflowId.localeCompare(b.workflowId) ||
      a.nodeId.localeCompare(b.nodeId) ||
      a.executionId.localeCompare(b.executionId)
  );

  return (
    <section aria-labelledby="bus-heading" className="flex flex-col gap-3">
      <h2 id="bus-heading" className="text-headline-md text-on-surface">
        Live nodes{' '}
        <span className="text-on-surface-variant">{rows.length}</span>
      </h2>
      <TableFrame>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Node</TableHead>
              <TableHead>Workflow</TableHead>
              <TableHead>Run</TableHead>
              <TableHead>Last heartbeat</TableHead>
              <TableHead>
                <span className="sr-only">Actions</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((beat) => (
              <TableRow
                key={`${beat.workflowId}\0${beat.executionId}\0${beat.nodeId}`}
              >
                <TableCell className="text-code-sm">{beat.nodeId}</TableCell>
                <TableCell className="text-code-sm">
                  {beat.workflowId}
                </TableCell>
                <TableCell
                  className="text-code-sm text-on-surface-variant"
                  title={beat.executionId || undefined}
                >
                  {beat.executionId.slice(0, 8) || '—'}
                </TableCell>
                <TableCell className="text-on-surface-variant">
                  {relativeTime(beat.timestamp)}
                </TableCell>
                <TableCell className="text-right">
                  <Button
                    type="button"
                    variant="outline"
                    aria-label={`Send command to ${beat.nodeId}`}
                    onClick={() => {
                      setResponse(undefined);
                      setTarget(beat);
                    }}
                  >
                    Send command…
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableFrame>
      {response !== undefined && (
        <div className="flex flex-col gap-1">
          <p className="text-body-sm text-on-surface-variant">
            Response.{' '}
            <a href="#audit-heading" className="text-primary hover:underline">
              Find it in the audit log
            </a>
          </p>
          <CodeBlock
            code={JSON.stringify(response, null, 2)}
            label="response"
          />
        </div>
      )}
      {target && (
        <SendCommandDialog
          workflowId={target.workflowId}
          nodeId={target.nodeId}
          onClose={() => setTarget(null)}
          onSent={(payload) => {
            setResponse(payload ?? null);
            setTarget(null);
          }}
        />
      )}
    </section>
  );
}
