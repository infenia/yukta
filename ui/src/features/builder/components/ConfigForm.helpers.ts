// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { createContext } from 'react';
import type { ConfigField } from '@/features/plugins/types';

/** Where a JsonField reports whether its text is invalid, so the page can block Save and Validate. */
export const JsonValidityContext = createContext<
  (key: string, invalid: boolean) => void
>(() => {});

/** "elementTimeoutMs" -> "Element timeout (ms)", "HTTP_METHOD" -> "Http method". */
export function humanize(name: string): string {
  const words = name
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .trim()
    .toLowerCase()
    .replace(/ ms$/, ' (ms)');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** What a field with a default shows under it; null when there is none to show. */
export function defaultHint(field: ConfigField): string | null {
  const value = field.defaultValue;
  if (value === null || value === undefined || value === '') return null;
  return `Default: ${typeof value === 'string' ? value : JSON.stringify(value)}`;
}

/** A required field with nothing set and no default to fall back on. */
export function isMissing(field: ConfigField, value: unknown): boolean {
  if (!field.required || (field.defaultValue ?? null) !== null) return false;
  return (
    value === undefined ||
    value === null ||
    value === '' ||
    (Array.isArray(value) && value.length === 0)
  );
}
