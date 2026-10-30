// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';

test.describe('New session', () => {
  test("says when the server can't find the project path", async ({ page }) => {
    let asked: unknown;
    await page.route('**/api/sessions/validate/project-path', (route) => {
      asked = route.request().postDataJSON();
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          timestamp: '2026-07-26T14:23:23',
          status: 200,
          message: 'OK',
          path: '/project-path',
          data: { exists: false, directory: false },
        }),
      });
    });
    await page.goto('/sessions/new');

    const path = page.getByLabel('Project path');
    await path.fill('/srv/repos/payments');
    await path.blur();

    await expect(
      page.getByText("The server can't find /srv/repos/payments.")
    ).toBeVisible();
    await expect(path).toHaveAttribute('aria-invalid', 'true');
    expect(asked).toEqual({ projectPath: '/srv/repos/payments' });
  });
});
