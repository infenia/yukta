// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { CodeBlock } from '@/components/common/CodeBlock';
import { RequirePermission } from '@/components/common/RequirePermission';
import { StateMessage } from '@/components/common/StateMessage';
import { Button } from '@/components/ui/button';
import {
  Table,
  TableBody,
  TableCell,
  TableFrame,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { formatDateTime } from '@/features/workflow/lib/timeFormat';
import {
  useRollbackSupervisor,
  useSupervisorType,
  useSupervisorVersions,
} from '../hooks/useSupervisorTypes';
import type { SupervisorView } from '../types';

const shown = (value: unknown) =>
  value === undefined
    ? '—'
    : typeof value === 'object'
      ? JSON.stringify(value)
      : String(value);

/** The decision maker's settings with their values; secrets come back as `***`. */
export function SettingsTab({ supervisor }: { supervisor: SupervisorView }) {
  const type = useSupervisorType(supervisor.type);
  if (type.isPending) return <StateMessage tone="loading" title="Loading…" />;
  const schema = type.data?.configSchema ?? [];
  return (
    <div className="flex flex-col gap-3">
      {schema.length > 0 && (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Setting</TableHead>
                <TableHead>Value</TableHead>
                <TableHead>What it does</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {schema.map((field) => (
                <TableRow key={field.name}>
                  <TableCell className="text-code-sm">{field.name}</TableCell>
                  <TableCell className="text-code-sm break-all">
                    {shown(
                      supervisor.settings[field.name] ??
                        field.defaultValue ??
                        undefined
                    )}
                  </TableCell>
                  <TableCell>{field.description}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableFrame>
      )}
      <details>
        <summary className="text-label-md cursor-pointer">
          All settings as JSON
        </summary>
        <div className="mt-2">
          <CodeBlock code={JSON.stringify(supervisor.settings, null, 2)} />
        </div>
      </details>
    </div>
  );
}

/** The lines of `after` that `before` lacks (+) and the reverse (-): enough to compare settings. */
export function lineDiff(before: string, after: string): string {
  const a = before.split('\n');
  const b = after.split('\n');
  const removed = a
    .filter((line) => !b.includes(line))
    .map((l) => `- ${l.trim()}`);
  const added = b
    .filter((line) => !a.includes(line))
    .map((l) => `+ ${l.trim()}`);
  return [...removed, ...added].join('\n') || 'No difference';
}

const settingsText = (s: SupervisorView) =>
  JSON.stringify(
    {
      description: s.description,
      dryRun: s.dryRun,
      parents: s.parents,
      settings: s.settings,
    },
    null,
    2
  );

/** The kept versions with their time; compare one with the newest; restore one. */
export function HistoryTab({ supervisor }: { supervisor: SupervisorView }) {
  const { data, isPending, error } = useSupervisorVersions(supervisor.id);
  const restore = useRollbackSupervisor();
  const [comparing, setComparing] = useState<number | null>(null);
  if (isPending)
    return <StateMessage tone="loading" title="Loading versions…" />;
  if (error)
    return <StateMessage tone="error" title="Failed to load the versions." />;
  if (data.length === 0) {
    return (
      <StateMessage title="No versions kept" compact>
        Each save keeps a version; the first save keeps version 1.
      </StateMessage>
    );
  }
  const newest = data[0];
  const compared = data.find((v) => v.version === comparing);
  return (
    <div className="flex flex-col gap-3">
      <ul className="border-border bg-card text-body-md shadow-card rounded-xl border">
        {data.map((v) => (
          <li
            key={v.version}
            className="border-border flex flex-wrap items-center gap-2 border-b px-3 py-2 last:border-b-0"
          >
            <b className="tabular-nums">v{v.version}</b>
            <span className="text-muted-foreground">
              {formatDateTime(v.savedAt)}
            </span>
            {v === newest && <span className="text-success">current</span>}
            <span className="flex-1" />
            {v !== newest && (
              <>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => setComparing(v.version)}
                >
                  Compare
                </Button>
                <RequirePermission perm="editSessions">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={restore.isPending}
                    onClick={() =>
                      restore.mutate({ id: supervisor.id, version: v.version })
                    }
                  >
                    Restore
                  </Button>
                </RequirePermission>
              </>
            )}
          </li>
        ))}
      </ul>
      {restore.error && (
        <p className="text-body-sm text-error">{restore.error.message}</p>
      )}
      {compared && (
        <section
          aria-label={`v${compared.version} to v${newest.version}`}
          className="flex flex-col gap-1"
        >
          <h3 className="text-label-md">
            v{compared.version} → v{newest.version}
          </h3>
          <CodeBlock
            code={lineDiff(
              settingsText(compared.supervisor),
              settingsText(newest.supervisor)
            )}
          />
        </section>
      )}
      <p className="text-body-sm text-muted-foreground">
        The newest 20 saves are kept; a save equal to the newest is not a new
        version. Restore saves that version again as the newest. Who saved a
        version is not recorded.
      </p>
    </div>
  );
}
