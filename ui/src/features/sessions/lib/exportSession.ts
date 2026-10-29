// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { getSessionDraft } from '@/features/builder/api';

/** Shown on every Export control: the server redacts secrets before sending the config. */
export const EXPORT_HINT = 'Secret values are not exported.';

/** Saves the session's full config as `<sessionId>.json` (2-space JSON, the format `session apply` reads). */
export async function exportSessionConfig(sessionId: string): Promise<void> {
  const config = await getSessionDraft(sessionId);
  const blob = new Blob([`${JSON.stringify(config, null, 2)}\n`], {
    type: 'application/json',
  });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `${sessionId}.json`;
  link.click();
  URL.revokeObjectURL(url);
}
