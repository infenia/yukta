// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Locator, Page } from '@playwright/test';

/** The runs of all sessions: /runs (live) and /history (finished, filterable). */
export class RunsPage {
  readonly status: Locator;
  readonly loadMore: Locator;
  readonly rows: Locator;

  constructor(private readonly page: Page) {
    this.status = page.getByLabel('Status');
    this.loadMore = page.getByRole('button', { name: 'Load more' });
    this.rows = page.getByRole('row');
  }

  async goto(): Promise<void> {
    await this.page.goto('/runs');
  }

  async gotoHistory(status?: string): Promise<void> {
    await this.page.goto(
      status ? `/history?status=${encodeURIComponent(status)}` : '/history'
    );
  }

  /** The link of a run by the start of its execution id. */
  run(idPrefix: string): Locator {
    return this.page.getByRole('link', { name: idPrefix });
  }
}
