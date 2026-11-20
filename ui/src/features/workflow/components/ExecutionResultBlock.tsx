// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { CodeBlock } from '@/components/common/CodeBlock';
import { PanelSection } from './PanelSection';
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
    <div data-testid="execution-result">
      <PanelSection
        title={
          <>
            Output
            {isStreaming && (
              <span
                role="status"
                className="text-label-sm text-primary"
                data-testid="result-streaming"
              >
                LIVE
              </span>
            )}
          </>
        }
      >
        <p className="text-body-sm text-on-surface-variant">
          What the run&apos;s final nodes produced, live.
        </p>
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
                <CodeBlock
                  title={result.nodeId ?? undefined}
                  code={formatPayload(result.payload)}
                  label={`${result.nodeId} result`}
                  grow
                />
              </li>
            ))}
          </ul>
        )}
      </PanelSection>
    </div>
  );
}
