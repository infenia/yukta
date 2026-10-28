// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  describe,
  it,
  expect,
  beforeAll,
  afterAll,
  afterEach,
  vi,
} from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { exportSessionConfig } from '../exportSession';

const config = {
  sessionId: 's 1',
  name: 'N',
  workflows: { w: { workflowId: 'w' } },
};
let fail = false;

const server = setupServer(
  http.get('/api/sessions/:sid/config', () =>
    fail
      ? HttpResponse.json({ message: 'gone' }, { status: 404 })
      : HttpResponse.json({
          timestamp: 't',
          status: 200,
          message: 'ok',
          data: config,
          path: '/',
        })
  )
);

beforeAll(() => server.listen());
afterEach(() => {
  fail = false;
  vi.restoreAllMocks();
});
afterAll(() => server.close());

describe('exportSessionConfig', () => {
  it('downloads the fetched config as pretty-printed <sessionId>.json', async () => {
    let blob: Blob | undefined;
    URL.createObjectURL = vi.fn((b: Blob) => {
      blob = b;
      return 'blob:x';
    });
    URL.revokeObjectURL = vi.fn();
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(function (this: HTMLAnchorElement) {
        expect(this.download).toBe('s 1.json');
        expect(this.href).toBe('blob:x');
      });

    await exportSessionConfig('s 1');

    expect(click).toHaveBeenCalledOnce();
    expect(await blob?.text()).toBe(`${JSON.stringify(config, null, 2)}\n`);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:x');
  });

  it('rejects when the config cannot be fetched', async () => {
    fail = true;
    await expect(exportSessionConfig('s1')).rejects.toThrow('gone');
  });
});
