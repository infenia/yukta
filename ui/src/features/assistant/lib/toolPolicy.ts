// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ToolSpec } from '../types';

const READ_NAME = /^(?:(?:list|get|diff|check|read)_|whoami$)/;

/**
 * Whether a tool only reads: the tool says so (`readOnlyHint`), else its name does. Anything
 * else may change something, so the person approves it first.
 */
export function isRead(tool: ToolSpec): boolean {
  return tool.annotations?.readOnlyHint ?? READ_NAME.test(tool.name);
}
