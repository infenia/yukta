// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Locator, Page } from '@playwright/test';

/** The inbox at /inbox. */
export class InboxPage {
  readonly heading: Locator;
  readonly all: Locator;
  readonly needsAction: Locator;
  readonly resolved: Locator;
  readonly dialog: Locator;

  constructor(private readonly page: Page) {
    this.heading = page.getByRole('heading', { name: 'Inbox' });
    this.all = page.getByRole('tab', { name: /All/ });
    this.needsAction = page.getByRole('tab', { name: /Needs action/ });
    this.resolved = page.getByRole('tab', { name: /Resolved/ });
    this.dialog = page.getByRole('dialog');
  }

  /** Opens the inbox, on one item when its id is given (the item query of the URL). */
  async goto(item?: string): Promise<void> {
    await this.page.goto(
      item ? `/inbox?item=${encodeURIComponent(item)}` : '/inbox'
    );
  }

  /** A notification or question by its title. */
  item(title: string | RegExp): Locator {
    return this.page.getByText(title);
  }
}
