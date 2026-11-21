// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { CodeBlock } from '@/components/common/CodeBlock';
import { InlineError } from '@/components/common/InlineError';
import { textareaFieldClassName } from '@/components/ui/native-select';
import { useExecutionResults } from '@/features/workflow/hooks/useExecutionResults';
import { startTestRun } from '../api';
import { parseSample, readPin, writePin } from '../lib/testPins';
import type { DraftWorkflow } from '../types';

/**
 * Run the unsaved draft up to this node, or this node alone on a sample, and show what came out.
 * The last sample and output are pinned for this browser tab.
 */
export function TestPanel({
  sessionId,
  workflow,
  nodeId,
}: {
  sessionId: string;
  workflow: DraftWorkflow;
  nodeId: string;
}) {
  const pinned = readPin(sessionId, workflow.workflowId, nodeId);
  const [sample, setSample] = useState(pinned?.sample ?? '{}');
  const [executionId, setExecutionId] = useState<string | null>(null);
  const run = useMutation({
    mutationFn: (stepOnly: boolean) =>
      startTestRun(sessionId, {
        workflow,
        untilNodeId: nodeId,
        stepOnly,
        // the buttons are disabled unless the sample parses
        payload: parseSample(sample) as Record<string, unknown>,
      }),
    onSuccess: (started) => setExecutionId(started.executionId),
  });
  const { results, complete, executionStatus } = useExecutionResults(
    sessionId,
    executionId
  );
  const output = executionId ? results.map((r) => r.payload) : pinned?.output;

  useEffect(() => {
    if (complete) {
      writePin(sessionId, workflow.workflowId, nodeId, {
        sample,
        output: results.map((r) => r.payload),
      });
    }
    // pin once per finished run
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [complete]);

  const valid = parseSample(sample) !== null;
  const running = run.isPending || (executionId !== null && !complete);
  const start = (stepOnly: boolean) => {
    setExecutionId(null);
    run.mutate(stepOnly);
  };
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-1">
        <label
          htmlFor="test-sample"
          className="text-label-sm text-on-surface font-semibold"
        >
          Sample input (JSON)
        </label>
        <textarea
          id="test-sample"
          rows={5}
          value={sample}
          aria-invalid={!valid}
          className={`${textareaFieldClassName} text-code-sm`}
          onChange={(event) => setSample(event.target.value)}
        />
        <span
          className={`text-body-sm ${valid ? 'text-on-surface-variant' : 'text-error'}`}
        >
          {valid
            ? 'The payload the trigger sends, or what "Test this step" feeds the node.'
            : 'The sample must be a JSON object.'}
        </span>
      </div>
      <div className="flex gap-3">
        <button
          type="button"
          disabled={!valid || running}
          onClick={() => start(false)}
          className="text-label-md text-primary disabled:text-muted-foreground hover:underline"
        >
          Run up to here
        </button>
        <button
          type="button"
          disabled={!valid || running}
          onClick={() => start(true)}
          className="text-label-md text-primary disabled:text-muted-foreground hover:underline"
        >
          Test this step
        </button>
      </div>
      {run.isError && <InlineError message={run.error.message} />}
      {executionId && complete && executionStatus && (
        <p className="text-body-sm text-on-surface-variant">
          Finished: {executionStatus}
        </p>
      )}
      {running && <p className="text-body-sm">Running…</p>}
      {output && output.length > 0 && (
        <CodeBlock
          code={JSON.stringify(
            output.length === 1 ? output[0] : output,
            null,
            2
          )}
          label={executionId ? 'output' : 'pinned output'}
        />
      )}
    </div>
  );
}
