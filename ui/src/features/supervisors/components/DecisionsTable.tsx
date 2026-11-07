// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { StateMessage } from '@/components/common/StateMessage';
import { NativeSelect } from '@/components/ui/native-select';
import {
  Table,
  TableBody,
  TableCell,
  TableFrame,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { useSupervisorDecisions } from '../hooks/useSupervisorTypes';

/** What became of the newest commands, newest first, filtered by outcome. */
export function DecisionsTable({ supervisor }: { supervisor?: string }) {
  const { data, isPending, error } = useSupervisorDecisions(supervisor);
  const [outcome, setOutcome] = useState('');
  if (isPending) return <StateMessage tone="loading" title="Loading…" />;
  if (error || !data) {
    return <StateMessage tone="error" title="Failed to load the decisions." />;
  }
  const outcomes = [...new Set(data.map((d) => d.outcome))].sort();
  const shown = outcome ? data.filter((d) => d.outcome === outcome) : data;
  return (
    <div className="flex flex-col gap-3">
      <label className="text-label-md flex items-center gap-2">
        Outcome
        <NativeSelect
          aria-label="Outcome"
          value={outcome}
          onChange={(event) => setOutcome(event.target.value)}
        >
          <option value="">All</option>
          {outcomes.map((o) => (
            <option key={o} value={o}>
              {o}
            </option>
          ))}
        </NativeSelect>
      </label>
      {shown.length === 0 ? (
        <StateMessage compact title="No decisions yet." />
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Time</TableHead>
                <TableHead>Attachment</TableHead>
                <TableHead>Command</TableHead>
                <TableHead>Outcome</TableHead>
                <TableHead>Reason</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {shown.map((d) => (
                <TableRow
                  key={`${d.time}-${d.supervisorId}-${d.executionId}-${d.command}`}
                >
                  <TableCell className="text-code-sm">{d.time}</TableCell>
                  <TableCell>{d.supervisorId}</TableCell>
                  <TableCell className="text-code-sm">{d.command}</TableCell>
                  <TableCell>{d.outcome}</TableCell>
                  <TableCell>{d.reason ?? '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableFrame>
      )}
    </div>
  );
}
