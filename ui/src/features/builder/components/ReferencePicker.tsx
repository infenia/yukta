// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { StepReference } from '../lib/testPins';

/** Lists the steps before this one and the fields of their last test output; a click inserts `payload.<field>`. */
export function ReferencePicker({
  references,
  onPick,
}: {
  references: StepReference[];
  onPick: (expression: string) => void;
}) {
  return (
    <details className="text-body-sm">
      <summary
        className="text-primary cursor-pointer"
        aria-label="Insert a value from an earlier step"
      >
        {'{}'}
      </summary>
      <div className="mt-1 flex flex-col gap-2">
        {references.map(({ nodeId, paths }) => (
          <div key={nodeId}>
            <span className="text-code-sm font-semibold">{nodeId}</span>
            {paths.length === 0 ? (
              <p className="text-muted-foreground">
                No output yet. Run this step from its Test tab.
              </p>
            ) : (
              <ul className="flex flex-wrap gap-1">
                {paths.map((path) => (
                  <li key={path}>
                    <button
                      type="button"
                      onClick={() => onPick(`payload.${path}`)}
                      className="text-code-sm bg-surface-container rounded-full px-2 py-0.5"
                    >
                      {path}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ))}
      </div>
    </details>
  );
}
