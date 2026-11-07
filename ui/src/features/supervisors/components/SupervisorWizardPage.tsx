// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useNavigate, useParams, useSearchParams } from 'react-router';
import { Callout } from '@/components/common/Callout';
import { StateMessage } from '@/components/common/StateMessage';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { NativeSelect } from '@/components/ui/native-select';
import { PageHeader } from '@/components/layout/PageHeader';
import { ConfigForm } from '@/features/builder/components/ConfigForm';
import { validateSupervisor } from '../api/supervisors';
import {
  useCreateSupervisor,
  useReplaceSupervisor,
  useSupervisor,
  useSupervisorType,
  useSupervisorTypes,
} from '../hooks/useSupervisorTypes';
import type { SupervisorDraft, SupervisorView } from '../types';

const STEPS = ['Type', 'Settings', 'Permissions', 'Review'] as const;

/** Settings of the wizard's own, kept apart from the type's: the common ones. */
function Wizard({
  initial,
  existing,
  onSaved,
}: {
  initial: Pick<SupervisorDraft, 'type' | 'settings' | 'dryRun'> & {
    id: string;
    description: string;
  };
  existing: boolean;
  /** Called with the id instead of going to the supervisor's page (the drawer). */
  onSaved?: (id: string) => void;
}) {
  const navigate = useNavigate();
  const [step, setStep] = useState(initial.type ? 1 : 0);
  const [id, setId] = useState(initial.id);
  const [type, setType] = useState(initial.type);
  const [description, setDescription] = useState(initial.description);
  const [dryRun, setDryRun] = useState(initial.dryRun);
  const [settings, setSettings] = useState(initial.settings);
  const [advanced, setAdvanced] = useState(false);
  const [json, setJson] = useState(JSON.stringify(initial.settings, null, 2));
  const types = useSupervisorTypes().data ?? [];
  const details = useSupervisorType(type || null).data;
  const create = useCreateSupervisor();
  const replace = useReplaceSupervisor();
  const draft: SupervisorDraft = {
    id: id || undefined,
    type,
    description: description || undefined,
    settings,
    dryRun,
  };
  const check = useQuery({
    queryKey: ['supervisors', 'validate', JSON.stringify(draft)],
    queryFn: () => validateSupervisor(draft),
    enabled: type !== '',
    placeholderData: keepPreviousData,
  });
  const errors = check.data?.errors ?? [];
  const saving = create.isPending || replace.isPending;
  const failure = create.error ?? replace.error;

  const editJson = (text: string) => {
    setJson(text);
    try {
      const parsed: unknown = JSON.parse(text);
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        setSettings(parsed as Record<string, unknown>);
      }
    } catch {
      // the text is half typed; the settings keep their last good value
    }
  };
  const save = () => {
    const done = () =>
      onSaved
        ? onSaved(id)
        : navigate(`/supervisors/${encodeURIComponent(id)}`);
    if (existing) replace.mutate({ ...draft, id }, { onSuccess: done });
    else create.mutate(draft, { onSuccess: done });
  };

  return (
    <div className="flex flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
      <PageHeader
        backdrop
        title={existing ? `Edit ${initial.id}` : 'New supervisor'}
        description="Choose a type, set it up, say what it may do, then review."
        actions={
          <ol className="flex flex-wrap gap-3">
            {STEPS.map((name, index) => (
              <li
                key={name}
                aria-current={index === step ? 'step' : undefined}
                className={
                  index === step ? 'font-semibold' : 'text-on-surface-variant'
                }
              >
                {index + 1}. {name}
              </li>
            ))}
          </ol>
        }
      />
      {step === 0 && (
        <label className="text-label-md flex max-w-md flex-col gap-1">
          Supervisor type
          <NativeSelect
            aria-label="Supervisor type"
            value={type}
            onChange={(event) => setType(event.target.value)}
          >
            <option value="">Choose a type</option>
            {types.map((t) => (
              <option key={t.type} value={t.type}>
                {t.displayName}
              </option>
            ))}
          </NativeSelect>
        </label>
      )}
      {step === 1 && (
        <div className="flex max-w-2xl flex-col gap-4">
          <label className="text-label-md flex flex-col gap-1">
            Name
            <Input
              value={id}
              disabled={existing}
              onChange={(event) => setId(event.target.value)}
            />
          </label>
          <label className="text-label-md flex flex-col gap-1">
            Description
            <Input
              value={description}
              onChange={(event) => setDescription(event.target.value)}
            />
          </label>
          <label className="text-label-md flex items-center gap-2">
            <input
              type="checkbox"
              checked={advanced}
              onChange={(event) => {
                setAdvanced(event.target.checked);
                setJson(JSON.stringify(settings, null, 2));
              }}
            />
            Advanced: JSON
          </label>
          {advanced ? (
            <textarea
              aria-label="Settings JSON"
              rows={12}
              spellCheck={false}
              className="border-border bg-muted text-code-sm rounded-lg border p-3"
              value={json}
              onChange={(event) => editJson(event.target.value)}
            />
          ) : details ? (
            <ConfigForm
              schema={details.configSchema}
              values={settings}
              onChange={setSettings}
            />
          ) : (
            <StateMessage tone="loading" compact title="Loading…" />
          )}
        </div>
      )}
      {step === 2 && (
        <div className="flex max-w-2xl flex-col gap-4">
          <label className="text-label-md flex items-center gap-2">
            <input
              type="checkbox"
              checked={dryRun}
              onChange={(event) => setDryRun(event.target.checked)}
            />
            Dry run: only record what it would do
          </label>
          {details && (
            <ConfigForm
              schema={details.commonSchema}
              values={settings}
              onChange={setSettings}
            />
          )}
        </div>
      )}
      {step === 3 && (
        <pre className="border-border bg-muted text-code-sm overflow-auto rounded-lg border p-3">
          {JSON.stringify(draft, null, 2)}
        </pre>
      )}
      {errors.length > 0 && (
        <Callout kind="danger">
          <ul>
            {errors.map((e) => (
              <li key={`${e.field}${e.message}`}>
                <b>{e.field}</b> {e.message}
              </li>
            ))}
          </ul>
        </Callout>
      )}
      {failure && <Callout kind="danger">{failure.message}</Callout>}
      <div className="flex gap-2">
        <Button
          type="button"
          variant="outline"
          disabled={step === 0}
          onClick={() => setStep(step - 1)}
        >
          Back
        </Button>
        {step < STEPS.length - 1 ? (
          <Button
            type="button"
            disabled={
              (step === 0 && type === '') ||
              (step === 1 && (id === '' || errors.length > 0))
            }
            onClick={() => setStep(step + 1)}
          >
            Next
          </Button>
        ) : (
          <Button
            type="button"
            disabled={saving || errors.length > 0 || id === ''}
            onClick={save}
          >
            Save
          </Button>
        )}
      </div>
    </div>
  );
}

const startFrom = (s: SupervisorView) => ({
  id: s.id,
  type: s.type,
  description: s.description ?? '',
  settings: s.settings,
  dryRun: s.dryRun,
});

/** Creates a supervisor (`/supervisors/new?type=`) or edits one (`/supervisors/:id/edit`). */
export function SupervisorWizardPage() {
  const { supervisorId } = useParams();
  const [params] = useSearchParams();
  const { data, isPending, error } = useSupervisor(supervisorId);
  if (supervisorId === undefined) {
    return (
      <Wizard
        existing={false}
        initial={{
          id: '',
          type: params.get('type') ?? '',
          description: '',
          settings: {},
          dryRun: false,
        }}
      />
    );
  }
  if (isPending) return <StateMessage tone="loading" title="Loading…" />;
  if (error || !data) {
    return <StateMessage tone="error" title="Failed to load the supervisor." />;
  }
  return <Wizard existing initial={startFrom(data)} />;
}

/** The wizard for a new supervisor, to be shown in a drawer: it reports the id instead of navigating. */
export function NewSupervisorWizard({
  onSaved,
}: {
  onSaved: (id: string) => void;
}) {
  return (
    <Wizard
      existing={false}
      onSaved={onSaved}
      initial={{
        id: '',
        type: '',
        description: '',
        settings: {},
        dryRun: false,
      }}
    />
  );
}
