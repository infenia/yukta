// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Locator, Page } from '@playwright/test';

/** The supervisor form at /supervisors/new and /supervisors/:id/edit. */
export class SupervisorEditorPage {
  readonly name: Locator;
  readonly description: Locator;
  readonly decisionMaker: Locator;
  readonly reportsTo: Locator;
  readonly settings: Locator;

  constructor(private readonly page: Page) {
    this.name = page.getByLabel('Name', { exact: true });
    this.description = page.getByLabel('Description', { exact: true });
    this.decisionMaker = page.getByLabel('Decision maker');
    this.reportsTo = page.getByLabel('Reports to');
    this.settings = page.getByLabel('Settings (JSON)');
  }

  async gotoNew(): Promise<void> {
    await this.page.goto('/supervisors/new');
  }

  async gotoEdit(supervisorId: string): Promise<void> {
    await this.page.goto(
      `/supervisors/${encodeURIComponent(supervisorId)}/edit`
    );
  }

  /** A button of the form by its name (Save, Cancel, ...). */
  button(name: string | RegExp): Locator {
    return this.page.getByRole('button', { name });
  }
}
