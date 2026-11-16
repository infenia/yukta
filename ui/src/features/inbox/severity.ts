// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { NotificationSeverity } from './api';

/** Colours of a severity: the dot of an item and the badge of the bell. */
export const SEVERITY_DOT: Record<NotificationSeverity, string> = {
  INFO: 'bg-primary',
  WARN: 'bg-warning',
  ERROR: 'bg-destructive',
};

export const SEVERITY_LABEL: Record<NotificationSeverity, string> = {
  INFO: 'Info',
  WARN: 'Warning',
  ERROR: 'Error',
};

/** Text only: a title or a body from a workflow is never shown as HTML or with control characters. */
export const plain = (text: string) =>
  // eslint-disable-next-line no-control-regex
  text.replace(/[\x00-\x1f\x7f]/g, ' ');
