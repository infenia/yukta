// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ArcGauge } from '@/components/dashboard/Gauges';
import { Knob } from '@/components/dashboard/Knob';
import { Panel } from '@/components/dashboard/Tile';
import { InlineError } from '@/components/common/InlineError';
import { useMetricSnapshot } from '@/features/metrics/hooks';
import { Modal } from '@/components/common/Modal';
import { Button } from '@/components/ui/button';
import { ApiError } from '@/lib/apiClient';
import { REQUIRED_ROLE, useMe } from '@/lib/identity';
import { getDrain, setDrain, type DrainState } from '../api/drain';
import {
  getLimits,
  limitText,
  setLimits,
  type SchedulerLimits,
} from '../api/scheduler';
import { PanelState } from './PanelState';
import { tagOf } from './format';

const LIMITS_KEY = ['control', 'scheduler', 'limits'];
const DRAIN_KEY = ['control', 'scheduler', 'drain'];
/** The knob goes to this when no limit is set, so there is something to turn. */
const KNOB_MAX = 64;

const failure = (error: Error | null) =>
  error instanceof ApiError && error.status === 403
    ? 'This needs an ADMIN key.'
    : error?.message;

/** Running against the limit, the limit as a knob, the drain switch and the refused starts. */
export function CapacityPanel({ live }: { live: boolean }) {
  const client = useQueryClient();
  const limits = useQuery({
    queryKey: LIMITS_KEY,
    queryFn: getLimits,
    refetchInterval: live ? 5000 : false,
  });
  const drain = useQuery({
    queryKey: DRAIN_KEY,
    queryFn: getDrain,
    refetchInterval: live ? 5000 : false,
  });
  const snapshot = useMetricSnapshot().data;
  const [turning, setTurning] = useState<number | null>(null);
  const [confirmDrain, setConfirmDrain] = useState(false);
  // Shown disabled, with the role it needs, to a key that may not change the limits
  const me = useMe();
  const mayChange = !me || me.permissions.changeLimits;
  const needs = mayChange ? undefined : `Needs ${REQUIRED_ROLE.changeLimits}`;

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
  const rejected = Object.entries(snapshot ?? {})
    .filter(([key]) => key.startsWith('yukta.executions.rejected'))
    .map(([key, rate]) => ({
      reason: tagOf(key, 'reason') ?? key,
      perMinute: rate * 60,
    }))
    .filter((r) => r.perMinute > 0);

  return (
    <Panel title="Capacity">
      <PanelState
        isPending={limits.isPending}
        error={limits.error}
        empty={!data}
        emptyText="No scheduler"
        onRetry={() => void limits.refetch()}
      >
        {data && (
          <div className="flex flex-col gap-3">
            <div className="flex items-center gap-4">
              <ArcGauge
                label="Running of limit"
                value={data.running}
                max={max > 0 ? max : Math.max(1, data.running)}
                tone={max > 0 && data.running >= max ? 'attention' : 'primary'}
              />
              <dl className="flex flex-col text-body-sm text-on-surface-variant">
                <dt>Running</dt>
                <dd className="num text-on-surface">
                  {data.running} of {limitText(max)}
                </dd>
                <dt>Waiting</dt>
                <dd className="num text-on-surface">
                  {data.queued} of {data.queueCapacity}
                </dd>
              </dl>
            </div>
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
            <label className="flex items-center gap-2 text-body-md">
              <input
                type="checkbox"
                role="switch"
                checked={drain.data?.draining ?? false}
                disabled={
                  !mayChange || drain.isPending || toggleDrain.isPending
                }
                title={needs}
                // Ending a drain is safe; starting one refuses every new start, so it asks first
                onChange={(e) =>
                  e.target.checked
                    ? setConfirmDrain(true)
                    : toggleDrain.mutate(false)
                }
              />
              Drain: refuse new starts
              {drain.data?.draining && (
                <span className="text-body-sm text-on-surface-variant">
                  {drain.data.idle ? '(idle)' : '(running work finishes)'}
                </span>
              )}
            </label>
            {rejected.length > 0 && (
              <ul
                aria-label="Refused starts per minute"
                className="flex flex-wrap gap-2 text-body-sm"
              >
                {rejected.map((r) => (
                  <li
                    key={r.reason}
                    className="rounded-full bg-muted px-2 py-0.5 tabular-nums"
                  >
                    {r.reason}: {r.perMinute.toFixed(1)}/min
                  </li>
                ))}
              </ul>
            )}
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
    </Panel>
  );
}
