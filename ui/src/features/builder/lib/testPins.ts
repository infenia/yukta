// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** The last sample fed to a node and the last output it gave, kept for this tab's session. */
export interface TestPin {
  sample: string;
  output?: unknown[];
}

const key = (sessionId: string, workflowId: string, nodeId: string) =>
  `yukta.testpin/${sessionId}/${workflowId}/${nodeId}`;

export function readPin(
  sessionId: string,
  workflowId: string,
  nodeId: string
): TestPin | null {
  try {
    const raw = sessionStorage.getItem(key(sessionId, workflowId, nodeId));
    return raw ? (JSON.parse(raw) as TestPin) : null;
  } catch {
    return null;
  }
}

export function writePin(
  sessionId: string,
  workflowId: string,
  nodeId: string,
  pin: TestPin
): void {
  try {
    sessionStorage.setItem(
      key(sessionId, workflowId, nodeId),
      JSON.stringify(pin)
    );
  } catch {
    // storage is only a convenience
  }
}

/** Parses the sample text; blank is an empty object, anything but an object is an error (null). */
export function parseSample(text: string): Record<string, unknown> | null {
  if (text.trim() === '') return {};
  try {
    const value: unknown = JSON.parse(text);
    return value !== null && typeof value === 'object' && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

/** The paths of an output's leaves ("a.b"); arrays and values are leaves, depth is capped. */
export function payloadPaths(value: unknown, prefix = '', depth = 0): string[] {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return prefix ? [prefix] : [];
  }
  if (depth >= 4) return [prefix];
  return Object.entries(value).flatMap(([key, child]) =>
    payloadPaths(child, prefix ? `${prefix}.${key}` : key, depth + 1)
  );
}

/** What an earlier step gave, by its pinned test output, for the expression picker. */
export interface StepReference {
  nodeId: string;
  paths: string[];
}
