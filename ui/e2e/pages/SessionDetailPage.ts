// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Locator, Page } from '@playwright/test';

/** One session at /sessions/:id, and its edit form at /sessions/:id/edit. */
export class SessionDetailPage {
  constructor(private readonly page: Page) {}

  async goto(sessionId: string): Promise<void> {
    await this.page.goto(`/sessions/${encodeURIComponent(sessionId)}`);
  }

  async gotoEdit(sessionId: string): Promise<void> {
    await this.page.goto(`/sessions/${encodeURIComponent(sessionId)}/edit`);
  }

  get heading(): Locator {
    return this.page.getByRole('heading').first();
  }

  /** A field of the session form by its label (Session ID, Name, Description, Working directory). */
  field(label: string): Locator {
    return this.page.getByLabel(label);
  }

  get save(): Locator {
    return this.page.getByRole('button', { name: /save|create session/i });
  }
}
