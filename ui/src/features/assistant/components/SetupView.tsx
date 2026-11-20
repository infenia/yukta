// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { InlineError } from '@/components/common/InlineError';
import { Button } from '@/components/ui/button';
import { inputFieldClassName } from '@/components/ui/native-select';
import { cn } from '@/lib/utils';
import { buildModel } from '../lib/models';
import type { Provider } from '../lib/proxy';
import type { Settings } from '../lib/settings';
import type { ChatModel } from '../types';

const PROVIDERS: { id: Provider; label: string; model: string }[] = [
  { id: 'anthropic', label: 'Anthropic', model: 'claude-sonnet-5-5' },
  { id: 'openai', label: 'OpenAI-compatible', model: '' },
];

/** Where the model comes from: a provider, a model name and the person's own key. */
export function SetupView({
  settings,
  apiKey,
  onSave,
  onHide,
}: {
  settings: Settings;
  apiKey: string | null;
  /** Called with a setup that has just answered a test call. */
  onSave: (settings: Settings, key: string) => void;
  onHide: () => void;
}) {
  const [draft, setDraft] = useState(settings);
  const [key, setKey] = useState(apiKey ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const change = (next: Partial<Settings>) => setDraft({ ...draft, ...next });
  const ready = key.trim() !== '' && draft.model.trim() !== '';

  const verify = async () => {
    setBusy(true);
    setError(null);
    const stop = new AbortController();
    try {
      const model = buildModel(draft, key.trim()) as ChatModel;
      await model
        .stream(
          'Reply with OK.',
          [{ role: 'user', text: 'Hi' }],
          [],
          stop.signal
        )
        [Symbol.asyncIterator]()
        .next();
      onSave(draft, key.trim());
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      stop.abort();
      setBusy(false);
    }
  };

  return (
    <form
      className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4"
      onSubmit={(event) => {
        event.preventDefault();
        if (ready && !busy) void verify();
      }}
    >
      <p className="text-body-md text-on-surface-variant">
        Connect a model with your own key.
      </p>
      <div
        role="group"
        aria-label="Provider"
        className="bg-surface-container flex gap-1 rounded-lg p-1"
      >
        {PROVIDERS.map(({ id, label, model }) => (
          <button
            key={id}
            type="button"
            aria-pressed={draft.provider === id}
            onClick={() =>
              change({
                provider: id,
                model: draft.provider === id ? draft.model : model,
              })
            }
            className={cn(
              'text-label-md transition-standard flex-1 rounded-md px-3 py-1.5',
              draft.provider === id
                ? 'bg-card text-on-surface shadow-card'
                : 'text-on-surface-variant hover:text-on-surface'
            )}
          >
            {label}
          </button>
        ))}
      </div>
      <label className="flex flex-col gap-1">
        <span className="text-label-md">Model</span>
        <input
          className={inputFieldClassName}
          value={draft.model}
          placeholder={
            draft.provider === 'openai' ? 'The model name' : undefined
          }
          onChange={(event) => change({ model: event.target.value })}
        />
      </label>
      {draft.provider === 'openai' && (
        <label className="flex flex-col gap-1">
          <span className="text-label-md">Base URL</span>
          <input
            className={inputFieldClassName}
            value={draft.baseUrl}
            placeholder="https://api.openai.com/v1"
            onChange={(event) => change({ baseUrl: event.target.value })}
          />
          <span className="text-label-sm text-on-surface-variant">
            The host must be listed in yukta.assistant.allowed-base-urls.
          </span>
        </label>
      )}
      <label className="flex flex-col gap-1">
        <span className="text-label-md">API key</span>
        <input
          type="password"
          autoComplete="off"
          className={inputFieldClassName}
          value={key}
          onChange={(event) => setKey(event.target.value)}
        />
      </label>
      <label className="text-body-md flex items-center gap-2">
        <input
          type="checkbox"
          checked={draft.remember}
          onChange={(event) => change({ remember: event.target.checked })}
        />
        Keep the key on this device
      </label>
      <InlineError message={error} />
      <div className="mt-auto flex items-center justify-between gap-2">
        <Button type="button" variant="ghost" onClick={onHide}>
          Hide the assistant button
        </Button>
        <Button type="submit" disabled={!ready || busy}>
          {busy ? 'Checking…' : 'Save and test'}
        </Button>
      </div>
    </form>
  );
}
