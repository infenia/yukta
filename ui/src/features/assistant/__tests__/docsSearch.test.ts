// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  indexDocs,
  resetDocs,
  SEARCH_DOCS,
  searchDocs,
} from '../lib/docsSearch';
import { isRead } from '../lib/toolPolicy';

const p = (t: string, h: string, x: string) => ({
  t,
  h,
  x,
  u: `https://d/${t}`,
});
const docs = [
  p('Install', 'Docker', 'Run the server with docker compose on port 9858.'),
  p(
    'Workflows',
    'Nodes',
    'A workflow is a graph of nodes: triggers, processors and terminals.'
  ),
  p(
    'Workflows',
    'Retries',
    'A node retries with backoff when its engine policy says so, and a retry counts.'
  ),
  p(
    'Config',
    'API keys',
    'Set yukta.api-keys to require a key on every request.'
  ),
];

describe('indexDocs', () => {
  const search = indexDocs(docs);

  it('ranks the passage that talks about the question first', () => {
    expect(search('how do I run it with docker')[0].t).toBe('Install');
    expect(search('api key')[0].t).toBe('Config');
  });

  it('counts the heading and a rarer word more, and a repeated word less', () => {
    expect(search('retries backoff')[0].h).toBe('Retries');
    expect(search('node nodes')[0].h).toBe('Nodes');
  });

  it('gives at most the limit, best first, and nothing for a question it has no words for', () => {
    expect(search('workflow node', 1)).toHaveLength(1);
    expect(search('workflow node').length).toBeLessThanOrEqual(4);
    expect(search('zebra')).toEqual([]);
    expect(search('')).toEqual([]);
  });

  it('copes with no documentation at all', () => {
    expect(indexDocs([])('anything')).toEqual([]);
  });
});

describe('searchDocs', () => {
  beforeEach(() => resetDocs());
  afterEach(() => vi.unstubAllGlobals());

  it('is a read-only tool', () => {
    expect(isRead(SEARCH_DOCS)).toBe(true);
  });

  it('fetches the index once and answers with passages, their titles and links', async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json(docs));
    vi.stubGlobal('fetch', fetchMock);

    const first = await searchDocs({ query: 'docker compose' });
    await searchDocs({ query: 'retries' });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe('/assistant-docs.json');
    expect(first).toContain(
      '## Install — Docker\nhttps://d/Install\n\nRun the server'
    );
  });

  it('leaves a missing heading out of the title and joins passages with a rule', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          Response.json([
            p('Intro', '', 'yukta is a server'),
            p('Other', 'Part', 'yukta is a tool'),
          ])
        )
    );

    const out = await searchDocs({ query: 'yukta' });

    expect(out).toContain('## Intro\n');
    expect(out).toContain('\n\n---\n\n## Other — Part\n');
  });

  it('says so when nothing matches, also for a question without a query', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(docs)));

    expect(await searchDocs({ query: 'zebra' })).toBe(
      'No documentation matched.'
    );
    expect(await searchDocs({})).toBe('No documentation matched.');
  });

  it('throws when the index is missing, and fetches it again next time', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response('', { status: 404 }))
      .mockResolvedValueOnce(Response.json(docs));
    vi.stubGlobal('fetch', fetchMock);

    await expect(searchDocs({ query: 'docker' })).rejects.toThrow(
      'not available'
    );
    expect(await searchDocs({ query: 'docker' })).toContain('Install');
  });
});
