// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  NativeSelect,
  textareaFieldClassName,
} from '@/components/ui/native-select';
import { Callout } from '@/components/common/Callout';
import { Modal } from '@/components/common/Modal';
import { ApiError } from '@/lib/apiClient';
import { parseSettings, type SupervisorProfile } from '../api/supervisors';
import {
  useCreateProfile,
  useReplaceProfile,
  useSupervisorTypes,
} from './useSupervisorData';

/** What each supervisor type reads, as a starting point for the settings. */
const EXAMPLES: Record<string, string> = {
  'rule-supervisor':
    '{"rules": [{"id": "pause-on-failure", "when": {"event": "NodeStatusChanged", "status": "FAILURE"}, "then": {"command": "PauseWorkflow"}}]}',
  'rule-control-plane': '{"rules": []}',
  'model-control-plane': '{"name": "tev1:0.8b"}',
  'rules-first-control-plane': '{"rules": [], "model": {"name": "tev1:0.8b"}}',
};

const message = (error: Error | null) =>
  error instanceof ApiError && error.status === 403
    ? 'Changing supervisors needs an ADMIN key.'
    : error?.message;

/**
 * Creates a profile, or edits one (its id and type stay). Settings are JSON; a value shown as
 * `***` is a secret, and sent back as it is it keeps the stored value.
 */
export function ProfileDialog({
  profile,
  onClose,
}: {
  profile?: SupervisorProfile;
  onClose: () => void;
}) {
  const types = useSupervisorTypes().data ?? [];
  const create = useCreateProfile();
  const replace = useReplaceProfile();
  const editing = profile !== undefined;
  const [id, setId] = useState(profile?.id ?? '');
  const [type, setType] = useState(profile?.type ?? '');
  const [dryRun, setDryRun] = useState(profile?.dryRun ?? false);
  const [text, setText] = useState(
    profile ? JSON.stringify(profile.settings, null, 2) : ''
  );
  const chosenType = type || types[0] || '';
  const parsed = parseSettings(text.trim() === '' ? '{}' : text);
  const problem =
    id.trim() === ''
      ? 'Give the profile an id.'
      : chosenType === ''
        ? 'Choose a type.'
        : 'error' in parsed
          ? parsed.error
          : null;
  const saving = editing ? replace : create;

  const save = () => {
    if (problem || 'error' in parsed) return;
    saving.mutate(
      { id: id.trim(), type: chosenType, settings: parsed.settings, dryRun },
      { onSuccess: onClose }
    );
  };

  return (
    <Modal
      title={editing ? 'Edit supervisor profile' : 'New supervisor profile'}
      onClose={onClose}
    >
      <div className="flex flex-col gap-3">
        <label className="flex flex-col gap-1 text-label-md text-on-surface">
          Profile id
          <Input
            value={id}
            disabled={editing}
            onChange={(e) => setId(e.target.value)}
          />
        </label>
        <label className="flex flex-col gap-1 text-label-md text-on-surface">
          Type
          <NativeSelect
            aria-label="Type"
            value={chosenType}
            disabled={editing}
            onChange={(e) => setType(e.target.value)}
          >
            {(editing && !types.includes(chosenType)
              ? [chosenType, ...types]
              : types
            ).map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </NativeSelect>
        </label>
        <label className="flex items-center gap-2 text-body-md">
          <input
            type="checkbox"
            checked={dryRun}
            onChange={(e) => setDryRun(e.target.checked)}
          />
          Dry run: only record what it would do
        </label>
        <label className="flex flex-col gap-1 text-label-md text-on-surface">
          Settings (JSON)
          <textarea
            rows={8}
            spellCheck={false}
            value={text}
            placeholder={EXAMPLES[chosenType] ?? '{}'}
            aria-invalid={'error' in parsed}
            onChange={(e) => setText(e.target.value)}
            className={`${textareaFieldClassName} font-mono`}
          />
        </label>
        <p className="text-body-sm text-on-surface-variant">
          Secrets such as an API key are shown as <code>***</code>. Leave them
          as they are to keep the stored value.
        </p>
        {problem && id.trim() !== '' && (
          <Callout kind="warning" compact>
            {problem}
          </Callout>
        )}
        {saving.error && (
          <Callout kind="danger" compact>
            {message(saving.error)}
          </Callout>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            onClick={save}
            disabled={problem !== null || saving.isPending}
          >
            {editing ? 'Save' : 'Create'}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
