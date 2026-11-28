// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { formatDateTime } from '@/features/workflow/lib/timeFormat';
import { useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { Button } from '@/components/ui/button';
import { Select } from '@/components/ui/select';
import { api } from '@/lib/apiClient';
import { useFallbackPoll } from '../context/ControlEventsContext';
import { fetchApi } from '@/lib/apiClient';
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

/** A control command and what became of it. */
export interface ControlAuditEntry {
  time: string;
  executionId: string;
  command: string;
  actor: string | null;
  applied: boolean;
  reason: string | null;
  /** the run's session and workflow; null once the server no longer holds the run */
  sessionId?: string | null;
  workflowId?: string | null;
}

/**
 * A key for each row. The server gives an entry no id, and the newest row comes first, so an index
 * would move every key when a row arrives. The content is the key; a repeat of the same command in
 * the same instant gets a number.
 */
export function rowKeys(entries: ControlAuditEntry[]): string[] {
  const seen = new Map<string, number>();
  return entries.map((entry) => {
    const base = `${entry.time}|${entry.executionId}|${entry.command}|${entry.actor}`;
    const count = seen.get(base) ?? 0;
    seen.set(base, count + 1);
    return `${base}|${count}`;
  });
}

/** Rows asked for at first, and added by each "Show more". */
const PAGE = 50;
/** The newest rows one request returns; beyond them "Show more" pages back with `before`. */
const MAX = 500;

const getAudit = (limit: number, executionId: string, before?: string) =>
  fetchApi<ControlAuditEntry[]>(
    `/api/control/audit?limit=${limit}` +
      (executionId ? `&executionId=${encodeURIComponent(executionId)}` : '') +
      (before ? `&before=${encodeURIComponent(before)}` : '')
  );

/** `SetLimits(2, 0)` is the command `SetLimits`, whatever its arguments. */
const commandName = (command: string) => command.replace(/\(.*$/, '');

type Result = 'all' | 'applied' | 'refused';

/** The run view of a command's run, when the server still knows the run's session and workflow. */
const runLink = (e: ControlAuditEntry) =>
  e.sessionId && e.workflowId
    ? `${api`/sessions/${e.sessionId}/workflow/${e.workflowId}`}?exec=${encodeURIComponent(e.executionId)}`
    : null;

/** Applied or refused, as a badge: the reason of a refusal reads next to it. */
function ResultBadge({ entry }: { entry: ControlAuditEntry }) {
  return entry.applied ? (
    <span className="bg-success/10 text-label-sm text-success rounded-full px-2 py-0.5">
      Applied
    </span>
  ) : (
    <span className="flex flex-wrap items-center gap-1">
      <span className="bg-destructive/10 text-label-sm text-destructive rounded-full px-2 py-0.5">
        Refused
      </span>
      {entry.reason && (
        <span className="text-body-sm text-on-surface-variant">
          {entry.reason}
        </span>
      )}
    </span>
  );
}

/** Who sent which control command, newest first, with filters over what was loaded. */
export function ControlAuditPanel() {
  const poll = useFallbackPoll();
  // ?execution=<id> shows the commands of one run (the run view links here)
  const [params, setParams] = useSearchParams();
  const execution = params.get('execution') ?? '';
  const [limit, setLimit] = useState(PAGE);
  const [result, setResult] = useState<Result>('all');
  const [command, setCommand] = useState('');
  const [actor, setActor] = useState('');
  const audit = useQuery({
    queryKey: ['control', 'audit', execution, limit],
    queryFn: () => getAudit(limit, execution),
    refetchInterval: poll,
    placeholderData: (previous) => previous,
  });
  // rows older than the newest MAX, added by "Show more" once the window is full
  const [older, setOlder] = useState<ControlAuditEntry[]>([]);
  const [exhausted, setExhausted] = useState(false);
  const [paging, setPaging] = useState(false);
  const [pagedFor, setPagedFor] = useState(execution);
  if (pagedFor !== execution) {
    setPagedFor(execution);
    setOlder([]);
    setExhausted(false);
  }
  const head = audit.data ?? [];
  const loaded = [...head, ...older];
  const keys = rowKeys(loaded);
  const commands = [...new Set(loaded.map((e) => commandName(e.command)))];
  const actors = [...new Set(loaded.map((e) => e.actor ?? 'api'))];
  const shown = loaded
    .map((entry, index) => ({ entry, key: keys[index] }))
    .filter(
      ({ entry: e }) =>
        (result === 'all' || (result === 'applied') === e.applied) &&
        (command === '' || commandName(e.command) === command) &&
        (actor === '' || (e.actor ?? 'api') === actor)
    );

  return (
    <section aria-labelledby="audit-heading" className="flex flex-col gap-2">
      <h2 id="audit-heading" className="sr-only">
        Control audit
      </h2>
      <div className="flex flex-wrap items-center gap-2">
        <Select
          ariaLabel="Result"
          value={result}
          onValueChange={(v) => setResult(v as Result)}
          options={[
            { value: 'all', label: 'Any result' },
            { value: 'applied', label: 'Applied' },
            { value: 'refused', label: 'Refused' },
          ]}
        />
        <Select
          searchable
          ariaLabel="Command"
          value={command}
          onValueChange={setCommand}
          options={[
            { value: '', label: 'Any command' },
            ...commands.map((c) => ({ value: c, label: c })),
          ]}
        />
        <Select
          searchable
          ariaLabel="Actor"
          value={actor}
          onValueChange={setActor}
          options={[
            { value: '', label: 'Any actor' },
            ...actors.map((a) => ({ value: a, label: a })),
          ]}
        />
        {execution && (
          <p className="text-body-sm text-on-surface-variant flex items-center gap-2">
            Commands of run <span className="text-code-sm">{execution}</span>
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                const next = new URLSearchParams(params);
                next.delete('execution');
                setParams(next, { replace: true });
              }}
            >
              Show all
            </Button>
          </p>
        )}
      </div>
      {audit.error ? (
        <StateMessage
          tone="error"
          title="Failed to load the control audit"
          onRetry={() => void audit.refetch()}
          compact
        />
      ) : audit.isPending ? (
        <StateMessage tone="loading" title="Loading…" compact />
      ) : loaded.length === 0 ? (
        <StateMessage
          title="No control commands recorded yet."
          icon="history"
          compact
        />
      ) : shown.length === 0 ? (
        <StateMessage title="No commands match the filters." compact />
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Time</TableHead>
                <TableHead>Actor</TableHead>
                <TableHead>Command</TableHead>
                <TableHead>Workflow</TableHead>
                <TableHead>Result</TableHead>
                <TableHead>Run</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {shown.map(({ entry, key }) => {
                const link = runLink(entry);
                return (
                  <TableRow key={key}>
                    <TableCell className="text-on-surface-variant">
                      {formatDateTime(entry.time)}
                    </TableCell>
                    <TableCell className="text-code-sm">
                      {entry.actor ?? 'api'}
                    </TableCell>
                    <TableCell className="text-code-sm">
                      {entry.command}
                    </TableCell>
                    <TableCell>
                      {entry.workflowId ? (
                        <>
                          {entry.workflowId}
                          <span className="text-on-surface-variant">
                            {' '}
                            · {entry.sessionId}
                          </span>
                        </>
                      ) : (
                        <span className="text-on-surface-variant">–</span>
                      )}
                    </TableCell>
                    <TableCell>
                      <ResultBadge entry={entry} />
                    </TableCell>
                    <TableCell className="text-code-sm">
                      {link ? (
                        <Link className="text-primary" to={link}>
                          {entry.executionId.slice(0, 8)}
                        </Link>
                      ) : (
                        <span className="text-on-surface-variant">
                          {entry.executionId}
                        </span>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableFrame>
      )}
      {head.length >= limit && !(limit >= MAX && exhausted) && (
        <div>
          <Button
            variant="outline"
            size="sm"
            disabled={paging}
            onClick={() => {
              if (limit < MAX) {
                setLimit((n) => Math.min(n + PAGE, MAX));
                return;
              }
              setPaging(true);
              getAudit(PAGE, execution, loaded[loaded.length - 1].time)
                .then((page) => {
                  setOlder((o) => [...o, ...page]);
                  setExhausted(page.length < PAGE);
                })
                .catch(() => setExhausted(true))
                .finally(() => setPaging(false));
            }}
          >
            Show more
          </Button>
        </div>
      )}
    </section>
  );
}
