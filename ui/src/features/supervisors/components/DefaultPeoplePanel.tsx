// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { StateMessage } from '@/components/common/StateMessage';
import { getSupervisionSummary } from '../api/supervision';

/** Default (people): the workflows no supervisor owns, which people control directly. */
export function DefaultPeoplePanel() {
  const summary = useQuery({
    queryKey: ['supervision', 'summary'],
    queryFn: getSupervisionSummary,
  });
  if (summary.isPending) {
    return <StateMessage tone="loading" title="Loading…" />;
  }
  if (summary.error || !summary.data) {
    return (
      <StateMessage tone="error" title="Could not load what nobody owns." />
    );
  }
  const { unownedWorkflows } = summary.data;
  return (
    <article className="flex flex-col gap-4">
      <header>
        <h2 className="text-headline-lg text-on-surface">Default (people)</h2>
        <p className="text-body-md text-on-surface-variant mt-1">
          Controls the workflows that no supervisor owns. Each run and node
          listed here is controlled by people directly.
        </p>
      </header>
      {unownedWorkflows.length === 0 ? (
        <StateMessage title="Nothing is unowned" icon="check_circle">
          Every workflow has a supervisor.
        </StateMessage>
      ) : (
        <ul className="border-border bg-card shadow-card divide-border flex flex-col divide-y rounded-xl border">
          {unownedWorkflows.map((w) => (
            <li key={`${w.sessionId}/${w.workflowId}`} className="p-3">
              <Link
                to={`/sessions/${encodeURIComponent(w.sessionId)}/workflow/${encodeURIComponent(w.workflowId)}`}
                className="text-label-md text-primary"
              >
                {w.sessionId} / {w.workflowId}
              </Link>
            </li>
          ))}
        </ul>
      )}
    </article>
  );
}
