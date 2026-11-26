// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Callout } from './Callout';
import {
  dismissApiKey,
  getApiKeyNotice,
  lockoutMessage,
  setApiKey,
} from '@/lib/apiKey';
import { useMe } from '@/lib/identity';

/** Why a key was refused, in words; null when it was accepted. */
async function check(key: string): Promise<string | null> {
  let response: Response;
  try {
    // one request to /api/me, not a reload that would send every page query with a wrong key
    response = await fetch('/api/me', {
      headers: { Authorization: `Bearer ${key}` },
    });
  } catch {
    return 'Could not reach the server. Check that it is running, then try again.';
  }
  if (response.ok) {
    return null;
  }
  if (response.status === 401) {
    return 'That key was not accepted. Check it and try again.';
  }
  if (response.status === 429) {
    return lockoutMessage(response.headers.get('Retry-After'));
  }
  return `The server answered ${response.status}. Try again.`;
}

/**
 * Takes the place of the whole app while the server wants a key: nothing behind it can be opened,
 * and nothing polls the server. The key is checked before it is kept, then the page reloads.
 */
export default function ApiKeyPrompt() {
  const me = useMe();
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  // the gate opens with the server's reason, if it refused the key in use
  const [error, setError] = useState<string | null>(getApiKeyNotice);

  const submit = async () => {
    const value = key.trim();
    setBusy(true);
    setError(null);
    const refused = await check(value);
    if (refused) {
      setError(refused);
      setBusy(false);
      return;
    }
    setApiKey(value);
    window.location.reload();
  };

  return (
    <div className="bg-background text-on-surface flex min-h-screen items-center justify-center px-4">
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby="api-key-title"
        onSubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
        className="border-border bg-card shadow-raised flex w-full max-w-sm flex-col gap-5 rounded-2xl border p-6"
      >
        <div className="flex items-center gap-2">
          <img src="/favicon.svg" alt="" className="size-8 object-contain" />
          <span className="text-label-md">Yukta</span>
        </div>
        <div className="flex flex-col gap-1">
          <h1 id="api-key-title" className="text-headline-md">
            Enter your API key
          </h1>
          <p className="text-body-md text-on-surface-variant">
            This server requires an API key. Ask whoever runs it for one.
          </p>
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="api-key" className="text-label-md">
            API key
          </label>
          <Input
            id="api-key"
            type="password"
            autoComplete="off"
            spellCheck={false}
            placeholder="Paste your key"
            autoFocus
            value={key}
            aria-invalid={error !== null}
            aria-describedby="api-key-hint"
            onChange={(event) => {
              setKey(event.target.value);
              setError(null);
            }}
          />
          <p id="api-key-hint" className="text-body-sm text-on-surface-variant">
            Kept in this browser tab only, and cleared when you close it.
          </p>
        </div>
        {error && (
          <Callout kind="danger" compact>
            {error}
          </Callout>
        )}
        <div className="flex flex-col gap-2">
          <Button type="submit" disabled={!key.trim() || busy}>
            {busy ? 'Checking…' : 'Continue'}
          </Button>
          {me?.authenticated && getApiKeyNotice() === null && (
            <Button type="button" variant="ghost" onClick={dismissApiKey}>
              Keep the current key
            </Button>
          )}
        </div>
      </form>
    </div>
  );
}
