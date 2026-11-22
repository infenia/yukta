// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { startValue, type StartInput } from '../lib/startValues';

/** The modifier key shown with Enter: the Command symbol on Apple devices, Ctrl elsewhere. */
export function modifierLabel(userAgent: string): string {
  return /Mac|iPhone|iPad/.test(userAgent) ? '⌘' : 'Ctrl';
}

/** Parses the payload editor text; empty means no payload. */
export function parsePayload(text: string): Record<string, unknown> {
  if (!text.trim()) return {};
  const value: unknown = JSON.parse(text);
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Payload must be a JSON object');
  }
  return value as Record<string, unknown>;
}

/** Form values (by dotted path) as the nested start payload; empty fields are left out. */
export function buildPayload(
  inputs: StartInput[],
  values: Record<string, string>
): Record<string, unknown> {
  const payload: Record<string, unknown> = {};
  for (const { path, type } of inputs) {
    const raw = values[path] ?? '';
    if (raw === '') continue;
    const value =
      type === 'number'
        ? Number(raw)
        : type === 'toggle'
          ? raw === 'true'
          : raw;
    setPath(payload, path, value);
  }
  return payload;
}

/** Sets a dotted path in a nested object, creating the objects on the way. */
function setPath(
  target: Record<string, unknown>,
  path: string,
  value: unknown
) {
  const keys = path.split('.');
  const last = keys.pop() as string;
  let node = target;
  for (const key of keys) {
    node = (node[key] ??= {}) as Record<string, unknown>;
  }
  node[last] = value;
}

/** Form values from a payload, the inverse of buildPayload for the known inputs. */
export function payloadValues(
  inputs: StartInput[],
  payload: Record<string, unknown>
): Record<string, string> {
  const values: Record<string, string> = {};
  for (const { path } of inputs) {
    const value = startValue(payload, path);
    if (value !== undefined && value !== null) values[path] = String(value);
  }
  return values;
}

/** The JSON to start from: every input as a key, the values set so far, a typed blank for the rest. */
export function payloadSkeleton(
  inputs: StartInput[],
  values: Record<string, string>
): string {
  const blank = { text: '', number: '0', toggle: 'false' };
  const filled: Record<string, string> = {};
  for (const { path, type } of inputs) {
    filled[path] = values[path] || blank[type];
  }
  // A blank text is kept (buildPayload drops empty strings), so the key still shows
  const payload = buildPayload(inputs, filled);
  for (const { path, type } of inputs) {
    if (type === 'text' && !values[path]) setPath(payload, path, '');
  }
  return JSON.stringify(payload, null, 2);
}
