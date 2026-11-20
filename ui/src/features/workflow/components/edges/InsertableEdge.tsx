// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useContext } from 'react';
import {
  BaseEdge,
  EdgeLabelRenderer,
  getBezierPath,
  type EdgeProps,
} from '@xyflow/react';
import { EdgeAdder } from '../../lib/stepAdders';

/**
 * An edge as the default one draws it, with what the editor gives (`EdgeAdder`) at its middle.
 * An edge to or from a closed stage is not one of the workflow's, so it gets none.
 */
export function InsertableEdge({
  id,
  source,
  target,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  style,
  data,
}: EdgeProps) {
  const adder = useContext(EdgeAdder);
  const [path, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  });
  const ofWorkflow =
    !source.startsWith('stage:') && !target.startsWith('stage:');
  return (
    <>
      <BaseEdge id={id} path={path} markerEnd={markerEnd} style={style} />
      {adder && ofWorkflow && (
        <EdgeLabelRenderer>
          <div
            className="nodrag nopan absolute"
            style={{
              transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
              pointerEvents: 'all',
            }}
          >
            {adder({
              source,
              target,
              sourcePort:
                (data?.sourcePort as string | null | undefined) ?? null,
            })}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}
