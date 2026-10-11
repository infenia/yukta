// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api, fetchApi } from '@/lib/apiClient';
import { CodeBlock } from '@/components/common/CodeBlock';
import { PageHeader } from '@/components/layout/PageHeader';
import { StateMessage } from '@/components/common/StateMessage';

const getNodes = (workflowId: string) =>
  fetchApi<string[]>(
    workflowId
      ? api`/api/control/workflows/${workflowId}/nodes`
      : '/api/control/nodes'
  );

const getHeartbeat = (workflowId: string, nodeId: string) =>
  fetchApi<unknown>(
    api`/api/control/workflows/${workflowId}/nodes/${nodeId}/heartbeat`
  );

function Heartbeat({
  workflowId,
  nodeId,
}: {
  workflowId: string;
  nodeId: string;
}) {
  const beat = useQuery({
    queryKey: ['control', workflowId, nodeId, 'heartbeat'],
    queryFn: () => getHeartbeat(workflowId, nodeId),
  });
  if (beat.isPending)
    return <span className="text-on-surface-variant">Loading…</span>;
  if (beat.error) return <span className="text-error">No heartbeat</span>;
  return (
    <div className="mt-2">
      <CodeBlock code={JSON.stringify(beat.data, null, 2)} label="heartbeat" />
    </div>
  );
}

/** Nodes registered on the control bus; filter by workflow id to inspect a node's heartbeat. */
export function ControlPage() {
  const [workflowId, setWorkflowId] = useState('');
  const [opened, setOpened] = useState<string | null>(null);
  const nodes = useQuery({
    queryKey: ['control', 'nodes', workflowId],
    queryFn: () => getNodes(workflowId),
    refetchInterval: 5000,
  });

  return (
    <div className="flex flex-1 flex-col gap-4 p-6">
      <PageHeader
        title="Control Bus"
        description="Nodes registered on the control bus. Filter by workflow id to inspect heartbeats."
        actions={
          <input
            aria-label="Workflow id"
            placeholder="Filter by workflow id (enables heartbeats)"
            value={workflowId}
            onChange={(event) => {
              setWorkflowId(event.target.value.trim());
              setOpened(null);
            }}
            className="border-outline-variant bg-surface text-on-surface h-8 w-80 max-w-full border px-2 text-sm"
          />
        }
      />

      {nodes.error ? (
        <StateMessage
          tone="error"
          title="Failed to load nodes"
          onRetry={() => void nodes.refetch()}
        />
      ) : nodes.isPending ? (
        <p className="text-on-surface-variant">Loading…</p>
      ) : nodes.data.length === 0 ? (
        <StateMessage title="No active nodes.">
          Nodes appear here once a workflow is running.
        </StateMessage>
      ) : (
        <ul className="divide-outline-variant border-outline-variant flex flex-col divide-y border">
          {nodes.data.map((node) => (
            <li key={node} className="p-3">
              <div className="flex items-center gap-3">
                <span className="text-code-sm text-on-surface">{node}</span>
                {workflowId && (
                  <button
                    type="button"
                    onClick={() => setOpened(opened === node ? null : node)}
                    className="text-label-sm text-primary ml-auto hover:underline"
                  >
                    {opened === node ? 'Hide heartbeat' : 'Heartbeat'}
                  </button>
                )}
              </div>
              {workflowId && opened === node && (
                <Heartbeat workflowId={workflowId} nodeId={node} />
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
