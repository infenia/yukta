// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

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
    <div
      className="border-outline-variant rounded-xl border p-4"
      data-testid="execution-result"
    >
      <h3 className="text-on-surface mb-2 flex items-center gap-2 text-xs font-semibold tracking-wider">
        RESULT
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
        <p className="text-on-surface-variant text-xs">
          {complete
            ? 'The execution produced no result.'
            : 'Waiting for a result…'}
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {results.map((result) => (
            <li key={result.sequence}>
              <span className="text-on-surface-variant text-xs">
                {result.nodeId}
              </span>
              <pre className="bg-surface-container text-on-surface max-h-48 overflow-auto rounded p-2 font-mono text-xs break-all whitespace-pre-wrap">
                {formatPayload(result.payload)}
              </pre>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
