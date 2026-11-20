// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Table } from './packaging';

/** Setting up API keys and limiting what workflows may do. Checked against docs/guides/operate/secure-the-server.mdx. */

export const SECURITY_INTRO =
  'A fresh server listens on `127.0.0.1` only and takes no key: every caller is `ADMIN`. Before you open it to other people, scripts or agents, give each one a named key with a role. Whoever can define a workflow decides what its nodes run, so give `ADMIN` only to people you would let run code on the server.';

export const ROLES: Table = {
  title: 'Roles',
  intro:
    'Each key has one role. `VIEWER` and `OPERATOR` also list the sessions they may reach.',
  columns: ['Role', 'May', 'Sessions'],
  rows: [
    ['VIEWER', 'Read sessions, runs and logs.', 'Required. `*` for all.'],
    [
      'OPERATOR',
      'Everything a viewer may, and start, stop and steer runs and answer input requests.',
      'Required. `*` for all.',
    ],
    [
      'ADMIN',
      'Everything: define sessions and workflows, change scheduler limits, read `/actuator/**`.',
      'All.',
    ],
  ],
};

export interface Step {
  title: string;
  text: string;
  code: string;
  label: string;
}

export const KEY_STEPS: Step[] = [
  {
    title: '1. Generate a key and keep only its digest',
    text: 'The server can hold the SHA-256 digest (`sha256:` and 64 hex digits) instead of the key. Give the caller `$key`, and the server the digest. Keys under 32 characters get a warning.',
    code: `key="$(openssl rand -hex 32)"
printf '%s' "$key" | sha256sum`,
    label: 'generate a key',
  },
  {
    title: '2. Give each caller a named key',
    text: 'Keys are a list in `yukta.api-keys`; the name appears in the UI and the audit trail. A `VIEWER` or `OPERATOR` key with no `sessions` is refused at start, and so is the same secret in two entries.',
    code: `yukta.api-keys[0].name=admin
yukta.api-keys[0].key=sha256:<digest of the admin key>
yukta.api-keys[0].role=ADMIN
yukta.api-keys[1].name=ci
yukta.api-keys[1].key=sha256:<digest of the CI key>
yukta.api-keys[1].role=OPERATOR
yukta.api-keys[1].sessions[0]=build`,
    label: 'application.yaml properties',
  },
  {
    title: '3. Or set them as environment variables',
    text: 'In a container or a `systemd` file the same list is `YUKTA_API_KEYS_<n>_NAME`, `_KEY`, `_ROLE` and `_SESSIONS_<m>`.',
    code: `docker run --rm -p 9858:9858 \\
  -e YUKTA_API_KEYS_0_NAME=admin \\
  -e YUKTA_API_KEYS_0_KEY=sha256:<digest> \\
  -e YUKTA_API_KEYS_0_ROLE=ADMIN \\
  ghcr.io/infenia/yukta`,
    label: 'docker run with an API key',
  },
  {
    title: '4. Rotate and expire',
    text: 'Several keys are valid at once. To rotate with no downtime, add the new key, move the clients over, then remove the old entry and restart. A short-lived key can expire; after that instant it is refused with `401`. Revoking a key needs a settings change and a restart.',
    code: 'yukta.api-keys[3].expires=2026-12-31T00:00:00Z',
    label: 'key expiry',
  },
  {
    title: '5. Send the key',
    text: 'Clients send the plain key as a bearer token. `401` is no key or a wrong key, `403` is a role or session the key may not use, `429` is too many calls.',
    code: 'curl -H "Authorization: Bearer $key" http://127.0.0.1:9858/api/me',
    label: 'call the API with a key',
  },
];

export const LIMIT_STEPS: Step[] = [
  {
    title: 'Allow only the commands you mean',
    text: '`PROCESS_EXECUTOR` runs nothing until you list executables. Never allow a shell or an interpreter such as `python3`: the list names an executable, not what it does with its arguments.',
    code: `yukta.process.allowed-commands=git,npm,mvn
yukta.process.working-dir-root=/srv/work`,
    label: 'allowed commands',
  },
  {
    title: 'Limit the hosts a workflow may call',
    text: 'By default HTTP, MCP and notify nodes may call any host except link-local and cloud metadata addresses. `*.example.com` matches subdomains, not `example.com`.',
    code: 'yukta.egress.allowed-hosts=api.example.com,hooks.slack.com',
    label: 'allowed hosts',
  },
  {
    title: 'Keep secrets out of workflow definitions',
    text: 'A definition with a literal secret is refused. Write `decrypted:slack.token` in the node and set `YUKTA_SECRET_SLACK_TOKEN` on the server (upper-case the name, other characters become `_`).',
    code: 'export YUKTA_SECRET_SLACK_TOKEN=xoxb-...',
    label: 'a secret on the server',
  },
];

export const SECURITY_NOTES = [
  'With no key set, the server accepts only the hosts `localhost`, `127.0.0.1` and `[::1]` and refuses a changing request with a foreign `Origin`. Behind a proxy list yours in `yukta.allowed-hosts` and `yukta.allowed-origins`; that check is off once any key is set, so prefer a key.',
  'Each key may make 1200 `/api` calls per minute, and wrong keys count against the caller address (20 per minute). Both are JVM `-D` properties; `0` turns a limit off.',
  'Every control command (pause, stop, answer, change limits) is recorded with the key name, on the Control page, Audit tab.',
  'The Options tab lists every security setting with its default; search for `api-keys`, `egress` or `process`.',
];
