// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useSupervision } from '@/features/supervisors/hooks/useSupervisorTypes';
import { controlledBy } from '@/features/supervisors/components/RequireControl';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Knob } from '@/components/dashboard/Knob';
import { InlineError } from '@/components/common/InlineError';
import { Modal } from '@/components/common/Modal';
import { Button } from '@/components/ui/button';
import { Tip } from '@/components/ui/tooltip';
import { ApiError } from '@/lib/apiClient';
import { REQUIRED_ROLE, useMe } from '@/lib/identity';
import { getDrain, setDrain, type DrainState } from '../api/drain';
import {
  getLimits,
  limitText,
  setLimits,
  type SchedulerLimits,
} from '../api/scheduler';
import { LimitsDialog } from './LimitsDialog';
import { PanelState } from '../dashboard/PanelState';

const LIMITS_KEY = ['control', 'scheduler', 'limits'];
const DRAIN_KEY = ['control', 'scheduler', 'drain'];
/** The knob goes to this when no limit is set, so there is something to turn. */
const KNOB_MAX = 64;

const failure = (error: Error | null) =>
  error instanceof ApiError && error.status === 403
    ? 'This needs an ADMIN key.'
    : error?.message;

/**
 * The write side of the scheduler: the concurrent-run limit as a knob, Edit limits and the drain
 * switch. It lives under Configuration; the Dashboard only reads these numbers.
 */
export function CapacityControls() {
  const client = useQueryClient();
  const limits = useQuery({ queryKey: LIMITS_KEY, queryFn: getLimits });
  const drain = useQuery({ queryKey: DRAIN_KEY, queryFn: getDrain });
  const [turning, setTurning] = useState<number | null>(null);
  const [confirmDrain, setConfirmDrain] = useState(false);
  const [editing, setEditing] = useState(false);
  // Shown disabled, with the role it needs, to a key that may not change the limits
  const me = useMe();
  // the server's owner may not let people change the limits or drain
  const server = useSupervision({}).data;
  const refused = [server?.setLimits, server?.drain].find(
    (c) => c && !c.allowed
  );
  const mayChange = (!me || me.permissions.changeLimits) && !refused;
  const needs = refused
    ? (refused.reason ?? controlledBy(refused.via))
    : mayChange
      ? undefined
      : `Needs ${REQUIRED_ROLE.changeLimits}`;

  // Both writes show the new value at once; the refetch after each write shows the server's value, so a refusal is undone.
  const changeLimit = useMutation({
    mutationFn: ({ max, perWorkflow }: { max: number; perWorkflow: number }) =>
      setLimits(max, perWorkflow),
    onMutate: async ({ max }) => {
      await client.cancelQueries({ queryKey: LIMITS_KEY });
      client.setQueryData<SchedulerLimits>(
        LIMITS_KEY,
        (old) => ({ ...old, maxConcurrent: max }) as SchedulerLimits
      );
    },
    onSettled: () => client.invalidateQueries({ queryKey: LIMITS_KEY }),
  });
  const toggleDrain = useMutation({
    mutationFn: setDrain,
    onMutate: async (on) => {
      await client.cancelQueries({ queryKey: DRAIN_KEY });
      client.setQueryData<DrainState>(
        DRAIN_KEY,
        (old) => ({ ...old, draining: on }) as DrainState
      );
    },
    onSettled: () => client.invalidateQueries({ queryKey: DRAIN_KEY }),
  });

  const data = limits.data;
  const max = data?.maxConcurrent ?? 0;
  const knobValue = turning ?? max;

  return (
    <section
      aria-label="Server capacity"
      className="bg-card shadow-card flex max-w-xl flex-col gap-4 rounded-xl p-4"
    >
      <p className="text-body-md text-on-surface-variant">
        How many runs the server starts at once, and whether it takes new ones.
        The Dashboard shows these numbers read-only.
      </p>
      <PanelState
        isPending={limits.isPending}
        error={limits.error}
        empty={!data}
        emptyText="No scheduler"
        onRetry={() => void limits.refetch()}
      >
        {data && (
          <div className="flex flex-col gap-3">
            <div className="flex items-center gap-3">
              <Knob
                label="Concurrent run limit (0 is unlimited)"
                value={knobValue}
                min={0}
                max={Math.max(KNOB_MAX, max)}
                valueText={limitText(knobValue)}
                onChange={setTurning}
                disabled={!mayChange}
                title={needs}
                onCommit={(value) => {
                  setTurning(null);
                  if (value !== max)
                    changeLimit.mutate({
                      max: value,
                      perWorkflow: data.maxPerWorkflow,
                    });
                }}
              />
              <span className="text-body-sm text-on-surface-variant">
                Limit: {limitText(knobValue)}
              </span>
            </div>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <label className="text-body-md flex items-center gap-2">
                <Tip
                  label={needs}
                  disabled={
                    !mayChange || drain.isPending || toggleDrain.isPending
                  }
                >
                  <input
                    type="checkbox"
                    role="switch"
                    checked={drain.data?.draining ?? false}
                    // Ending a drain is safe; starting one refuses every new start, so it asks first
                    onChange={(e) =>
                      e.target.checked
                        ? setConfirmDrain(true)
                        : toggleDrain.mutate(false)
                    }
                  />
                </Tip>
                Drain: refuse new starts
                {drain.data?.draining && (
                  <span className="text-body-sm text-on-surface-variant">
                    {drain.data.idle ? '(idle)' : '(running work finishes)'}
                  </span>
                )}
              </label>
              <Tip label={needs} disabled={!mayChange}>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setEditing(true)}
                >
                  Edit limits
                </Button>
              </Tip>
            </div>
            {(changeLimit.error ?? toggleDrain.error) && (
              <InlineError
                message={
                  failure(changeLimit.error ?? toggleDrain.error) ?? null
                }
              />
            )}
          </div>
        )}
      </PanelState>
      {editing && data && (
        <LimitsDialog limits={data} onClose={() => setEditing(false)} />
      )}
      {confirmDrain && (
        <Modal title="Drain the server?" onClose={() => setConfirmDrain(false)}>
          <p className="text-body-md text-on-surface-variant mb-4">
            New starts are refused until you end the drain. Runs already going
            finish.
          </p>
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setConfirmDrain(false)}>
              Cancel
            </Button>
            <Button
              onClick={() => {
                toggleDrain.mutate(true);
                setConfirmDrain(false);
              }}
            >
              Drain
            </Button>
          </div>
        </Modal>
      )}
    </section>
  );
}
