// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { NativeSelect } from '@/components/ui/native-select';
import { CodeBlock } from '@/components/common/CodeBlock';
import { StateMessage } from '@/components/common/StateMessage';
import { getExecutionResults } from '../api/getExecutionResults';
import { downloadText } from '../lib/downloadFile';
import type { WorkflowGraph } from '../types/workflow';

/** Results shown before "Load more". */
const PAGE_SIZE = 20;

/** Nodes no edge leaves: where results arrive. */
function terminalNodes(graph: WorkflowGraph): string[] {
  const sources = new Set(graph.edges.map((edge) => edge.source));
  return graph.nodes
    .map((node) => node.nodeId)
    .filter((nodeId) => !sources.has(nodeId));
}

/** The results of a finished run: filter by terminal node, page through them, download them. */
export function ResultsExplorer({
  graph,
  sessionId,
  executionId,
}: {
  graph: WorkflowGraph;
  sessionId: string;
  executionId: string;
}) {
  const [node, setNode] = useState('');
  const [shown, setShown] = useState(PAGE_SIZE);
  const results = useQuery({
    queryKey: ['executions', executionId, 'results'],
    queryFn: () => getExecutionResults(sessionId, executionId),
  });

  if (results.error) {
    return (
      <StateMessage
        tone="error"
        title="Failed to load the results"
        onRetry={() => void results.refetch()}
        compact
      />
    );
  }
  if (results.isPending) {
    return <StateMessage tone="loading" title="Loading…" compact />;
  }

  const all = results.data.events.filter((event) => event.kind === 'RESULT');
  const matching = node ? all.filter((event) => event.nodeId === node) : all;
  const page = matching.slice(0, shown);

  return (
    <div className="flex flex-col gap-3" data-testid="results-explorer">
      <div className="flex flex-wrap items-center gap-2">
        <NativeSelect
          aria-label="Filter by node"
          value={node}
          onChange={(event) => {
            setNode(event.target.value);
            setShown(PAGE_SIZE);
          }}
        >
          <option value="">All terminal nodes</option>
          {terminalNodes(graph).map((id) => (
            <option key={id}>{id}</option>
          ))}
        </NativeSelect>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={matching.length === 0}
          onClick={() =>
            downloadText(
              `results-${executionId}.json`,
              JSON.stringify(
                matching.map(({ nodeId, sequence, timestamp, payload }) => ({
                  nodeId,
                  sequence,
                  timestamp,
                  payload,
                })),
                null,
                2
              ),
              'application/json'
            )
          }
        >
          Download JSON
        </Button>
      </div>
      {results.data.truncated && (
        <p className="text-body-sm text-on-surface-variant">
          Some results were cut because they were too large.
        </p>
      )}
      {matching.length === 0 ? (
        <p className="text-body-sm text-on-surface-variant">
          No results{node ? ` from ${node}` : ''}.
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {page.map((result) => (
            <li key={result.sequence}>
              <details>
                <summary className="cursor-pointer text-body-sm text-on-surface-variant">
                  {result.nodeId} · #{result.sequence}
                </summary>
                <CodeBlock
                  code={JSON.stringify(result.payload, null, 2)}
                  label={`${result.nodeId} result ${result.sequence}`}
                />
              </details>
            </li>
          ))}
        </ul>
      )}
      {matching.length > page.length && (
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => setShown((count) => count + PAGE_SIZE)}
        >
          Load more ({matching.length - page.length} left)
        </Button>
      )}
    </div>
  );
}
