// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { CodeBlock } from '@/components/common/CodeBlock';
import { RequirePermission } from '@/components/common/RequirePermission';
import { StateMessage } from '@/components/common/StateMessage';
import { PageHeader } from '@/components/layout/PageHeader';
import { Button, buttonVariants } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  NativeSelect,
  textareaFieldClassName,
} from '@/components/ui/native-select';
import { ApiError } from '@/lib/apiClient';
import {
  useCreateSupervisor,
  useReplaceSupervisor,
  useSupervisor,
  useSupervisorTypes,
} from '../hooks/useSupervisorTypes';
import type { SupervisorDraft } from '../types';

/** What a supervisor id may be (as the server's `SupervisorIds.RULE`). */
const ID = /^[A-Za-z0-9._-]{1,64}$/;

interface Values {
  id: string;
  type: string;
  description: string;
  dryRun: boolean;
  parents: string;
  settings: string;
}

/** The settings text as an object, or the reason it is not one. */
function parseSettings(text: string): Record<string, unknown> | string {
  if (text.trim() === '') return {};
  try {
    const value: unknown = JSON.parse(text);
    return value && typeof value === 'object' && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : 'Settings must be a JSON object';
  } catch (e) {
    return `Settings are not valid JSON: ${(e as Error).message}`;
  }
}

/**
 * A plain form for a quick change by hand. Most supervisors are made and changed by an agent over
 * MCP or the CLI; what a supervisor owns is set there or in a definition, not here.
 */
function SupervisorForm({
  initial,
  existing,
  onSaved,
}: {
  initial: Values;
  existing: boolean;
  onSaved: (id: string) => void;
}) {
  const [values, setValues] = useState(initial);
  const types = useSupervisorTypes();
  const create = useCreateSupervisor();
  const replace = useReplaceSupervisor();
  const saving = create.isPending || replace.isPending;
  const error = create.error ?? replace.error;
  const parsed = parseSettings(values.settings);
  const badId = !existing && values.id !== '' && !ID.test(values.id);
  const blocked =
    typeof parsed === 'string' || !ID.test(values.id) || values.type === '';
  const set = (patch: Partial<Values>) => setValues({ ...values, ...patch });
  const save = () => {
    const draft: SupervisorDraft & { id: string } = {
      id: values.id,
      type: values.type,
      description: values.description || undefined,
      dryRun: values.dryRun,
      parents: values.parents
        .split(',')
        .map((p) => p.trim())
        .filter(Boolean),
      settings: parsed as Record<string, unknown>,
    };
    (existing ? replace : create).mutate(draft, {
      onSuccess: () => onSaved(values.id),
    });
  };
  return (
    <form
      className="flex max-w-xl flex-col gap-3"
      onSubmit={(e) => {
        e.preventDefault();
        save();
      }}
    >
      <label className="flex flex-col gap-1">
        <span className="text-label-md">Name</span>
        <Input
          aria-label="Name"
          value={values.id}
          disabled={existing}
          aria-invalid={badId}
          aria-describedby="supervisor-id-hint"
          onChange={(e) => set({ id: e.target.value })}
        />
        <span
          id="supervisor-id-hint"
          className={
            badId
              ? 'text-body-sm text-error'
              : 'text-body-sm text-muted-foreground'
          }
        >
          {badId
            ? 'Use letters, digits, dot, dash or underscore, up to 64'
            : 'Letters, digits, dot, dash or underscore'}
        </span>
      </label>
      <label className="flex flex-col gap-1">
        <span className="text-label-md">Description</span>
        <Input
          aria-label="Description"
          value={values.description}
          onChange={(e) => set({ description: e.target.value })}
        />
      </label>
      <label className="flex flex-col gap-1">
        <span className="text-label-md">Decision maker</span>
        <NativeSelect
          aria-label="Decision maker"
          value={values.type}
          onChange={(e) => set({ type: e.target.value })}
        >
          <option value="">Choose…</option>
          {(types.data ?? []).map((t) => (
            <option key={t.type} value={t.type}>
              {t.displayName}
            </option>
          ))}
        </NativeSelect>
        {existing && (
          <span className="text-body-sm text-muted-foreground">
            Changing it replaces the settings below.
          </span>
        )}
      </label>
      <label className="text-body-md flex items-center gap-2">
        <input
          type="checkbox"
          checked={values.dryRun}
          onChange={(e) => set({ dryRun: e.target.checked })}
        />
        Dry run: record its commands, apply none
      </label>
      <label className="flex flex-col gap-1">
        <span className="text-label-md">Reports to</span>
        <Input
          aria-label="Reports to"
          value={values.parents}
          placeholder="Supervisor ids, comma separated"
          onChange={(e) => set({ parents: e.target.value })}
        />
        <span className="text-body-sm text-muted-foreground">
          A cycle is refused when it is saved.
        </span>
      </label>
      <label className="flex flex-col gap-1">
        <span className="text-label-md">Settings (JSON)</span>
        <textarea
          rows={8}
          className={`${textareaFieldClassName} font-mono`}
          aria-label="Settings (JSON)"
          value={values.settings}
          aria-invalid={typeof parsed === 'string'}
          onChange={(e) => set({ settings: e.target.value })}
        />
        {typeof parsed === 'string' && (
          <span className="text-body-sm text-error">{parsed}</span>
        )}
        <span className="text-body-sm text-muted-foreground">
          A secret shown as *** stays as it is.
        </span>
      </label>
      {error && (
        <div role="alert" className="text-body-sm text-error">
          <p>{error.message}</p>
          {error instanceof ApiError && (
            <ul>
              {(error.fieldErrors ?? []).map((f) => (
                <li key={f.field}>
                  {f.field}: {f.message}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      <div className="flex gap-2">
        <RequirePermission perm="editSessions">
          <Button type="submit" disabled={blocked || saving}>
            {saving ? 'Saving…' : 'Save'}
          </Button>
        </RequirePermission>
      </div>
    </form>
  );
}

const asValues = (s: {
  id: string;
  type: string;
  description: string | null;
  dryRun: boolean;
  parents?: string[];
  settings: Record<string, unknown>;
}): Values => ({
  id: s.id,
  type: s.type,
  description: s.description ?? '',
  dryRun: s.dryRun,
  parents: (s.parents ?? []).join(', '),
  settings: JSON.stringify(s.settings, null, 2),
});

/** The form in a drawer, for the definition editors' "Create new": starts in dry run. */
export function NewSupervisorForm({
  onSaved,
}: {
  onSaved: (id: string) => void;
}) {
  return (
    <SupervisorForm
      existing={false}
      onSaved={onSaved}
      initial={{
        id: '',
        type: '',
        description: '',
        dryRun: true,
        parents: '',
        settings: '{}',
      }}
    />
  );
}

/** `/supervisors/new` and `/supervisors/:supervisorId/edit`. */
export function SupervisorEditPage() {
  const { supervisorId } = useParams();
  const navigate = useNavigate();
  const loaded = useSupervisor(supervisorId);
  const done = (id: string) =>
    navigate(`/supervisors?supervisor=${encodeURIComponent(id)}`);
  const id = supervisorId ?? '<id>';
  return (
    <div className="flex flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
      <PageHeader
        title={supervisorId ? `Edit ${supervisorId}` : 'New supervisor'}
        description="For a quick change by hand. Most changes come from an agent; the same change over MCP or the CLI is below."
        actions={
          <Link
            to="/supervisors"
            className={buttonVariants({ variant: 'outline' })}
          >
            Back
          </Link>
        }
      />
      {supervisorId && loaded.isPending && (
        <StateMessage tone="loading" title="Loading…" />
      )}
      {supervisorId && loaded.error && (
        <StateMessage tone="error" title={`Failed to load ${supervisorId}.`} />
      )}
      {(!supervisorId || loaded.data) && (
        <SupervisorForm
          key={loaded.data?.id ?? 'new'}
          existing={Boolean(supervisorId)}
          onSaved={done}
          initial={
            loaded.data
              ? asValues(loaded.data)
              : {
                  id: '',
                  type: '',
                  description: '',
                  dryRun: true,
                  parents: '',
                  settings: '{}',
                }
          }
        />
      )}
      <div className="max-w-xl">
        <CodeBlock
          label="The same with an agent"
          code={[
            `MCP  save_supervisor {"id": "${id}", "type": …, "settings": {…}}`,
            `CLI  yukta supervisors show ${id}`,
            `CLI  yukta supervisors assign ${id} <target>`,
          ].join('\n')}
        />
      </div>
    </div>
  );
}
