// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { sseData } from '../lib/sse';
import { collect, streamResponse } from '../testFixtures';

const read = (chunks: string[]) =>
  collect(sseData(streamResponse(chunks).body as ReadableStream<Uint8Array>));

describe('sseData', () => {
  it('yields the data of each event, however the chunks cut it', async () => {
    expect(await read(['data: a\n\nda', 'ta: b\n', '\ndata: c\n\n'])).toEqual([
      'a',
      'b',
      'c',
    ]);
  });

  it('joins the data lines of one event and reads CRLF line ends', async () => {
    expect(await read(['data: one\r\ndata: two\r\n\r\n'])).toEqual([
      'one\ntwo',
    ]);
  });

  it('skips events without data and fields other than data', async () => {
    expect(
      await read([': keep-alive\n\nevent: ping\nid: 1\ndata:x\n\n'])
    ).toEqual(['x']);
  });

  it('yields an event the stream ended without a blank line after', async () => {
    expect(await read(['data: last'])).toEqual(['last']);
  });

  it('yields nothing for an empty stream', async () => {
    expect(await read([])).toEqual([]);
  });
});
