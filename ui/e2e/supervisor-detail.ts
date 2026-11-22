// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { envelope } from './api-envelope';

/**
 * What a supervisor's page asks for besides the list: its decision maker's entry, its rules and its
 * versions. A spec that only mocks the list and lands on a supervisor registers these as well.
 */
export async function mockSupervisorDetail(page: Page) {
  await page.route(/\/api\/supervisor-engines\/[^/?]+$/, (r) =>
    r.fulfill(
      envelope('/engine', {
        type: 'rules',
        displayName: 'Rules',
        category: 'RULES',
        icon: 'rule',
        defaultAllowedCommands: null,
        configSchema: [],
        commonSchema: [],
      })
    )
  );
  await page.route(/\/api\/supervisors\/[^/]+\/engine\/rules$/, (r) =>
    r.fulfill(
      envelope('/rules', { version: 1, rules: { schemaVersion: 1, rules: [] } })
    )
  );
  await page.route(/\/api\/supervisors\/[^/]+\/versions$/, (r) =>
    r.fulfill(envelope('/versions', []))
  );
}
