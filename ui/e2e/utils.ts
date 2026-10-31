// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page, Response, Locator } from '@playwright/test';

/**
 * Wait for API response with optional URL/method filtering
 *
 * @example
 * const response = await waitForApiResponse(page, { url: '/api/sessions' });
 * const data = await response.json();
 */
export async function waitForApiResponse(
  page: Page,
  options?: {
    url?: string | RegExp;
    method?: string;
    timeout?: number;
  }
): Promise<Response> {
  return page.waitForResponse(
    (response) => {
      if (options?.url) {
        const urlRegex =
          typeof options.url === 'string'
            ? new RegExp(options.url.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
            : options.url;
        if (!urlRegex.test(response.url())) return false;
      }
      if (options?.method && response.request().method() !== options.method) {
        return false;
      }
      return true;
    },
    { timeout: options?.timeout !== undefined ? options.timeout : 5000 }
  );
}

/**
 * Fill and submit a form by label text
 *
 * @example
 * await fillForm(page, { 'Name': 'John Doe', 'Email': 'john@example.com' });
 */
export async function fillForm(
  page: Page,
  fields: Record<string, string>
): Promise<void> {
  for (const [label, value] of Object.entries(fields)) {
    const input = page.getByLabel(label);
    await input.fill(value);
  }
}

/**
 * Wait for loading spinner to disappear
 *
 * @example
 * await waitForLoadingComplete(page);
 */
export async function waitForLoadingComplete(
  page: Page,
  timeout = 5000
): Promise<void> {
  await page.waitForLoadState('networkidle', { timeout });
  // Wait for fonts to load via document.fonts.ready API
  await page.evaluate(() => document.fonts.ready);
}

/**
 * Test table data by finding row containing specific cell text
 *
 * @example
 * const row = await getTableRow(page, 'Build Pipeline');
 * const cells = await row.locator('td').allTextContents();
 */
export async function getTableRow(page: Page, cellText: string) {
  return page.locator(`tr:has-text("${cellText}")`).first();
}

/**
 * Assert table contains expected columns
 *
 * @example
 * await assertTableColumns(page, ['Name', 'Initiator', 'Tags']);
 */
export async function assertTableColumns(
  page: Page,
  expectedColumns: string[]
): Promise<void> {
  for (const column of expectedColumns) {
    const header = page.locator(`th:has-text("${column}")`);
    await header.waitFor({ state: 'visible' });
  }
}

/**
 * Count visible table rows (excluding header)
 *
 * @example
 * const count = await countTableRows(page);
 */
export async function countTableRows(page: Page): Promise<number> {
  const rows = page.locator('tbody tr:visible');
  return rows.count();
}

/**
 * Mock API response for next request
 * MSW handles the actual mocking; this is a placeholder for custom overrides
 *
 * @example
 * await mockApiResponse(page, '/api/sessions');
 */
export async function mockApiResponse(
  page: Page,
  urlPattern: string | RegExp
): Promise<void> {
  // MSW server handles API mocking via page.route interception
  // This function is reserved for custom route handling if needed
  await page.route(urlPattern, (route) => {
    route.continue();
  });
}

/**
 * Mock `/api/metrics/*` with fixed data, so a dashboard spec makes no unmocked `/api` call.
 * `values` are the series keys of the snapshot (and of each series, which gets a 12-point ramp
 * ending at that value); the stream answers one sample and stays open until the page closes.
 *
 * @example
 * await mockMetricsApi(page, { 'yukta.executions.running': 3 });
 */
export async function mockMetricsApi(
  page: Page,
  values: Record<string, number> = {}
): Promise<void> {
  const ok = (data: unknown) => ({
    contentType: 'application/json',
    body: JSON.stringify({
      timestamp: new Date().toISOString(),
      status: 200,
      message: 'ok',
      data,
      path: '/api/metrics',
    }),
  });
  const now = Date.now();
  await page.route('**/api/metrics/snapshot', (route) =>
    route.fulfill(ok({ time: now, values }))
  );
  await page.route('**/api/metrics/catalog', (route) =>
    route.fulfill(ok({ series: Object.keys(values).sort() }))
  );
  await page.route('**/api/metrics/series?*', (route) => {
    const url = new URL(route.request().url());
    const names = (url.searchParams.get('names') ?? '').split(',');
    const series = Object.fromEntries(
      Object.entries(values)
        .filter(([key]) => names.some((name) => key.startsWith(name)))
        .map(([key, value]) => [
          key,
          Array.from({ length: 12 }, (_, i) => ({
            time: now - (11 - i) * 5000,
            value: (value * (i + 1)) / 12,
          })),
        ])
    );
    return route.fulfill(ok({ range: url.searchParams.get('range'), series }));
  });
  await page.route('**/api/metrics/stream', (route) =>
    route.fulfill({
      contentType: 'text/event-stream',
      body: `id: 0\ndata: ${JSON.stringify({ time: now, values })}\n\n`,
    })
  );
}

/**
 * Mock `/api/metrics/*` with fixed data, so a dashboard spec makes no unmocked `/api` call.
 * `values` are the series keys of the snapshot (and of each series, which gets a 12-point ramp
 * ending at that value); the stream answers one sample and stays open until the page closes.
 *
 * @example
 * await mockMetricsApi(page, { 'yukta.executions.running': 3 });
 */
export async function mockMetricsApi(
  page: Page,
  values: Record<string, number> = {}
): Promise<void> {
  const ok = (data: unknown) => ({
    contentType: 'application/json',
    body: JSON.stringify({
      timestamp: new Date().toISOString(),
      status: 200,
      message: 'ok',
      data,
      path: '/api/metrics',
    }),
  });
  const now = Date.now();
  await page.route('**/api/metrics/snapshot', (route) =>
    route.fulfill(ok({ time: now, values }))
  );
  await page.route('**/api/metrics/catalog', (route) =>
    route.fulfill(ok({ series: Object.keys(values).sort() }))
  );
  await page.route('**/api/metrics/series?*', (route) => {
    const url = new URL(route.request().url());
    const names = (url.searchParams.get('names') ?? '').split(',');
    const series = Object.fromEntries(
      Object.entries(values)
        .filter(([key]) => names.some((name) => key.startsWith(name)))
        .map(([key, value]) => [
          key,
          Array.from({ length: 12 }, (_, i) => ({
            time: now - (11 - i) * 5000,
            value: (value * (i + 1)) / 12,
          })),
        ])
    );
    return route.fulfill(ok({ range: url.searchParams.get('range'), series }));
  });
  await page.route('**/api/metrics/stream', (route) =>
    route.fulfill({
      contentType: 'text/event-stream',
      body: `id: 0\ndata: ${JSON.stringify({ time: now, values })}\n\n`,
    })
  );
}

/**
 * Get all visible text in an element (including children)
 *
 * @example
 * const text = await getTextContent(page.locator('[data-testid="error-message"]'));
 */
export async function getTextContent(locator: Locator): Promise<string> {
  return locator.evaluate((el) => (el as HTMLElement).textContent || '');
}

/**
 * Assert element is visible and has expected text
 *
 * @example
 * await assertElementText(page, '[data-testid="status"]', 'Active');
 */
export async function assertElementText(
  page: Page,
  selector: string,
  expectedText: string
): Promise<void> {
  const element = page.locator(selector);
  await element.waitFor({ state: 'visible' });
  const text = await getTextContent(element);
  if (!text.includes(expectedText)) {
    throw new Error(`Expected text "${expectedText}" not found in "${text}"`);
  }
}

/**
 * Click element and wait for navigation/response
 *
 * @example
 * await clickAndWait(page, 'button[type="submit"]', { waitForUrl: '/sessions/123' });
 */
export async function clickAndWait(
  page: Page,
  selector: string,
  options?: {
    waitForUrl?: string | RegExp;
    waitForResponse?: string | RegExp;
    timeout?: number;
  }
): Promise<void> {
  const button = page.locator(selector);

  if (options?.waitForUrl) {
    await Promise.all([
      page.waitForURL(options.waitForUrl, { timeout: options?.timeout }),
      button.click(),
    ]);
  } else if (options?.waitForResponse) {
    await Promise.all([
      waitForApiResponse(page, { url: options.waitForResponse }),
      button.click(),
    ]);
  } else {
    await button.click();
  }
}

/**
 * Get all text contents from multiple elements
 *
 * @example
 * const tags = await getTextContents(page.locator('span.tag'));
 */
export async function getTextContents(locator: Locator): Promise<string[]> {
  return locator.evaluateAll((elements) =>
    (elements as HTMLElement[]).map((el) => el.textContent || '')
  );
}
