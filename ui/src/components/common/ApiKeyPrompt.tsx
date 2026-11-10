// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Callout } from './Callout';
import { API_KEY_REQUIRED_EVENT, setApiKey } from '@/lib/apiKey';

/** Asks for the API key once the server answers 401/403, then reloads with the key set. */
export default function ApiKeyPrompt() {
  const [open, setOpen] = useState(false);
  const [key, setKey] = useState('');

  useEffect(() => {
    const show = () => setOpen(true);
    window.addEventListener(API_KEY_REQUIRED_EVENT, show);
    return () => window.removeEventListener(API_KEY_REQUIRED_EVENT, show);
  }, []);

  if (!open) {
    return null;
  }

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setApiKey(key.trim());
    window.location.reload();
  };

  return (
    <form
      role="alertdialog"
      aria-label="API key required"
      onSubmit={submit}
      className="border-border bg-card shadow-raised fixed top-24 left-1/2 z-50 flex w-96 max-w-[90vw] -translate-x-1/2 flex-col gap-3 rounded-2xl border p-4"
    >
      <Callout kind="info" compact>
        This server requires an API key. The key stays in this browser tab only.
      </Callout>
      <Input
        type="password"
        aria-label="API key"
        autoFocus
        value={key}
        onChange={(event) => setKey(event.target.value)}
      />
      <Button type="submit" disabled={!key.trim()}>
        Use key
      </Button>
    </form>
  );
}
