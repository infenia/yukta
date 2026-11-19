// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** `${start.a.b}` or `${start.a.b:int}`: the path, without the optional type. */
const START_REF = /\$\{start\.([^}:]+)(?::[a-z]+)?\}/g;

/** The `start.…` paths a node's config reads, each once, in the order they appear. */
export function startPaths(config: unknown): string[] {
  const text = JSON.stringify(config) ?? '';
  return [...new Set([...text.matchAll(START_REF)].map((match) => match[1]))];
}

/** The value at a dotted path of the start payload, or undefined when it is not there. */
export function startValue(
  payload: Record<string, unknown>,
  path: string
): unknown {
  return path
    .split('.')
    .reduce<unknown>(
      (value, key) =>
        value !== null && typeof value === 'object'
          ? (value as Record<string, unknown>)[key]
          : undefined,
      payload
    );
}
