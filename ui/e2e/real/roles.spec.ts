// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { RealApi, sessionJson, shell, unique } from './api';
import { expect, test } from './fixtures';

const viewer = process.env['YUKTA_VIEWER_KEY'];
const admin = process.env['YUKTA_API_KEY'];

test.describe('a VIEWER key', () => {
  test.skip(!viewer || !admin, 'YUKTA_API_KEY and YUKTA_VIEWER_KEY are needed');
  test.use({ extraHTTPHeaders: { Authorization: `Bearer ${viewer}` } });

  test('reads, but is refused every write', async ({
    page,
    request,
    playwright,
    baseURL,
  }) => {
    // the session is seeded with the ADMIN key; the page and \`request\` carry the VIEWER key
    const seed = await playwright.request.newContext({
      baseURL,
      extraHTTPHeaders: { Authorization: `Bearer ${admin}` },
    });
    const api = new RealApi(seed);
    try {
      const id = await api.applySession(
        sessionJson(unique('roles'), 'd', shell('p', 'echo hi'))
      );
      expect((await request.get(`/api/sessions/${id}`)).status()).toBe(200);
      expect((await request.delete(`/api/sessions/${id}`)).status()).toBe(403);

      await page.goto(`/sessions/${id}`);
      await expect(page.getByRole('heading').first()).toBeVisible();
      await expect(page.getByRole('menuitem', { name: 'Delete' })).toHaveCount(
        0
      );
    } finally {
      await api.cleanup();
      await seed.dispose();
    }
  });
});
