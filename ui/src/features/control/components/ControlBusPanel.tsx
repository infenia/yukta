// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { NativeSelect } from '@/components/ui/native-select';
import { CodeBlock } from '@/components/common/CodeBlock';
import { StateMessage } from '@/components/common/StateMessage';
import {
  getBusNodes,
  getHeartbeat,
  parseBusNode,
  type BusNode,
} from '../api/controlBus';
import { SendCommandDialog } from './SendCommandDialog';

function Heartbeat({ node }: { node: BusNode }) {
  const beat = useQuery({
    queryKey: ['control', node.workflowId, node.nodeId, 'heartbeat'],
    queryFn: () => getHeartbeat(node.workflowId, node.nodeId),
  });
  if (beat.isPending)
    return <span className="text-on-surface-variant">Loading…</span>;
  if (beat.error) return <span className="text-error">No heartbeat</span>;
  return (
    <CodeBlock code={JSON.stringify(beat.data, null, 2)} label="heartbeat" />
  );
}

/** Pick a workflow and a node on the control bus, see its heartbeat and send it a command. */
export function ControlBusPanel() {
  const [workflowId, setWorkflowId] = useState('');
  const [nodeId, setNodeId] = useState('');
  const [sending, setSending] = useState(false);
  const [response, setResponse] = useState<unknown>(undefined);
  const nodes = useQuery({
    queryKey: ['control', 'nodes'],
    queryFn: getBusNodes,
    refetchInterval: 5000,
  });

  if (nodes.error) {
    return (
      <StateMessage
        tone="error"
        title="Failed to load nodes"
        onRetry={() => void nodes.refetch()}
      />
    );
  }
  if (nodes.isPending) {
    return <StateMessage tone="loading" title="Loading…" compact />;
  }
  if (nodes.data.length === 0) {
    return (
      <StateMessage title="No active nodes.">
        Nodes appear here once a workflow is running.
      </StateMessage>
    );
  }

  const parsed = nodes.data.map(parseBusNode);
  const workflows = [...new Set(parsed.map((n) => n.workflowId))];
  const nodeIds = parsed
    .filter((n) => n.workflowId === workflowId)
    .map((n) => n.nodeId);
  const chosen = workflowId && nodeId ? { workflowId, nodeId } : null;

  return (
    <section aria-labelledby="bus-heading" className="flex flex-col gap-3">
      <h2 id="bus-heading" className="text-headline-md text-on-surface">
        Nodes
      </h2>
      <div className="flex flex-wrap items-center gap-3">
        <NativeSelect
          aria-label="Workflow"
          value={workflowId}
          onChange={(event) => {
            setWorkflowId(event.target.value);
            setNodeId('');
            setResponse(undefined);
          }}
        >
          <option value="">Choose a workflow…</option>
          {workflows.map((id) => (
            <option key={id}>{id}</option>
          ))}
        </NativeSelect>
        <NativeSelect
          aria-label="Node"
          value={nodeId}
          disabled={!workflowId}
          onChange={(event) => {
            setNodeId(event.target.value);
            setResponse(undefined);
          }}
        >
          <option value="">Choose a node…</option>
          {nodeIds.map((id) => (
            <option key={id}>{id}</option>
          ))}
        </NativeSelect>
        <Button
          type="button"
          disabled={!chosen}
          onClick={() => setSending(true)}
        >
          Send command…
        </Button>
      </div>
      {chosen && <Heartbeat node={chosen} />}
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
      {sending && chosen && (
        <SendCommandDialog
          {...chosen}
          onClose={() => setSending(false)}
          onSent={(payload) => {
            setResponse(payload ?? null);
            setSending(false);
          }}
        />
      )}
    </section>
  );
}
