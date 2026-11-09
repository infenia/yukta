// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Callout } from '@/components/common/Callout';
import { CodeBlock } from '@/components/common/CodeBlock';
import { RequirePermission } from '@/components/common/RequirePermission';
import { StateMessage } from '@/components/common/StateMessage';
import { Button } from '@/components/ui/button';
import { inputFieldClassName } from '@/components/ui/native-select';
import { useNow } from '@/features/control/dashboard/useNow';
import { formatDateTime } from '@/features/workflow/lib/timeFormat';
import { ApiError } from '@/lib/apiClient';
import {
  getCredential,
  issueCredential,
  revokeCredential,
} from '../api/supervisors';

const Icon = ({ name }: { name: string }) => (
  <span aria-hidden="true" className="material-symbols-outlined text-sm">
    {name}
  </span>
);

/** How an external system and Yukta talk: signals out, signals in, decisions in. */
export function ExternalFlow({ url }: { url: unknown }) {
  return (
    <ul className="border-border bg-card text-body-md shadow-card flex flex-col gap-1 rounded-xl border px-3 py-2">
      <li className="flex items-center gap-2">
        <Icon name="arrow_forward" />
        <span>
          <b>Out:</b>{' '}
          {url
            ? 'Yukta posts each signal to the url below'
            : 'no url: nothing is posted, the system reads the signals'}
        </span>
      </li>
      <li className="flex items-center gap-2">
        <Icon name="history" />
        <span>
          <b>In:</b> the same signals at <code>/api/agent/v1/signals</code>{' '}
          (missed posts are read here by cursor)
        </span>
      </li>
      <li className="flex items-center gap-2">
        <Icon name="hub" />
        <span>
          <b>Decisions:</b> commands, review and delegation answers at{' '}
          <code>/api/agent/v1</code>
        </span>
      </li>
    </ul>
  );
}

const when = (at: string | null, none: string) =>
  at ? formatDateTime(at) : none;

/**
 * The secret the external system uses on /api/agent/v1. A new or rotated secret is shown once and
 * kept only in this component's state: not in the query cache or storage, and gone after Done.
 */
export function CredentialPanel({ id }: { id: string }) {
  const client = useQueryClient();
  const now = useNow();
  const key = ['supervisors', 'credential', id];
  const credential = useQuery({
    queryKey: key,
    queryFn: () => getCredential(id),
    retry: false,
  });
  const [token, setToken] = useState<string | null>(null);
  const [ttl, setTtl] = useState('');
  const [addresses, setAddresses] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const act = async (work: () => Promise<unknown>) => {
    setBusy(true);
    setFailure(null);
    try {
      await work();
    } catch (error) {
      setFailure((error as Error).message);
    } finally {
      setBusy(false);
      setConfirming(false);
      await client.invalidateQueries({ queryKey: key });
    }
  };
  const issue = () =>
    act(async () => {
      const issued = await issueCredential(
        id,
        Number(ttl) || null,
        addresses.split(/[\s,]+/).filter(Boolean)
      );
      setToken(issued.token);
    });
  const revoke = () => act(() => revokeCredential(id));

  if (credential.isPending) {
    return <StateMessage tone="loading" title="Loading the credential…" />;
  }
  const missing =
    credential.error instanceof ApiError && credential.error.status === 404;
  if (credential.error && !missing) {
    return (
      <StateMessage tone="error" title="Failed to load the credential.">
        {credential.error.message}
      </StateMessage>
    );
  }
  // After a revoke the refetch fails with 404 but the query keeps the old data.
  const meta = missing ? undefined : credential.data;
  const expired = !!meta?.expiresAt && new Date(meta.expiresAt).getTime() < now;
  return (
    <section
      aria-label="Credential"
      className="border-border bg-card shadow-card flex flex-col gap-2 rounded-xl border p-3"
    >
      <h3 className="text-label-md flex items-center gap-2">
        Credential
        {meta ? (
          <span className={expired ? 'text-error' : 'text-success'}>
            <Icon name={expired ? 'block' : 'check_circle'} />{' '}
            {expired ? 'Expired' : 'Active'}
          </span>
        ) : (
          <span className="text-muted-foreground">None</span>
        )}
      </h3>
      {meta ? (
        <dl className="text-body-md grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
          <dt className="text-muted-foreground">Created</dt>
          <dd>{when(meta.issuedAt, 'before the server started')}</dd>
          <dt className="text-muted-foreground">Expires</dt>
          <dd>{when(meta.expiresAt, 'never')}</dd>
          <dt className="text-muted-foreground">Last used</dt>
          <dd>
            {meta.lastUsedAt
              ? `${formatDateTime(meta.lastUsedAt)}${meta.lastUsedFrom ? ` from ${meta.lastUsedFrom}` : ''}`
              : 'not used'}{' '}
            (since the server started)
          </dd>
          <dt className="text-muted-foreground">Allowed addresses</dt>
          <dd>{meta.addresses.length ? meta.addresses.join(', ') : 'any'}</dd>
        </dl>
      ) : (
        <p className="text-body-md text-muted-foreground">
          The system cannot use /api/agent/v1 until a credential is created.
        </p>
      )}
      {token && (
        <Callout kind="warning" title="Copy it now: it is not shown again.">
          <div className="flex flex-col gap-2">
            <CodeBlock code={token} label="the credential" />
            <Button size="sm" variant="outline" onClick={() => setToken(null)}>
              Done
            </Button>
          </div>
        </Callout>
      )}
      <RequirePermission perm="editSessions">
        <div className="flex flex-wrap items-end gap-2">
          <label className="text-label-sm flex flex-col gap-1">
            Works for (hours, empty: no expiry)
            <input
              type="number"
              min={1}
              className={inputFieldClassName}
              value={ttl}
              onChange={(event) => setTtl(event.target.value)}
            />
          </label>
          <label className="text-label-sm flex flex-col gap-1">
            Allowed addresses (empty: any)
            <input
              className={inputFieldClassName}
              placeholder="10.0.4.0/24"
              value={addresses}
              onChange={(event) => setAddresses(event.target.value)}
            />
          </label>
          <Button size="sm" disabled={busy} onClick={issue}>
            <Icon name={meta ? 'restart_alt' : 'key'} />
            {meta ? 'Rotate' : 'Create'}
          </Button>
          {meta && !confirming && (
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() => setConfirming(true)}
            >
              <Icon name="close" />
              Revoke
            </Button>
          )}
        </div>
      </RequirePermission>
      {confirming && (
        <div
          role="alertdialog"
          aria-label="Revoke the credential?"
          className="flex flex-wrap items-center gap-2"
        >
          <span className="text-body-md">
            Revoke it? The system loses access at once.
          </span>
          <Button
            size="sm"
            variant="destructive"
            disabled={busy}
            onClick={revoke}
          >
            Revoke now
          </Button>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => setConfirming(false)}
          >
            Cancel
          </Button>
        </div>
      )}
      <p className="text-body-sm text-muted-foreground">
        Rotate makes a new one; the old stops at once.
      </p>
      {failure && <p className="text-body-sm text-error">{failure}</p>}
    </section>
  );
}
