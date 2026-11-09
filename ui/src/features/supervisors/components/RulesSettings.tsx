// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { CodeBlock } from '@/components/common/CodeBlock';
import { StateMessage } from '@/components/common/StateMessage';
import { Button } from '@/components/ui/button';
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
import { searchExecutions } from '@/features/runs/api/searchExecutions';
import { parseFilters } from '@/features/runs/lib/filters';
import { formatDateTime } from '@/features/workflow/lib/timeFormat';
import { getRules, replayRules } from '../api/supervisors';
import { ruleSentence } from '../lib/ruleSentences';

const RECENT_RUNS = 20;

/** A rules supervisor's rules as sentences, a replay on a past run, and the document as JSON. */
export function RulesSettings({ id }: { id: string }) {
  const rules = useQuery({
    queryKey: ['supervisors', 'rules', id],
    queryFn: () => getRules(id),
  });
  if (rules.isPending) return <StateMessage tone="loading" title="Loading…" />;
  if (rules.error) {
    return (
      <StateMessage tone="error" title="Failed to load the rules.">
        {rules.error.message}
      </StateMessage>
    );
  }
  const list = rules.data.rules.rules ?? [];
  return (
    <div className="flex flex-col gap-3">
      <h3 className="text-label-md">
        Rules {list.length} · version {rules.data.version}
      </h3>
      {list.length === 0 ? (
        <StateMessage title="No rules" compact>
          Without rules this supervisor does nothing on its own.
        </StateMessage>
      ) : (
        <ul className="border-border bg-card text-body-md shadow-card rounded-xl border">
          {list.map(ruleSentence).map((rule) => (
            <li
              key={rule.id}
              className="border-border flex flex-col gap-0.5 border-b px-3 py-2 last:border-b-0"
            >
              <b className="text-code-sm">{rule.id}</b>
              <span>
                <b>When</b> {rule.when}
              </span>
              <span>
                <b>Then</b> {rule.then}
              </span>
            </li>
          ))}
        </ul>
      )}
      <Replay id={id} />
      <details>
        <summary className="text-label-md cursor-pointer">
          The document as JSON
        </summary>
        <div className="mt-2 flex flex-col gap-1">
          <CodeBlock
            code={JSON.stringify(rules.data.rules, null, 2)}
            label="the rules"
          />
          <p className="text-body-sm text-muted-foreground">
            Read with MCP <code>get_rules</code>, changed with{' '}
            <code>save_rules</code> or <code>yukta supervisors rules set</code>.
          </p>
        </div>
      </details>
    </div>
  );
}

/** The stored rules tried on a recent run, in dry run: nothing is sent. */
function Replay({ id }: { id: string }) {
  const runs = useQuery({
    queryKey: ['runs', 'recent', 'replay'],
    queryFn: () =>
      searchExecutions(parseFilters(new URLSearchParams()), RECENT_RUNS),
  });
  const [run, setRun] = useState('');
  const replay = useMutation({
    mutationFn: (executionId: string) => replayRules(id, executionId),
  });
  const chosen = run || runs.data?.[0]?.executionId || '';
  return (
    <section aria-label="Replay on a run" className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <NativeSelect
          aria-label="Run"
          value={chosen}
          onChange={(event) => setRun(event.target.value)}
          disabled={!runs.data?.length}
        >
          {runs.data?.length ? (
            runs.data.map((r) => (
              <option key={r.executionId} value={r.executionId}>
                {r.workflowId} · {r.status}
                {r.startTime ? ` · ${formatDateTime(r.startTime)}` : ''}
              </option>
            ))
          ) : (
            <option value="">No runs yet</option>
          )}
        </NativeSelect>
        <Button
          size="sm"
          variant="outline"
          disabled={!chosen || replay.isPending}
          onClick={() => replay.mutate(chosen)}
        >
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-sm"
          >
            replay
          </span>
          Replay on a run
        </Button>
      </div>
      <p className="text-body-sm text-muted-foreground">
        Dry run: nothing is sent. Only status changes were recorded, so rules on
        logs or input requests do not fire.
      </p>
      {replay.error && (
        <p className="text-body-sm text-error">{replay.error.message}</p>
      )}
      {replay.data &&
        (replay.data.length === 0 ? (
          <StateMessage title="Nothing would have happened" compact>
            No rule matched on this run.
          </StateMessage>
        ) : (
          <TableFrame>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Would</TableHead>
                  <TableHead>Node</TableHead>
                  <TableHead>Cause</TableHead>
                  <TableHead>Reason</TableHead>
                  <TableHead>Allowed</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {replay.data.map((d, i) => (
                  <TableRow key={i}>
                    <TableCell className="text-code-sm">{d.command}</TableCell>
                    <TableCell className="text-code-sm">
                      {d.nodeId ?? 'the run'}
                    </TableCell>
                    <TableCell>{d.cause}</TableCell>
                    <TableCell>{d.reason ?? '—'}</TableCell>
                    <TableCell>{d.allowed ? 'yes' : 'no: refused'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableFrame>
        ))}
    </section>
  );
}
