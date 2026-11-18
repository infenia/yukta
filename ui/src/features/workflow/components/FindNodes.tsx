// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMemo, useState } from 'react';
import { IconButton } from '@/components/common/IconButton';
import { cn } from '@/lib/utils';
import { findNodes } from '../lib/findNodes';
import type { WorkflowGraph } from '../types/workflow';

/**
 * Search the graph by node ID, plugin type, stage, description or edge ("a -> b"). The canvas
 * frames every match; the list under the field opens one.
 */
export function FindNodes({
  graph,
  query,
  onQuery,
  onPick,
  className,
}: {
  graph: WorkflowGraph;
  query: string;
  onQuery: (query: string) => void;
  onPick: (nodeId: string) => void;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const matches = useMemo(() => findNodes(graph, query), [graph, query]);
  const byId = useMemo(
    () => new Map(graph.nodes.map((node) => [node.nodeId, node])),
    [graph]
  );
  const close = () => {
    setOpen(false);
    onQuery('');
  };
  if (!open) {
    return (
      <IconButton
        icon="search"
        label="Find nodes"
        tip="Find nodes by ID, type, stage, description or edge"
        aria-expanded={false}
        onClick={() => setOpen(true)}
        className={cn('size-8', className)}
      />
    );
  }
  return (
    <div className={cn('relative', className)}>
      <div className="border-border bg-surface shadow-card flex h-8 items-center gap-1 rounded-full border pr-1 pl-3">
        <input
          type="search"
          autoFocus
          aria-label="Find nodes"
          placeholder="ID, type, stage, text, a -> b"
          value={query}
          onChange={(event) => onQuery(event.target.value)}
          onKeyDown={(event) => event.key === 'Escape' && close()}
          className="text-body-md placeholder:text-on-surface-variant w-56 bg-transparent outline-none"
        />
        {query.trim() && (
          <span
            role="status"
            className="text-label-sm text-on-surface-variant num"
          >
            {matches.length} {matches.length === 1 ? 'match' : 'matches'}
          </span>
        )}
        <IconButton
          icon="close"
          label="Close find"
          onClick={close}
          className="size-6 border-0"
        />
      </div>
      {matches.length > 0 && (
        <ul
          aria-label="Matching nodes"
          className="border-border bg-surface shadow-raised absolute top-9 left-0 max-h-64 w-80 overflow-auto rounded-lg border p-1"
        >
          {matches.map(({ nodeId, via }) => (
            <li key={nodeId}>
              <button
                type="button"
                onClick={() => onPick(nodeId)}
                className="hover:bg-muted flex w-full flex-col rounded-md px-2 py-1 text-left"
              >
                <span className="text-body-sm truncate">{nodeId}</span>
                <span className="text-label-sm text-on-surface-variant truncate">
                  {via === 'edge'
                    ? 'on a matching edge'
                    : (byId.get(nodeId)?.description ?? byId.get(nodeId)?.type)}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
