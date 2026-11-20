// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Tip } from '@/components/ui/tooltip';
import { iconButton } from '@/components/ui/iconTone';
import { CodeBlock } from '@/components/common/CodeBlock';
import { StateMessage } from '@/components/common/StateMessage';
import { getExecutionResults } from '../api/getExecutionResults';
import { cn } from '@/lib/utils';
import { PanelSection } from './PanelSection';
import { downloadText } from '../lib/downloadFile';

/** Results shown before "Load more". */
const PAGE_SIZE = 20;

/** A filter only earns its row once there are this many results to look through. */
const FILTER_FROM = 6;

/** The results of a finished run: filter by node, page through them, download them. */
export function ResultsExplorer({
  sessionId,
  executionId,
}: {
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
  const needle = node.trim().toLowerCase();
  const matching = needle
    ? all.filter((event) => (event.nodeId ?? '').toLowerCase().includes(needle))
    : all;
  const page = matching.slice(0, shown);

  return (
    <div data-testid="results-explorer">
      <PanelSection
        title="All results"
        actions={
          <>
            <span className="text-body-sm text-muted-foreground tabular-nums">
              {matching.length} {matching.length === 1 ? 'result' : 'results'}
            </span>
            <Tip label="Download JSON">
              <button
                type="button"
                aria-label="Download JSON"
                disabled={matching.length === 0}
                className={cn(
                  iconButton('neutral'),
                  'size-7 self-center rounded-lg disabled:opacity-35'
                )}
                onClick={() =>
                  downloadText(
                    `results-${executionId}.json`,
                    JSON.stringify(
                      matching.map(
                        ({ nodeId, sequence, timestamp, payload }) => ({
                          nodeId,
                          sequence,
                          timestamp,
                          payload,
                        })
                      ),
                      null,
                      2
                    ),
                    'application/json'
                  )
                }
              >
                <span
                  aria-hidden="true"
                  className="material-symbols-outlined text-lg"
                >
                  download
                </span>
              </button>
            </Tip>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <p className="text-body-sm text-on-surface-variant -mb-1">
            Every result of this run, node by node.
          </p>
          {all.length > FILTER_FROM && (
            <input
              type="search"
              aria-label="Filter by node"
              placeholder="Filter nodes"
              value={node}
              onChange={(event) => {
                setNode(event.target.value);
                setShown(PAGE_SIZE);
              }}
              className="bg-muted text-body-md placeholder:text-muted-foreground focus-visible:ring-ring/40 h-8 rounded-full px-3 outline-none focus-visible:ring-2"
            />
          )}
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
                  <CodeBlock
                    title={`${result.nodeId} · #${result.sequence}`}
                    code={JSON.stringify(result.payload, null, 2)}
                    label={`${result.nodeId} result ${result.sequence}`}
                    collapsible
                    grow
                  />
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
      </PanelSection>
    </div>
  );
}
