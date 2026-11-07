// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { StateMessage } from '@/components/common/StateMessage';
import { CodeBlock } from '@/components/common/CodeBlock';
import { Button, buttonVariants } from '@/components/ui/button';
import { PageHeader } from '@/components/layout/PageHeader';
import {
  useAttachments,
  useDeleteSupervisor,
  useSupervisor,
} from '../hooks/useSupervisorTypes';
import { AttachDialog } from './AttachDialog';
import { AttachmentsTable } from './AttachmentsTable';
import { DecisionsTable } from './DecisionsTable';

type Tab = 'overview' | 'attachments' | 'decisions';
const TABS: Tab[] = ['overview', 'attachments', 'decisions'];

/** One supervisor: its settings, where it is attached and what it decided. */
export function SupervisorDetailPage() {
  const { supervisorId } = useParams();
  const { data: supervisor, isPending, error } = useSupervisor(supervisorId);
  const attachments = useAttachments({ supervisor: supervisorId });
  const remove = useDeleteSupervisor();
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>('overview');
  const [attaching, setAttaching] = useState(false);
  if (isPending) return <StateMessage tone="loading" title="Loading…" />;
  if (error || !supervisor) {
    return <StateMessage tone="error" title="Failed to load the supervisor." />;
  }
  const id = encodeURIComponent(supervisor.id);
  return (
    <div className="flex flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
      <PageHeader
        backdrop
        title={supervisor.id}
        description={`${supervisor.type}${supervisor.description ? ` · ${supervisor.description}` : ''}`}
        actions={
          supervisor.system ? (
            <span className="text-label-md">Set by the server · read-only</span>
          ) : (
            <div className="flex flex-wrap gap-2">
              <Button
                type="button"
                variant="outline"
                onClick={() => setAttaching(true)}
              >
                Attach
              </Button>
              <Link
                to={`/supervisors/${id}/edit`}
                className={buttonVariants({ variant: 'outline' })}
              >
                Edit
              </Link>
              <Button
                type="button"
                variant="outline"
                onClick={() =>
                  remove.mutate(supervisor.id, {
                    onSuccess: () => navigate('/supervisors?tab=supervisors'),
                  })
                }
              >
                Delete
              </Button>
            </div>
          )
        }
      />
      {remove.error && (
        <StateMessage tone="error" compact title={remove.error.message} />
      )}
      <div role="tablist" className="border-border flex gap-4 border-b">
        {TABS.map((name) => (
          <button
            key={name}
            type="button"
            role="tab"
            aria-selected={tab === name}
            onClick={() => setTab(name)}
            className={`text-label-md -mb-px h-9 border-b-2 px-0.5 capitalize ${
              tab === name
                ? 'border-primary text-on-surface'
                : 'text-on-surface-variant hover:text-on-surface border-transparent'
            }`}
          >
            {name}
          </button>
        ))}
      </div>
      {tab === 'overview' && (
        <CodeBlock
          code={JSON.stringify(
            { dryRun: supervisor.dryRun, settings: supervisor.settings },
            null,
            2
          )}
        />
      )}
      {tab === 'attachments' && (
        <AttachmentsTable attachments={attachments.data ?? []} />
      )}
      {tab === 'decisions' && <DecisionsTable supervisor={supervisor.id} />}
      {attaching && (
        <AttachDialog
          supervisorId={supervisor.id}
          onClose={() => setAttaching(false)}
        />
      )}
    </div>
  );
}
