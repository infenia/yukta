// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { StateMessage } from '@/components/common/StateMessage';
import {
  Table,
  TableBody,
  TableCell,
  TableFrame,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { useEdition } from '@/features/edition/hooks/useEdition';
import { useWorkers } from '../api/workers';

/** Plugin types shown per worker before the rest collapse into "+n". */
const VISIBLE_TYPES = 3;

const relative = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

/** "in 12 seconds" / "3 seconds ago" for a timestamp, in the largest fitting unit. */
export function relativeTime(iso: string, now = Date.now()): string {
  const seconds = Math.round((Date.parse(iso) - now) / 1000);
  return Math.abs(seconds) < 60
    ? relative.format(seconds, 'second')
    : relative.format(Math.round(seconds / 60), 'minute');
}

function Chips({ values }: { values: string[] }) {
  const shown = values.slice(0, VISIBLE_TYPES);
  const hidden = values.length - shown.length;
  return (
    <span className="flex flex-wrap gap-1">
      {shown.map((value) => (
        <span
          key={value}
          className="rounded bg-surface-container px-1.5 py-0.5 text-code-sm"
        >
          {value}
        </span>
      ))}
      {hidden > 0 && (
        <span
          className="px-1 text-code-sm text-on-surface-variant"
          title={values.slice(VISIBLE_TYPES).join(', ')}
        >
          +{hidden}
        </span>
      )}
    </span>
  );
}

/** The remote workers with their pools, plugin types and how much of their capacity is in use. */
export function WorkersPanel() {
  const workers = useWorkers();
  const edition = useEdition();
  const community = edition.data?.edition === 'COMMUNITY';

  return (
    <section aria-labelledby="workers-heading" className="flex flex-col gap-2">
      <h2 id="workers-heading" className="text-headline-md text-on-surface">
        Workers
      </h2>
      {workers.error ? (
        <StateMessage
          tone="error"
          title="Failed to load workers"
          onRetry={() => void workers.refetch()}
          compact
        />
      ) : workers.isPending ? (
        <StateMessage tone="loading" title="Loading…" compact />
      ) : workers.data.length === 0 ? (
        <StateMessage title="No workers connected." icon="dns" compact>
          Workers run nodes on other machines
          {community
            ? ' and need the Plus edition.'
            : '. Start one and point it at this server (see Operations → Remote workers in the docs).'}
        </StateMessage>
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Worker id</TableHead>
                <TableHead>Pools</TableHead>
                <TableHead>Plugin types</TableHead>
                <TableHead>Capacity</TableHead>
                <TableHead>In use</TableHead>
                <TableHead>Lease</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {workers.data.map((worker) => (
                <TableRow key={worker.workerId}>
                  <TableCell className="text-code-sm">
                    {worker.workerId}
                    {worker.draining && (
                      <span className="text-on-surface-variant">
                        {' '}
                        · draining
                      </span>
                    )}
                  </TableCell>
                  <TableCell>
                    <Chips values={worker.pools} />
                  </TableCell>
                  <TableCell>
                    <Chips values={worker.pluginTypes} />
                  </TableCell>
                  <TableCell>{worker.capacity}</TableCell>
                  <TableCell>
                    <progress
                      className="mr-2 align-middle"
                      value={worker.usedSlots}
                      max={Math.max(worker.capacity, 1)}
                      aria-label={`${worker.workerId} slots in use`}
                    />
                    {worker.usedSlots}/{worker.capacity}
                  </TableCell>
                  <TableCell className="text-on-surface-variant">
                    {relativeTime(worker.leaseUntil)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableFrame>
      )}
    </section>
  );
}
