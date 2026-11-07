// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
import { StateMessage } from '@/components/common/StateMessage';
import { buttonVariants } from '@/components/ui/button';
import { useSupervisors } from '../hooks/useSupervisorTypes';

/** The configured supervisors, those the server configures itself included (read-only). */
export function SupervisorsTab() {
  const { data, isPending, error } = useSupervisors();
  if (isPending) return <StateMessage tone="loading" title="Loading…" />;
  if (error || !data) {
    return (
      <StateMessage tone="error" title="Failed to load the supervisors." />
    );
  }
  return (
    <div className="flex flex-col gap-3">
      <div className="flex justify-end">
        <Link to="/supervisors/new" className={buttonVariants()}>
          New supervisor
        </Link>
      </div>
      {data.length === 0 ? (
        <StateMessage title="No supervisors yet">
          Pick a type in the catalog and create one.
        </StateMessage>
      ) : (
        <ul className="flex flex-col gap-2">
          {data.map((s) => (
            <li key={s.id}>
              <Link
                to={`/supervisors/${encodeURIComponent(s.id)}`}
                className="border-border hover:bg-muted flex flex-wrap items-center gap-3 rounded-lg border px-4 py-3"
              >
                <span className="text-body-md text-on-surface flex-1 font-medium">
                  {s.id}
                </span>
                <code className="text-code-sm text-on-surface-variant">
                  {s.type}
                </code>
                {s.dryRun && <span className="text-label-sm">Dry run</span>}
                {s.system && (
                  <span className="text-label-sm">System · read-only</span>
                )}
                <span className="text-label-sm text-on-surface-variant">
                  {s.attachments} attached
                </span>
                <span className="text-label-sm text-on-surface-variant">
                  {s.applied} applied · {s.rejected} rejected
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
