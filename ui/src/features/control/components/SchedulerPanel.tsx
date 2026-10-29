// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Callout } from '@/components/common/Callout';
import { Modal } from '@/components/common/Modal';
import { StateMessage } from '@/components/common/StateMessage';
import { ApiError } from '@/lib/apiClient';
import {
  getLimits,
  limitText,
  parseLimit,
  setLimits,
  type SchedulerLimits,
} from '../api/scheduler';
import { useFallbackPoll } from '../context/ControlEvents';

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-body-sm text-on-surface-variant">{label}</dt>
      <dd className="text-headline-md text-on-surface">{value}</dd>
    </div>
  );
}

function EditDialog({
  limits,
  onClose,
}: {
  limits: SchedulerLimits;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [concurrent, setConcurrent] = useState(String(limits.maxConcurrent));
  const [perWorkflow, setPerWorkflow] = useState(String(limits.maxPerWorkflow));
  const [confirming, setConfirming] = useState(false);
  const save = useMutation({
    mutationFn: (values: [number, number]) => setLimits(...values),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: ['control', 'scheduler'],
      });
      onClose();
    },
  });
  const a = parseLimit(concurrent);
  const b = parseLimit(perWorkflow);
  const valid = a !== null && b !== null;
  const failure =
    save.error instanceof ApiError && save.error.status === 403
      ? 'Changing limits needs an ADMIN key.'
      : save.error?.message;

  return (
    <Modal title="Edit execution limits" onClose={onClose}>
      <div className="flex flex-col gap-3">
        <label className="text-label-md text-on-surface flex flex-col gap-1">
          Max concurrent (0 is unlimited)
          <Input
            inputMode="numeric"
            value={concurrent}
            aria-invalid={a === null}
            onChange={(event) => {
              setConcurrent(event.target.value);
              setConfirming(false);
            }}
          />
        </label>
        <label className="text-label-md text-on-surface flex flex-col gap-1">
          Max per workflow (0 is unlimited)
          <Input
            inputMode="numeric"
            value={perWorkflow}
            aria-invalid={b === null}
            onChange={(event) => {
              setPerWorkflow(event.target.value);
              setConfirming(false);
            }}
          />
        </label>
        {!valid && (
          <Callout kind="danger" compact>
            Enter whole numbers, 0 or more.
          </Callout>
        )}
        <Callout kind="info" compact>
          Lowering a limit does not stop runs that are already running; it holds
          new starts back. Raising one admits waiting starts at once.
        </Callout>
        {confirming && valid && (
          <Callout kind="warning" compact>
            Set max concurrent to {limitText(a)} and max per workflow to{' '}
            {limitText(b)}?
          </Callout>
        )}
        {failure && (
          <Callout kind="danger" compact>
            {failure}
          </Callout>
        )}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          {confirming && valid ? (
            <Button
              type="button"
              disabled={save.isPending}
              onClick={() => save.mutate([a, b])}
            >
              Confirm and save
            </Button>
          ) : (
            <Button
              type="button"
              disabled={!valid}
              onClick={() => setConfirming(true)}
            >
              Save
            </Button>
          )}
        </div>
      </div>
    </Modal>
  );
}

/** The execution limits, how much of them is in use, and a way to change them. */
export function SchedulerPanel() {
  const poll = useFallbackPoll();
  const [editing, setEditing] = useState(false);
  const limits = useQuery({
    queryKey: ['control', 'scheduler'],
    queryFn: getLimits,
    refetchInterval: poll,
  });

  return (
    <section
      aria-labelledby="scheduler-heading"
      className="flex flex-col gap-2"
    >
      <div className="flex items-center gap-3">
        <h2 id="scheduler-heading" className="text-headline-md text-on-surface">
          Execution limits
        </h2>
        {limits.data && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="ml-auto"
            onClick={() => setEditing(true)}
          >
            Edit limits
          </Button>
        )}
      </div>
      {limits.error ? (
        <StateMessage
          tone="error"
          title="Failed to load the limits"
          onRetry={() => void limits.refetch()}
          compact
        />
      ) : limits.isPending ? (
        <StateMessage tone="loading" title="Loading…" compact />
      ) : (
        <>
          <dl className="grid grid-cols-2 gap-4 sm:grid-cols-5">
            <Stat
              label="Max concurrent"
              value={limitText(limits.data.maxConcurrent)}
            />
            <Stat
              label="Max per workflow"
              value={limitText(limits.data.maxPerWorkflow)}
            />
            <Stat label="Running" value={String(limits.data.running)} />
            <Stat label="Queued" value={String(limits.data.queued)} />
            <Stat
              label="Queue capacity"
              value={String(limits.data.queueCapacity)}
            />
          </dl>
          {limits.data.maxConcurrent > 0 && (
            <progress
              aria-label="Slots in use"
              className="w-full"
              max={limits.data.maxConcurrent}
              value={Math.min(limits.data.running, limits.data.maxConcurrent)}
            />
          )}
          {editing && (
            <EditDialog
              limits={limits.data}
              onClose={() => setEditing(false)}
            />
          )}
        </>
      )}
    </section>
  );
}
