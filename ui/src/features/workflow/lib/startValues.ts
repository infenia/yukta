// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** `${start.a.b}` or `${start.a.b:int}`: the path, without the optional type. */
const START_REF = /\$\{start\.([^}:]+)(?::[a-z]+)?\}/g;

const START_TYPED = /\$\{start\.([^}:]+)(?::([a-z]+))?\}/g;

/** A value the run needs: its dotted path in the start payload and its type (`text` without one). */
export interface StartInput {
  path: string;
  type: 'text' | 'number' | 'toggle';
  /** The workflow copes without it (a null check, or a default in the expression). */
  optional?: boolean;
  /** What it falls back to when left empty, when the expression says. */
  fallback?: string;
}

const NUMBER = new Set(['int', 'long', 'double', 'float', 'number']);
const TOGGLE = new Set(['bool', 'boolean']);

interface InputNode {
  nodeId?: string;
  type: string;
  config?: unknown;
}
interface InputEdge {
  source: string;
  target: string;
}

/** Triggers that hand the caller's payload on as the message the next nodes read as `payload`. */
const PAYLOAD_TRIGGERS = new Set(['API_TRIGGER', 'WEBHOOK_TRIGGER']);

const esc = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** `payload['text']`, `payload["text"]` (as JSON-escaped) and `payload.text`: the key, once each. */
const PAYLOAD_REF = /payload(?:\[\\?["']([\w-]+)\\?["']\]|\.([A-Za-z_]\w*))/g;

/** The keys the nodes right after a payload trigger read from `payload`, with how they cope without. */
function payloadInputs(nodes: InputNode[], edges: InputEdge[]): StartInput[] {
  const triggers = new Set(
    nodes.filter((n) => PAYLOAD_TRIGGERS.has(n.type)).map((n) => n.nodeId)
  );
  const next = new Set(
    edges.filter((e) => triggers.has(e.source)).map((e) => e.target)
  );
  const found = new Map<string, StartInput>();
  for (const node of nodes.filter((n) => next.has(n.nodeId ?? ''))) {
    const text = JSON.stringify(node.config) ?? '';
    for (const match of text.matchAll(PAYLOAD_REF)) {
      const key = match[1] ?? match[2];
      if (found.has(key)) continue;
      const ref = `payload(?:\\[\\\\?["']${esc(key)}\\\\?["']\\]|\\.${esc(key)})`;
      const optional = new RegExp(`${ref}\\s*[!=]=\\s*null`).test(text);
      const fallback = new RegExp(`${ref}\\s*:\\s*'([^']*)'`).exec(text)?.[1];
      found.set(key, { path: key, type: 'text', optional, fallback });
    }
  }
  return [...found.values()];
}

/**
 * What the run is started with, found in the nodes: each `${start.…}` (typed by its suffix) and
 * each `payload` key read right after an API or webhook trigger. Once each, in order.
 */
export function startInputs(
  nodes: InputNode[],
  edges: InputEdge[] = []
): StartInput[] {
  const found = new Map<string, StartInput>();
  for (const node of nodes) {
    const text = JSON.stringify(node.config) ?? '';
    for (const [, path, type] of text.matchAll(START_TYPED)) {
      if (!found.has(path)) {
        found.set(path, {
          path,
          type: NUMBER.has(type)
            ? 'number'
            : TOGGLE.has(type)
              ? 'toggle'
              : 'text',
        });
      }
    }
  }
  for (const input of payloadInputs(nodes, edges)) {
    if (!found.has(input.path)) found.set(input.path, input);
  }
  return [...found.values()];
}

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
