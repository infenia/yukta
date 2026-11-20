// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { createContext } from 'react';
import type { ConfigField } from '@/features/plugins/types';

/** Where a JsonField reports whether its text is invalid, so the page can block Save and Validate. */
export const JsonValidityContext = createContext<
  (key: string, invalid: boolean) => void
>(() => {});

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
