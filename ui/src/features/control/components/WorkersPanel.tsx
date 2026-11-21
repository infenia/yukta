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
import { useWorkers } from '../api/workers';
import { relativeTime } from './WorkersPanel.helpers';
import { Tip } from '@/components/ui/tooltip';

/** Plugin types shown per worker before the rest collapse into "+n". */
const VISIBLE_TYPES = 3;

function Chips({ values }: { values: string[] }) {
  const shown = values.slice(0, VISIBLE_TYPES);
  const hidden = values.length - shown.length;
  return (
    <span className="flex flex-wrap gap-1">
      {shown.map((value) => (
        <span
          key={value}
          className="bg-surface-container text-code-sm rounded px-1.5 py-0.5"
        >
          {value}
        </span>
      ))}
      {hidden > 0 && (
        <Tip label={values.slice(VISIBLE_TYPES).join(', ')}>
          <span className="text-code-sm text-on-surface-variant px-1">
            +{hidden}
          </span>
        </Tip>
      )}
    </span>
  );
}

/** The remote workers, when there are any, with their pools, plugin types and how much of their capacity is in use. */
export function WorkersPanel() {
  const workers = useWorkers();

  // the System card says there are none; this detail table is for when there are some
  if (workers.data?.length === 0) return null;

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
