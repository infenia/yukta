// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { expect, test } from '@playwright/test';

test('canary fails on purpose', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByText('this text does not exist')).toBeVisible({ timeout: 2000 });
});
