// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** Event fired when the server answers 401/403, so the key prompt can open. */
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

export function requireApiKey(): void {
  window.dispatchEvent(new Event(API_KEY_REQUIRED_EVENT));
}

/** Headers that carry the key, empty when none is set. */
export function authHeaders(): Record<string, string> {
  const key = getApiKey();
  return key ? { Authorization: `Bearer ${key}` } : {};
}
