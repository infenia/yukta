// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { NativeSelect } from '@/components/ui/native-select';
import { Sheet, SheetContent, SheetTitle } from '@/components/ui/sheet';
import { useSupervisors } from '../hooks/useSupervisorTypes';
import type { SupervisorRef } from '../types';
import { groupNodes } from '../lib/scope';
import { NewSupervisorWizard } from './SupervisorWizardPage';

/**
 * Picks the supervisors a session or workflow declares. With `nodeIds` (a workflow) each
 * supervisor covers the whole workflow or chosen nodes; without them (a session) only the
 * supervisor and whether it runs. "Create new" opens the wizard in a drawer and adds the result.
 */
export function SupervisorPicker({
  value,
  onChange,
  nodeIds,
  errors = [],
}: {
  value: SupervisorRef[];
  onChange: (next: SupervisorRef[]) => void;
  nodeIds?: string[];
  /** Messages from the server about these refs, shown under the picker. */
  errors?: string[];
}) {
  const configured = (useSupervisors().data ?? []).filter((s) => !s.system);
  const [creating, setCreating] = useState(false);
  const change = (index: number, patch: Partial<SupervisorRef>) =>
    onChange(value.map((ref, i) => (i === index ? { ...ref, ...patch } : ref)));
  const toggleNode = (index: number, id: string, selected: string[]) =>
    change(index, {
      nodes: selected.includes(id)
        ? selected.filter((n) => n !== id)
        : [...selected, id],
    });
  const unused = configured.find(
    (s) => !value.some((ref) => ref.supervisor === s.id)
  );

  const nodePicker = (index: number, ids: string[], selected: string[]) => (
    <fieldset className="border-border max-h-40 overflow-auto rounded-lg border p-2">
      <legend className="text-label-sm px-1">Nodes</legend>
      {groupNodes(ids).map(([prefix, ids]) => (
        <div key={prefix}>
          <div className="text-label-sm text-on-surface-variant">
            {prefix ? `${prefix}.*` : 'No prefix'}
          </div>
          {ids.map((id) => (
            <label key={id} className="text-body-md flex items-center gap-2">
              <input
                type="checkbox"
                checked={selected.includes(id)}
                onChange={() => toggleNode(index, id, selected)}
              />
              {id}
            </label>
          ))}
        </div>
      ))}
    </fieldset>
  );

  return (
    <div className="flex flex-col gap-2">
      {value.map((ref, index) => (
        <div
          key={index}
          className="border-border flex flex-col gap-2 rounded-lg border p-3"
        >
          <div className="flex flex-wrap items-center gap-3">
            <NativeSelect
              aria-label={`Supervisor ${index + 1}`}
              value={ref.supervisor}
              onChange={(event) =>
                change(index, { supervisor: event.target.value })
              }
            >
              {!configured.some((s) => s.id === ref.supervisor) && (
                <option value={ref.supervisor}>{ref.supervisor}</option>
              )}
              {configured.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.id}
                </option>
              ))}
            </NativeSelect>
            {nodeIds && (
              <div
                role="group"
                aria-label={`Scope of supervisor ${index + 1}`}
                className="flex gap-3"
              >
                <label className="text-label-md flex items-center gap-1">
                  <input
                    type="radio"
                    name={`scope-${index}`}
                    checked={!ref.nodes}
                    onChange={() => change(index, { nodes: null })}
                  />
                  Whole workflow
                </label>
                <label className="text-label-md flex items-center gap-1">
                  <input
                    type="radio"
                    name={`scope-${index}`}
                    checked={Boolean(ref.nodes)}
                    onChange={() => change(index, { nodes: [] })}
                  />
                  Selected nodes
                </label>
              </div>
            )}
            <label className="text-label-md flex items-center gap-1">
              <input
                type="checkbox"
                checked={ref.enabled !== false}
                onChange={(event) =>
                  change(index, { enabled: event.target.checked })
                }
              />
              Runs
            </label>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="ml-auto"
              aria-label={`Remove supervisor ${index + 1}`}
              onClick={() => onChange(value.filter((_, i) => i !== index))}
            >
              Remove
            </Button>
          </div>
          {nodeIds && ref.nodes && nodePicker(index, nodeIds, ref.nodes)}
        </div>
      ))}
      <div className="flex flex-wrap gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={unused === undefined}
          onClick={() =>
            unused && onChange([...value, { supervisor: unused.id }])
          }
        >
          Add supervisor
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => setCreating(true)}
        >
          Create new
        </Button>
      </div>
      {errors.map((message) => (
        <p key={message} role="alert" className="text-body-sm text-error">
          {message}
        </p>
      ))}
      {creating && (
        <Sheet open onOpenChange={(open) => !open && setCreating(false)}>
          <SheetContent>
            <SheetTitle>New supervisor</SheetTitle>
            <NewSupervisorWizard
              onSaved={(id) => {
                onChange([...value, { supervisor: id }]);
                setCreating(false);
              }}
            />
          </SheetContent>
        </Sheet>
      )}
    </div>
  );
}
