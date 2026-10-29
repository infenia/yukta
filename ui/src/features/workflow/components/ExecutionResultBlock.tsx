// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { CodeBlock } from '@/components/common/CodeBlock';
import { Card } from '@/components/ui/card';
import { useExecutionResults } from '../hooks/useExecutionResults';

function formatPayload(payload: unknown): string {
  if (typeof payload === 'string') {
    return payload;
  }
  return JSON.stringify(payload, null, 2) ?? '';
}

/** The RESULT block of the progress panel: what reached the terminal nodes, live. */
export function ExecutionResultBlock({
  sessionId,
  executionId,
}: {
  sessionId: string;
  executionId: string | null;
}) {
  const { results, complete, isStreaming } = useExecutionResults(
    sessionId,
    executionId
  );

  if (!executionId) {
    return null;
  }

  return (
    <Card className="p-4" data-testid="execution-result">
      <h3 className="text-overline mb-2 flex items-center gap-2">
        Result
        {isStreaming && (
          <span
            className="text-label-sm text-primary"
            data-testid="result-streaming"
          >
            LIVE
          </span>
        )}
      </h3>
      {results.length === 0 ? (
        <p className="text-body-sm text-on-surface-variant">
          {complete
            ? 'The execution produced no result.'
            : 'Waiting for a result…'}
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {results.map((result) => (
            <li key={result.sequence}>
              <span className="text-body-sm text-on-surface-variant">
                {result.nodeId}
              </span>
              <CodeBlock
                code={formatPayload(result.payload)}
                label={`${result.nodeId} result`}
              />
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
