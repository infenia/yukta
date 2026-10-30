// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useDeferredValue, useEffect, useId, useState } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { useMatch, useNavigate, useSearchParams } from 'react-router';
import { useMe } from '@/lib/identity';
import { toCli, toMcp } from '@/lib/commandEquivalents';
import { useWorkflowExecutions } from '@/features/workflow/hooks/useWorkflowExecutions';
import { useExecutionControl } from '@/features/workflow/hooks/useExecutionControl';
import { isTerminalExecStatus } from '@/features/workflow/lib/statusMapper';
import { cn } from '@/lib/utils';
import { useTheme } from '@/lib/theme';
import { useSessionSummaries } from '@/features/sessions/hooks/useSessionSummaries';
import { getSessionWorkflows } from '@/features/sessions/api/getSessionWorkflows';
import { searchExecutions } from '@/features/runs/api/searchExecutions';
import { parseFilters } from '@/features/runs/lib/filters';
import { StatusPill } from '@/features/workflow/components/StatusPill';
import type { WorkflowSummary } from '@/features/sessions/types/session';
import { OPEN_EVENT } from './CommandPalette.helpers';

/** Results shown per group, so one busy group cannot push the others out of view. */
const PER_GROUP = 5;
/** Run IDs are hex; shorter queries match too many runs to be useful. */
const MIN_RUN_QUERY = 3;

interface PaletteItem {
  id: string;
  group: string;
  icon: string;
  label: string;
  hint?: string;
  mono?: boolean;
  status?: string | null;
  perform: () => void;
}

const matches = (item: PaletteItem, query: string) =>
  `${item.label} ${item.hint ?? ''}`.toLowerCase().includes(query);

/**
 * The ⌘K / Ctrl+K palette: jump to a page, session, workflow or run, or run an action.
 * Mounted once in the app shell; its data loads only while it is open.
 */
export function CommandPalette() {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setOpen((was) => !was);
      }
    };
    const onOpen = () => setOpen(true);
    document.addEventListener('keydown', onKey);
    window.addEventListener(OPEN_EVENT, onOpen);
    return () => {
      document.removeEventListener('keydown', onKey);
      window.removeEventListener(OPEN_EVENT, onOpen);
    };
  }, []);

  return open ? <PaletteDialog onClose={() => setOpen(false)} /> : null;
}

function PaletteDialog({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate();
  const { theme, setTheme } = useTheme();
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const deferred = useDeferredValue(query.trim().toLowerCase());
  const listId = useId();

  const sessions = useSessionSummaries().data ?? [];
  // ponytail: one workflows request per session; add a server-side search when sessions number in the hundreds
  const workflows = useQueries({
    queries: sessions.map((session) => ({
      queryKey: ['sessions', session.sessionId, 'workflows'],
      queryFn: () => getSessionWorkflows(session.sessionId),
      select: (list: WorkflowSummary[]) =>
        list.map((workflow) => ({ ...workflow, sessionId: session.sessionId })),
    })),
  });
  const runs = useQuery({
    queryKey: ['runs', 'palette', deferred],
    queryFn: () =>
      searchExecutions(
        parseFilters(new URLSearchParams({ text: deferred })),
        PER_GROUP
      ),
    enabled: deferred.length >= MIN_RUN_QUERY,
  });

  const go = (to: string) => () => {
    onClose();
    void navigate(to);
  };

  // On a run view: the workflow and its run (the one in the URL, else the newest)
  const here = useMatch('/sessions/:sessionId/workflow/:workflowId');
  const [params] = useSearchParams();
  const sessionId = here?.params.sessionId ?? '';
  const workflowId = here?.params.workflowId ?? '';
  const executions = useWorkflowExecutions(sessionId, workflowId).data ?? [];
  const run =
    executions.find((e) => e.executionId === params.get('exec')) ??
    executions[0];
  const control = useExecutionControl(sessionId, workflowId);
  const me = useMe();
  // Stopping cannot be undone, so it takes a second press
  const [confirmStop, setConfirmStop] = useState(false);
  const copy = (text: string) => () => {
    void navigator.clipboard?.writeText(text);
    onClose();
  };
  const start = { kind: 'start', sessionId, workflowId } as const;
  const contextual: PaletteItem[] = here
    ? [
        {
          id: 'here:cli',
          group: 'This workflow',
          icon: 'terminal',
          label: 'Copy CLI command to run it',
          perform: copy(toCli(start, window.location.origin)),
        },
        {
          id: 'here:mcp',
          group: 'This workflow',
          icon: 'data_object',
          label: 'Copy MCP call to run it',
          perform: copy(toMcp(start)),
        },
        ...(run &&
        !isTerminalExecStatus(run.status) &&
        (!me || me.permissions.controlRuns)
          ? [
              {
                id: 'here:stop',
                group: 'This workflow',
                icon: 'stop_circle',
                label: confirmStop
                  ? `Press again to stop run ${run.executionId.slice(0, 8)}`
                  : `Stop run ${run.executionId.slice(0, 8)}`,
                perform: () => {
                  if (!confirmStop) {
                    setConfirmStop(true);
                    return;
                  }
                  control.runAction.mutate({
                    executionId: run.executionId,
                    action: 'stop',
                  });
                  onClose();
                },
              },
            ]
          : []),
      ]
    : [];

  const all: PaletteItem[] = [
    ...contextual,
    ...[
      ['Sessions', '/', 'folder'],
      ['Runs', '/runs', 'history'],
      ['Plugins', '/plugins', 'extension'],
      ['Control', '/control', 'hub'],
    ].map(([label, to, icon]) => ({
      id: `page:${to}`,
      group: 'Pages',
      icon,
      label,
      perform: go(to),
    })),
    ...sessions.map((session) => ({
      id: `session:${session.sessionId}`,
      group: 'Sessions',
      icon: 'folder',
      label: session.name || session.sessionId,
      hint: session.sessionId,
      perform: go(`/sessions/${encodeURIComponent(session.sessionId)}`),
    })),
    ...workflows.flatMap((result) =>
      (result.data ?? []).map((workflow) => ({
        id: `workflow:${workflow.sessionId}/${workflow.workflowId}`,
        group: 'Workflows',
        icon: 'account_tree',
        label: workflow.workflowId,
        hint: `in ${workflow.sessionId} · ${workflow.nodeCount} nodes`,
        mono: true,
        status: workflow.status,
        perform: go(
          `/sessions/${encodeURIComponent(workflow.sessionId)}/workflow/${encodeURIComponent(workflow.workflowId)}`
        ),
      }))
    ),
    ...(runs.data ?? []).map((run) => ({
      id: `run:${run.executionId}`,
      group: 'Runs',
      icon: 'history',
      label: run.executionId.slice(0, 8),
      hint: `${run.workflowId} in ${run.sessionId}`,
      mono: true,
      status: run.status,
      perform: go(
        `/sessions/${encodeURIComponent(run.sessionId)}/workflow/${encodeURIComponent(run.workflowId)}?exec=${encodeURIComponent(run.executionId)}`
      ),
    })),
    {
      id: 'action:new-session',
      group: 'Actions',
      icon: 'add',
      label: 'New session',
      perform: go('/sessions/new'),
    },
    {
      id: 'action:theme',
      group: 'Actions',
      icon: 'contrast',
      label:
        theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme',
      perform: () => {
        setTheme(theme === 'dark' ? 'light' : 'dark');
        onClose();
      },
    },
    {
      id: 'action:copy-link',
      group: 'Actions',
      icon: 'link',
      label: 'Copy link to this page',
      perform: () => {
        void navigator.clipboard?.writeText(window.location.href);
        onClose();
      },
    },
  ];

  const groups = new Map<string, PaletteItem[]>();
  for (const item of all) {
    if (deferred && !matches(item, deferred) && item.group !== 'Runs') continue;
    const list = groups.get(item.group) ?? [];
    if (list.length < PER_GROUP) groups.set(item.group, [...list, item]);
  }
  const shown = [...groups.values()].flat();
  const current = Math.min(active, Math.max(shown.length - 1, 0));

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const step = event.key === 'ArrowDown' ? 1 : -1;
      setActive((current + step + shown.length) % Math.max(shown.length, 1));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      shown[current]?.perform();
    } else if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
    }
  };

  return (
    <div
      className="bg-foreground/30 fixed inset-0 z-70 flex justify-center p-4 pt-[12vh]"
      onMouseDown={(event) => event.target === event.currentTarget && onClose()}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Command palette"
        className="border-border bg-card shadow-raised flex h-fit max-h-[70vh] w-full max-w-xl flex-col overflow-hidden rounded-2xl border"
      >
        <div className="border-border flex h-13 items-center gap-2.5 border-b px-4">
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-muted-foreground"
          >
            search
          </span>
          <input
            autoFocus
            role="combobox"
            aria-expanded="true"
            aria-controls={listId}
            aria-activedescendant={
              shown[current] ? `${listId}-${current}` : undefined
            }
            aria-label="Search pages, sessions, workflows, runs and actions"
            placeholder="Search sessions, workflows, runs and actions"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              setActive(0);
            }}
            onKeyDown={onKeyDown}
            className="text-on-surface placeholder:text-muted-foreground flex-1 bg-transparent text-base outline-none"
          />
          <kbd className="border-border bg-muted text-muted-foreground rounded border px-1.5 font-mono text-[11px]">
            Esc
          </kbd>
        </div>
        <div id={listId} role="listbox" className="overflow-y-auto p-1.5">
          {shown.length === 0 && (
            <p className="text-body-sm text-muted-foreground px-3 py-6 text-center">
              Nothing matches “{query}”.
            </p>
          )}
          {[...groups.entries()].map(([group, items]) => (
            <div key={group} role="group" aria-label={group}>
              <p className="text-overline px-2.5 pt-2.5 pb-1">{group}</p>
              {items.map((item) => {
                const index = shown.indexOf(item);
                return (
                  <div
                    key={item.id}
                    id={`${listId}-${index}`}
                    role="option"
                    aria-selected={index === current}
                    data-testid={`palette-${item.id}`}
                    onMouseMove={() => setActive(index)}
                    onClick={item.perform}
                    className={cn(
                      'flex cursor-pointer items-center gap-2.5 rounded-lg px-2.5 py-2',
                      index === current && 'bg-accent text-accent-foreground'
                    )}
                  >
                    <span
                      aria-hidden="true"
                      className="material-symbols-outlined text-muted-foreground text-lg"
                    >
                      {item.icon}
                    </span>
                    <span
                      className={cn(
                        'truncate',
                        item.mono ? 'text-code-sm font-semibold' : 'font-medium'
                      )}
                    >
                      {item.label}
                    </span>
                    {item.hint && (
                      <span className="text-body-sm text-muted-foreground truncate">
                        {item.hint}
                      </span>
                    )}
                    {item.status !== undefined && (
                      <StatusPill
                        status={item.status}
                        testId="palette-status"
                        className="ml-auto"
                      />
                    )}
                  </div>
                );
              })}
            </div>
          ))}
        </div>
        <div className="border-border bg-muted text-body-sm text-muted-foreground flex flex-wrap gap-4 border-t px-4 py-2">
          <span>↑ ↓ move</span>
          <span>⏎ open</span>
          <span>Esc close</span>
        </div>
      </div>
    </div>
  );
}
