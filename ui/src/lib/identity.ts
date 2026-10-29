// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useSyncExternalStore } from 'react';

export type Role = 'VIEWER' | 'OPERATOR' | 'ADMIN';

/** What the caller may do, as GET /api/me reports it. */
export interface Permissions {
  startRuns: boolean;
  controlRuns: boolean;
  editSessions: boolean;
  deleteSessions: boolean;
  changeLimits: boolean;
  sendCommands: boolean;
}

export type Permission = keyof Permissions;

export interface Me {
  authenticated: boolean;
  keyName: string | null;
  role: Role;
  sessions: string[] | null;
  authEnabled: boolean;
  permissions: Permissions;
}

/** The role a capability needs, for the "Needs OPERATOR" tooltip. */
export const REQUIRED_ROLE: Record<Permission, Role> = {
  startRuns: 'OPERATOR',
  controlRuns: 'OPERATOR',
  editSessions: 'ADMIN',
  deleteSessions: 'ADMIN',
  changeLimits: 'ADMIN',
  sendCommands: 'ADMIN',
};

/*
 * The caller's identity lives in a small store, not in a query, so any component can read it
 * without a provider. Until it is known (or when the server does not answer /api/me) nothing is
 * gated: the server still refuses what is not allowed.
 */
let current: Me | null = null;
const listeners = new Set<() => void>();

export function setMe(me: Me | null): void {
  current = me;
  listeners.forEach((listener) => listener());
}

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

/** The caller's identity, or null while it is unknown. */
export const useMe = (): Me | null =>
  useSyncExternalStore(subscribe, () => current);

/** The role of the key in use, for error messages; null when unknown. */
export const knownRole = (): Role | null => current?.role ?? null;

