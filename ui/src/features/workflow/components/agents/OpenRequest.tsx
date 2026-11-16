// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { getInput } from '../../api/inputs';
import { InputRow } from '../InputRow';

/**
 * A request an agent waits on, answered where it is asked: the same row the inputs panel shows,
 * once the request has been read. Until then, or if it cannot be read, the prompt is shown as text.
 */
export function OpenRequest({
  executionId,
  requestId,
  prompt,
}: {
  executionId: string;
  requestId: string;
  prompt: string;
}) {
  const request = useQuery({
    queryKey: ['inputs', 'request', executionId, requestId],
    queryFn: () => getInput(executionId, requestId),
  });
  if (!request.data) {
    return (
      <p className="break-words whitespace-pre-wrap">
        <span className="font-medium">Request (open)</span>: {prompt}
      </p>
    );
  }
  return (
    <ul aria-label="Request the agent waits on">
      <InputRow request={request.data} />
    </ul>
  );
}
