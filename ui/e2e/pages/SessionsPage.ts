// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Locator, Page } from '@playwright/test';

/** The session list at /sessions. */
export class SessionsPage {
  readonly table: Locator;
  readonly rows: Locator;
  readonly tagFilter: Locator;
  readonly nextPage: Locator;
  readonly exportJson: Locator;

  constructor(private readonly page: Page) {
    this.table = page.locator('table');
    this.rows = page.locator('tbody tr');
    this.tagFilter = page.getByRole('button', { name: /tag filter/i });
    this.nextPage = page.getByRole('button', { name: 'Next page' });
    this.exportJson = page.getByRole('button', { name: 'Export JSON' });
  }

  async goto(): Promise<void> {
    await this.page.goto('/sessions');
  }

  /** The row (or any text) that names a session. */
  session(name: string): Locator {
    return this.page.getByText(name);
  }
}
