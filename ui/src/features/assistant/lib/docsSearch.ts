// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Passage } from '../../../../scripts/docsIndex.mjs';
import type { ToolSpec } from '../types';

/** Okapi BM25: how much a repeated word counts, and how much a long passage is discounted. */
const K1 = 1.5;
const B = 0.75;

const words = (text: string): string[] =>
  text.toLowerCase().match(/[a-z0-9_]+/g) ?? [];

/**
 * A ranking over the passages: the best `limit` for a question by BM25, the page title and the
 * heading counting double. Built once, then asked many times.
 */
export function indexDocs(passages: Passage[]) {
  const docs = passages.map((p) => words(`${p.t} ${p.h} ${p.h} ${p.x}`));
  const average = docs.reduce((sum, d) => sum + d.length, 0) / docs.length;
  const frequency = new Map<string, number>();
  for (const doc of docs) {
    for (const word of new Set(doc)) {
      frequency.set(word, (frequency.get(word) ?? 0) + 1);
    }
  }
  const counts = docs.map((doc) => {
    const count = new Map<string, number>();
    for (const word of doc) count.set(word, (count.get(word) ?? 0) + 1);
    return count;
  });
  return (query: string, limit = 4): Passage[] => {
    const terms = [...new Set(words(query))];
    return counts
      .map((count, index) => {
        let score = 0;
        for (const term of terms) {
          const f = count.get(term);
          if (!f) continue;
          const n = frequency.get(term) as number;
          score +=
            (Math.log(1 + (docs.length - n + 0.5) / (n + 0.5)) *
              (f * (K1 + 1))) /
            (f + K1 * (1 - B + (B * docs[index].length) / average));
        }
        return { index, score };
      })
      .filter(({ score }) => score > 0)
      .sort((a, b) => b.score - a.score)
      .slice(0, limit)
      .map(({ index }) => passages[index]);
  };
}

let loaded: Promise<ReturnType<typeof indexDocs>> | null = null;

/** The index of the documentation the build emitted, fetched once. */
function load() {
  loaded ??= fetch('/assistant-docs.json')
    .then((res) => {
      if (!res.ok) throw new Error('The documentation index is not available.');
      return res.json() as Promise<Passage[]>;
    })
    .then(indexDocs)
    .catch((error) => {
      loaded = null;
      throw error;
    });
  return loaded;
}

/** Forgets the fetched index, so the next search fetches it again. */
export function resetDocs(): void {
  loaded = null;
}

/** The tool the model calls to look something up in the documentation. */
export const SEARCH_DOCS: ToolSpec = {
  name: 'search_docs',
  description:
    'Searches the Yukta documentation (guides, concepts, reference) and returns the best matching passages with their page links. Use it for questions about how Yukta works or how to do something.',
  inputSchema: {
    type: 'object',
    properties: { query: { type: 'string', description: 'What to look for' } },
    required: ['query'],
  },
  annotations: { readOnlyHint: true },
};

/** Runs {@link SEARCH_DOCS}: the passages as Markdown, each under its title and link. */
export async function searchDocs(
  args: Record<string, unknown>
): Promise<string> {
  const hits = (await load())(String(args.query ?? ''));
  return hits.length > 0
    ? hits
        .map((p) => `## ${p.t}${p.h ? ` — ${p.h}` : ''}\n${p.u}\n\n${p.x}`)
        .join('\n\n---\n\n')
    : 'No documentation matched.';
}
