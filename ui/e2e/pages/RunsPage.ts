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

  /** Opens the history, filtered by status and/or by text (an execution id, a session or a workflow). */
  async gotoHistory(status?: string, text?: string): Promise<void> {
    const query = new URLSearchParams();
    if (status) query.set('status', status);
    if (text) query.set('text', text);
    const qs = query.toString();
    await this.page.goto(qs ? `/history?${qs}` : '/history');
  }

  /** The link of a run by the start of its execution id. */
  run(idPrefix: string): Locator {
    return this.page.getByRole('link', { name: idPrefix });
  }
}
