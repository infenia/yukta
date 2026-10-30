// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { createContext } from 'react';

/** Where a JsonField reports whether its text is invalid, so the page can block Save and Validate. */
export const JsonValidityContext = createContext<
  (key: string, invalid: boolean) => void
>(() => {});
