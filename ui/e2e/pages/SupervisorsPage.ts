// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Locator, Page } from '@playwright/test';

/** The supervisors browser at /supervisors. */
export class SupervisorsPage {
  readonly navigation: Locator;

  constructor(private readonly page: Page) {
    this.navigation = page.getByRole('navigation', { name: 'Supervisors' });
  }

  async goto(supervisorId?: string): Promise<void> {
    await this.page.goto(
      supervisorId
        ? `/supervisors?supervisor=${encodeURIComponent(supervisorId)}`
        : '/supervisors'
    );
  }

  /** A region of the detail view: Inputs, Credential, ... */
  region(name: string): Locator {
    return this.page.getByRole('region', { name });
  }
}
