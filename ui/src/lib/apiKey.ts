// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useSyncExternalStore } from 'react';

/** Event fired when the server answers 401, so the key gate can open. */
export const API_KEY_REQUIRED_EVENT = 'yukta:api-key-required';

const STORAGE_KEY = 'yukta.apiKey';

/** The API key of this browser tab, or null. Kept in sessionStorage so it ends with the tab. */
export function getApiKey(): string | null {
  try {
    return sessionStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

export function setApiKey(key: string | null): void {
  try {
    if (key) {
      sessionStorage.setItem(STORAGE_KEY, key);
    } else {
      sessionStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    // Storage blocked: the key lasts only until the next reload.
  }
}

/*
 * Once a key is required the flag stays set until a key is accepted (the page reloads) or the
 * gate is dismissed, so the app shell stays unmounted and stops polling the server.
 */
let required = false;
let notice: string | null = null;

/** Opens the key gate; `why` is shown on it when the server refused (null when asked for a change). */
export function requireApiKey(why: string | null = null): void {
  required = true;
  notice = why;
  window.dispatchEvent(new Event(API_KEY_REQUIRED_EVENT));
}

/** Opens the gate for a 401: says so when a key was sent, as a first visit needs no warning. */
export function apiKeyRefused(): void {
  requireApiKey(
    getApiKey() ? 'The key in use was not accepted. Enter another one.' : null
  );
}

/** Opens the gate for the server's lockout of this address after too many wrong keys. */
export function apiKeyLockedOut(retryAfter: string | null): void {
  requireApiKey(lockoutMessage(retryAfter));
}

export const lockoutMessage = (retryAfter: string | null) =>
  `Too many failed attempts from this address. Try again in ${retryAfter ?? '60'} seconds.`;

/** Why the gate is open, when the server refused; null otherwise. */
export const getApiKeyNotice = (): string | null => notice;

/** Closes the gate without a new key (offered only while the current key is accepted). */
export function dismissApiKey(): void {
  required = false;
  notice = null;
  window.dispatchEvent(new Event(API_KEY_REQUIRED_EVENT));
}

const subscribe = (listener: () => void) => {
  window.addEventListener(API_KEY_REQUIRED_EVENT, listener);
  return () => window.removeEventListener(API_KEY_REQUIRED_EVENT, listener);
};

/** True while the server wants a (different) key. */
export const useApiKeyRequired = (): boolean =>
  useSyncExternalStore(subscribe, () => required);

/** Headers that carry the key, empty when none is set. */
export function authHeaders(): Record<string, string> {
  const key = getApiKey();
  return key ? { Authorization: `Bearer ${key}` } : {};
}
