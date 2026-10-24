// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Callout } from './Callout';

/** Compact error notice for failed actions (a danger Callout, role=alert). */
export function InlineError({ message }: { message: string | null }) {
  return message ? (
    <Callout kind="danger" compact>
      {message}
    </Callout>
  ) : null;
}
