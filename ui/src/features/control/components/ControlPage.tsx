// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api, fetchApi } from '@/lib/apiClient';
import { SupervisorsPanel } from './SupervisorsPanel';

const getNodes = (workflowId: string) =>
  fetchApi<string[]>(
    workflowId ? api`/api/control/workflows/${workflowId}/nodes` : '/api/control/nodes'
  );

const getHeartbeat = (workflowId: string, nodeId: string) =>
  fetchApi<unknown>(api`/api/control/workflows/${workflowId}/nodes/${nodeId}/heartbeat`);

function Heartbeat({ workflowId, nodeId }: { workflowId: string; nodeId: string }) {
  const beat = useQuery({
    queryKey: ['control', workflowId, nodeId, 'heartbeat'],
    queryFn: () => getHeartbeat(workflowId, nodeId),
  });
  if (beat.isPending) return <span className="text-on-surface-variant">Loading…</span>;
  if (beat.error) return <span className="text-error">No heartbeat</span>;
  return (
    <pre className="mt-1 max-h-48 overflow-auto border border-outline-variant bg-surface-container-low p-2 text-xs">
      {JSON.stringify(beat.data, null, 2)}
    </pre>
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
    <div className="flex flex-1 flex-col gap-6 p-6">
      <SupervisorsPanel />
      <div className="flex items-center gap-3">
        <h1 className="text-2xl font-bold text-on-surface">Control Bus</h1>
        <input
          aria-label="Workflow id"
          placeholder="Filter by workflow id (enables heartbeats)"
          value={workflowId}
          onChange={(event) => {
            setWorkflowId(event.target.value.trim());
            setOpened(null);
          }}
          className="ml-auto h-8 w-80 border border-outline-variant bg-surface px-2 text-sm text-on-surface"
        />
      </div>

      {nodes.error ? (
        <p role="alert" className="text-error">
          Failed to load nodes
        </p>
      ) : nodes.isPending ? (
        <p className="text-on-surface-variant">Loading…</p>
      ) : nodes.data.length === 0 ? (
        <p className="text-on-surface-variant">No active nodes.</p>
      ) : (
        <ul className="flex flex-col divide-y divide-outline-variant border border-outline-variant">
          {nodes.data.map((node) => (
            <li key={node} className="p-3">
              <div className="flex items-center gap-3">
                <span className="font-mono text-sm text-on-surface">{node}</span>
                {workflowId && (
                  <button
                    type="button"
                    onClick={() => setOpened(opened === node ? null : node)}
                    className="ml-auto text-xs font-semibold text-primary"
                  >
                    {opened === node ? 'Hide heartbeat' : 'Heartbeat'}
                  </button>
                )}
              </div>
              {workflowId && opened === node && <Heartbeat workflowId={workflowId} nodeId={node} />}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
