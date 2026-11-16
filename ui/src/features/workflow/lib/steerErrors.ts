// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { ApiError } from '@/lib/apiClient';

/** What went wrong when a message, an interrupt or an end was refused, in words. */
export function steerErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.status) {
      case 403:
        return 'You may not steer this agent: the node names who may.';
      case 404:
        return 'This conversation is gone or not yours to see.';
      case 409:
        return /not.live|ended|running/i.test(error.message)
          ? 'The agent is not running any more.'
          : 'This agent cannot do that.';
      case 413:
        return 'The message is too long.';
      case 429:
        return 'Too many messages to this agent: wait a moment.';
      default:
        return error.message;
    }
  }
  return error instanceof Error ? error.message : 'The request failed.';
}

/** Whether the person may never steer this agent, as far as the server has told us. */
export const isForbidden = (error: unknown): boolean =>
  error instanceof ApiError && error.status === 403;
