// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Locator, Page } from '@playwright/test';

/** Mission control at /control. */
export class ControlPage {
  readonly root: Locator;
  readonly kioskButton: Locator;
  readonly kiosk: Locator;

  constructor(private readonly page: Page) {
    this.root = page.getByTestId('mission-control');
    this.kioskButton = page.getByRole('button', { name: 'Kiosk' });
    this.kiosk = page.getByTestId('kiosk');
  }

  async goto(tab?: string): Promise<void> {
    await this.page.goto(
      tab ? `/control?tab=${encodeURIComponent(tab)}` : '/control'
    );
  }

  heading(name: string): Locator {
    return this.page.getByRole('heading', { name });
  }
}
