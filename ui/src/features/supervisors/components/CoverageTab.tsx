// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
import { cn } from '@/lib/utils';
import { StateMessage } from '@/components/common/StateMessage';
import { buttonVariants } from '@/components/ui/button';
import { useAttachments } from '../hooks/useSupervisorTypes';
import { buildCoverage, type CoverageNode } from '../lib/coverage';
import type { AttachmentView } from '../types';

const LEVEL_NAME = {
  global: 'Everything',
  session: 'Session',
  workflow: 'Workflow',
  section: 'Nodes',
} as const;

const SOURCE_NAME = {
  MANUAL: 'attached by hand',
  DEFINITION: 'declared in a session or workflow definition',
  SYSTEM: 'set by the server',
} as const;

function Chip({
  attachment,
  inherited,
}: {
  attachment: AttachmentView;
  inherited?: boolean;
}) {
  return (
    <Link
      to={`/supervisors/${encodeURIComponent(attachment.supervisorId)}`}
      title={`${attachment.supervisorId}: ${SOURCE_NAME[attachment.source]}${
        inherited ? ', inherited from a wider scope' : ''
      }${attachment.enabled ? '' : ' (paused)'}`}
      className={cn(
        'text-body-sm border-border inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5',
        inherited
          ? 'text-on-surface-variant border-dashed'
          : 'bg-accent text-accent-foreground',
        !attachment.enabled && 'line-through opacity-60'
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          'size-1.5 rounded-full',
          attachment.source === 'SYSTEM'
            ? 'bg-muted-foreground'
            : attachment.source === 'DEFINITION'
              ? 'bg-primary'
              : 'bg-success'
        )}
      />
      {attachment.supervisorId}
    </Link>
  );
}

function Row({
  node,
  inherited,
  depth,
}: {
  node: CoverageNode;
  inherited: AttachmentView[];
  depth: number;
}) {
  const passDown = [...inherited, ...node.own];
  return (
    <li>
      <div
        className="border-border bg-card flex flex-wrap items-center gap-x-3 gap-y-1.5 rounded-lg border px-3 py-2"
        style={{ marginLeft: depth * 20 }}
      >
        <span className="text-overline w-20 shrink-0">
          {LEVEL_NAME[node.level]}
        </span>
        <span className="text-label-md text-on-surface min-w-0 truncate">
          {node.level === 'global' ? 'All sessions and workflows' : node.label}
        </span>
        <span className="flex flex-wrap items-center gap-1.5">
          {node.own.map((a) => (
            <Chip key={a.id} attachment={a} />
          ))}
          {inherited.map((a) => (
            <Chip key={a.id} attachment={a} inherited />
          ))}
          {passDown.length === 0 && (
            <span className="text-body-sm text-muted-foreground">
              Not supervised
            </span>
          )}
        </span>
      </div>
      {node.children.length > 0 && (
        <ul className="mt-1.5 flex flex-col gap-1.5">
          {node.children.map((child) => (
            <Row
              key={child.key}
              node={child}
              inherited={passDown}
              depth={depth + 1}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

/** Who is watching what: the scope nesting with the supervisors attached at each level. */
export function CoverageTab() {
  const { data, isPending, error } = useAttachments();
  if (isPending) return <StateMessage tone="loading" title="Loading…" />;
  if (error || !data) {
    return <StateMessage tone="error" title="Failed to load the coverage." />;
  }
  if (data.length === 0) {
    return (
      <div className="border-border bg-card flex flex-col gap-3 rounded-xl border p-6">
        <h2 className="text-headline-sm text-on-surface">
          Nothing is supervised yet
        </h2>
        <ol className="text-body-md text-on-surface-variant list-decimal pl-5">
          <li>
            Pick a behavior in the Library (a failure guard is a good start).
          </li>
          <li>Create a supervisor from it and choose where it watches.</li>
          <li>
            Come back here to see what it covers, and to Activity for what it
            did.
          </li>
        </ol>
        <Link
          to="/supervisors/new"
          className={cn(buttonVariants(), 'self-start')}
        >
          Create supervisor
        </Link>
      </div>
    );
  }
  const root = buildCoverage(data);
  return (
    <div className="flex flex-col gap-3">
      <ul className="flex flex-col gap-1.5">
        <Row node={root} inherited={[]} depth={0} />
      </ul>
      <p className="text-body-sm text-muted-foreground flex flex-wrap gap-x-4">
        <span>● green: attached by hand</span>
        <span>● blue: from a definition</span>
        <span>● grey: set by the server</span>
      </p>
    </div>
  );
}
