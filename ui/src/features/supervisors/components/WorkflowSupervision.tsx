// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link, useSearchParams } from 'react-router';
import { Callout } from '@/components/common/Callout';
import { Modal } from '@/components/common/Modal';
import { iconButton } from '@/components/ui/iconTone';
import { Tip } from '@/components/ui/tooltip';
import { StateMessage } from '@/components/common/StateMessage';
import { useOwnership, useSupervision } from '../hooks/useSupervisorTypes';
import { ownerLabel } from '../lib/ownership';
import { ConflictsBanner } from './ConflictsBanner';
import { controlledBy } from './RequireControl';

const supervisorLink = (owner: string) =>
  owner === 'default' ? (
    'Default (people)'
  ) : (
    <Link
      to={`/supervisors?supervisor=${encodeURIComponent(owner)}`}
      className="text-primary"
    >
      {owner}
    </Link>
  );

/** The nodes of each owner, in the order the owners first appear. */
function byOwner(nodes: Record<string, string>) {
  const groups = new Map<string, string[]>();
  for (const [node, owner] of Object.entries(nodes)) {
    groups.set(owner, [...(groups.get(owner) ?? []), node]);
  }
  return [...groups.entries()];
}

/** Who owns the run level and each node of a workflow, and who can stop its run. */
export function SupervisionPanel({
  sessionId,
  workflowId,
  executionId,
}: {
  sessionId: string;
  workflowId: string;
  executionId: string | null;
}) {
  const ownership = useOwnership(sessionId, workflowId);
  const run = useSupervision({ execution: executionId }, Boolean(executionId));
  if (ownership.isPending) {
    return <StateMessage tone="loading" title="Loading who owns what…" />;
  }
  if (ownership.error) {
    return (
      <StateMessage tone="error" title="Could not load who owns this workflow.">
        The run controls use your role only; the server still checks every
        command.
      </StateMessage>
    );
  }
  const { run: runOwner, nodes, defaultSupervisor } = ownership.data;
  const groups = byOwner(nodes);
  const stop = run.data?.stop;
  const nobodyOwns =
    runOwner === 'default' && groups.every(([owner]) => owner === 'default');
  return (
    <div className="flex flex-col gap-3">
      <ConflictsBanner session={sessionId} workflow={workflowId} />
      {nobodyOwns && defaultSupervisor && (
        <StateMessage
          compact
          icon="account_tree"
          title={`Nobody supervises ${workflowId}`}
        >
          People control it directly through Default (people).
        </StateMessage>
      )}
      {!defaultSupervisor && runOwner === 'default' && (
        <Callout kind="warning" title="Nobody can control this workflow">
          Default (people) is off and no supervisor owns {workflowId}. An ADMIN
          can turn Default on or assign a supervisor.
        </Callout>
      )}
      <p className="text-body-sm text-on-surface-variant">
        A supervisor decides who may start, stop or change a workflow. Where no
        supervisor is set, people control it directly.
      </p>
      <div className="border-border bg-card shadow-card overflow-x-auto rounded-xl border">
        <table className="text-body-md w-full">
          <thead className="bg-muted">
            <tr>
              <th className="text-label-md text-on-surface-variant px-3 py-2 text-left">
                Part of the workflow
              </th>
              <th className="text-label-md text-on-surface-variant px-3 py-2 text-left">
                Controlled by
              </th>
            </tr>
          </thead>
          <tbody>
            <tr className="border-border border-t">
              <td className="px-3 py-2">The run as a whole</td>
              <td className="px-3 py-2">{supervisorLink(runOwner)}</td>
            </tr>
            {groups.map(([owner, ids]) => (
              <tr key={owner} className="border-border border-t">
                <td className="px-3 py-2">
                  <span className="text-on-surface-variant">
                    {ids.length === 1 ? 'Step ' : `${ids.length} steps: `}
                  </span>
                  <code className="text-code-sm">{ids.join(', ')}</code>
                </td>
                <td className="px-3 py-2">{supervisorLink(owner)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {stop && (
        <p className="text-body-sm text-on-surface-variant">
          <b>Can you stop this run?</b>{' '}
          {stop.allowed
            ? stop.via === 'default'
              ? 'Yes, directly.'
              : `Yes, through ${stop.via}.`
            : (stop.reason ?? controlledBy(stop.via))}
        </p>
      )}
    </div>
  );
}

/** The run's owner on the workflow page, opening who owns what; `?supervision=1` opens it at once. */
export function WorkflowSupervision({
  sessionId,
  workflowId,
  executionId,
}: {
  sessionId: string;
  workflowId: string;
  executionId: string | null;
}) {
  // the dialog lives in the URL, so the palette (and a link) can open it on a page already shown
  const [params, setParams] = useSearchParams();
  const open = params.has('supervision');
  const show = (yes: boolean) => {
    const next = new URLSearchParams(params);
    if (yes) next.set('supervision', '1');
    else next.delete('supervision');
    setParams(next, { replace: !yes });
  };
  const { data } = useOwnership(sessionId, workflowId);
  return (
    <>
      <Tip label={`Controlled by: ${ownerLabel(data?.run)}`}>
        <button
          type="button"
          onClick={() => show(true)}
          aria-label={`Run owner: ${ownerLabel(data?.run)}`}
          className={iconButton('primary')}
        >
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-base"
          >
            shield_person
          </span>
        </button>
      </Tip>
      {open && (
        <Modal
          title="Who controls this workflow"
          onClose={() => show(false)}
          wide
        >
          <SupervisionPanel
            sessionId={sessionId}
            workflowId={workflowId}
            executionId={executionId}
          />
        </Modal>
      )}
    </>
  );
}
