// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** The run ids a 409 names: "2 run(s) are still active: e1, e2. Stop them first…". */
export function activeRunIds(message: string): string[] {
  const match = /still active: ([^.]*)\./.exec(message);
  return match ? match[1].split(', ').filter(Boolean) : [];
}
