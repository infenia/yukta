// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Callout } from '@/components/common/Callout';
import type { Problem } from './validationIssues';

/**
 * Every problem that stops the workflow, one line each. One about a node has a button that
 * selects the node and opens the inspector where it is fixed.
 */
export function ProblemsPanel({
  problems,
  onGo,
}: {
  problems: Problem[];
  onGo: (nodeId: string, tab: Problem['tab']) => void;
}) {
  if (problems.length === 0) return null;
  return (
    <Callout
      kind="danger"
      compact
      title={
        problems.length === 1 ? '1 problem' : `${problems.length} problems`
      }
    >
      <ul aria-label="Problems" className="flex flex-col gap-1">
        {problems.map((problem, index) => {
          const { nodeId, tab } = problem;
          return (
            <li
              key={`${index}/${problem.message}`}
              className="flex flex-wrap items-baseline gap-x-2"
            >
              <span>{problem.message}</span>
              {nodeId && (
                <button
                  type="button"
                  className="text-primary text-label-md font-semibold hover:underline"
                  onClick={() => onGo(nodeId, tab)}
                >
                  Go to {nodeId}
                </button>
              )}
            </li>
          );
        })}
      </ul>
    </Callout>
  );
}
