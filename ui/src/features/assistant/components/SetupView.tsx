// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { InlineError } from '@/components/common/InlineError';
import { Button } from '@/components/ui/button';
import { inputFieldClassName } from '@/components/ui/native-select';
import { cn } from '@/lib/utils';
import type { useNano } from '../hooks/useNano';
import { buildModel } from '../lib/models';
import type { Provider } from '../lib/proxy';
import type { Settings } from '../lib/settings';
import type { ChatModel } from '../types';

/** The API formats a provider can speak; `model` is the name each starts with. */
const FORMATS: { id: Provider; label: string; model: string }[] = [
  { id: 'anthropic', label: 'Anthropic', model: 'claude-sonnet-5-5' },
  { id: 'openai', label: 'OpenAI-compatible', model: '' },
];

/** Older settings chose `auto`: the built-in model first, the key (by base URL) otherwise. */
const normalize = (settings: Settings): Settings =>
  settings.provider === 'auto'
    ? {
        ...settings,
        provider: settings.baseUrl ? 'openai' : 'anthropic',
        device: true,
      }
    : settings;

/** What the person needs to know about the built-in model, by state. */
const NANO_TEXT: Record<string, string> = {
  checking: 'Checking this browser.',
  unsupported:
    'This browser has no built-in model. Chrome 148 or later on a desktop has one.',
  unavailable:
    'This device cannot run the built-in model. It needs about 22 GB of free disk and a GPU with more than 4 GB of memory, or 16 GB of RAM.',
  downloadable:
    "Chrome's built-in model (Gemini Nano) runs on this device. It is downloaded once, which takes a while.",
  downloading: 'Downloading the model.',
  available: "Chrome's built-in model (Gemini Nano) is ready.",
};

/** Where the model comes from: this device, or a provider with the person's own key. */
export function SetupView({
  settings,
  apiKey,
  nano,
  onSave,
  onHide,
}: {
  settings: Settings;
  apiKey: string | null;
  nano: ReturnType<typeof useNano>;
  /** Called with a setup that has just answered a test call (or is ready, for the built-in model). */
  onSave: (settings: Settings, key: string) => void;
  onHide: () => void;
}) {
  const [draft, setDraft] = useState(normalize(settings));
  const [key, setKey] = useState(apiKey ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const change = (next: Partial<Settings>) => setDraft({ ...draft, ...next });
  const onDevice = draft.provider === 'nano';
  const downloading = onDevice && nano.state === 'downloading';
  const needsDownload = onDevice && nano.state === 'downloadable';
  const useNano = onDevice || (draft.device && nano.state === 'available');
  const ready = useNano
    ? nano.state === 'available'
    : key.trim() !== '' && draft.model.trim() !== '';

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

  const submit = () => {
    if (!ready || busy) return;
    if (useNano) {
      onSave(draft, key);
    } else {
      void verify();
    }
  };

  return (
    <form
      className="flex min-h-0 flex-1 flex-col overflow-y-auto"
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
    >
      <div className="flex flex-1 flex-col gap-5 p-4">
        <div>
          <h3 className="text-label-md">Choose a model</h3>
          <p className="text-label-sm text-on-surface-variant">
            Where the assistant's answers come from.
          </p>
        </div>
        <div
          role="group"
          aria-label="Model source"
          className="flex flex-col gap-2"
        >
          {[
            {
              id: 'nano',
              label: 'On this device',
              hint: "Chrome's built-in model. Private: nothing leaves this browser.",
              selected: onDevice,
              show: nano.state !== 'unsupported' || onDevice,
              pick: () => change({ provider: 'nano' }),
            },
            {
              id: 'provider',
              label: 'Your own provider',
              hint: 'Claude, OpenAI or any compatible service, with your own API key.',
              selected: !onDevice,
              show: true,
              pick: () =>
                onDevice &&
                change({ provider: 'anthropic', model: FORMATS[0].model }),
            },
          ]
            .filter(({ show }) => show)
            .map(({ id, label, hint, selected, pick }) => (
              <button
                key={id}
                type="button"
                aria-label={label}
                aria-pressed={selected}
                onClick={pick}
                className={cn(
                  'transition-standard flex items-start gap-3 rounded-xl border px-3 py-2.5 text-left',
                  selected
                    ? 'border-primary/60 bg-primary/10'
                    : 'border-border hover:bg-primary/5'
                )}
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    'mt-0.5 flex size-4 shrink-0 items-center justify-center rounded-full border',
                    selected ? 'border-primary' : 'border-outline-variant'
                  )}
                >
                  {selected && (
                    <span className="assistant-gradient size-2 rounded-full" />
                  )}
                </span>
                <span aria-hidden="true" className="flex flex-col">
                  <span className="text-label-md">{label}</span>
                  <span className="text-label-sm text-on-surface-variant">
                    {hint}
                  </span>
                </span>
              </button>
            ))}
        </div>
        {onDevice ? (
          <div className="border-border bg-card/60 flex flex-col gap-2 rounded-xl border p-3">
            <p className="text-body-md">{NANO_TEXT[nano.state]}</p>
            {downloading && (
              <div
                role="progressbar"
                aria-label="Model download"
                aria-valuemin={0}
                aria-valuemax={100}
                aria-valuenow={Math.round(nano.progress * 100)}
                className="bg-surface-container h-1.5 overflow-hidden rounded-full"
              >
                <div
                  className="assistant-gradient h-full"
                  style={{ width: `${Math.round(nano.progress * 100)}%` }}
                />
              </div>
            )}
          </div>
        ) : (
          <div className="border-border bg-card/60 flex flex-col gap-3 rounded-xl border p-3">
            <div
              role="group"
              aria-label="API format"
              className="bg-surface-container flex gap-1 rounded-lg p-1"
            >
              {FORMATS.map(({ id, label, model }) => (
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
                    'text-label-md transition-standard flex-1 rounded-md px-2 py-1.5 leading-tight',
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
            {nano.state !== 'unsupported' && (
              <label className="text-body-md flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={draft.device}
                  onChange={(event) => change({ device: event.target.checked })}
                />
                Use the built-in model when it is ready
              </label>
            )}
          </div>
        )}
        <InlineError message={error} />
      </div>
      <div className="border-primary/15 bg-card/60 sticky bottom-0 flex flex-col gap-3 border-t p-4 backdrop-blur-sm">
        {needsDownload ? (
          <Button type="button" onClick={() => void nano.download()}>
            Download model
          </Button>
        ) : (
          <Button type="submit" disabled={!ready || busy}>
            {busy
              ? 'Checking…'
              : useNano
                ? downloading
                  ? 'Downloading…'
                  : 'Use this model'
                : 'Save and test'}
          </Button>
        )}
        <div className="flex flex-col items-start gap-1">
          <Button type="button" variant="ghost" size="sm" onClick={onHide}>
            Hide the assistant button
          </Button>
          <p className="text-label-sm text-on-surface-variant px-2">
            To bring it back, open the jump palette (Ctrl+K or ⌘K) and choose
            "Ask the assistant".
          </p>
        </div>
      </div>
    </form>
  );
}
