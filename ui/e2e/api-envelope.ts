// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** The clock of every mocked response, so a screenshot of a page that shows it does not move. */
const FIXED_TIMESTAMP = '2026-07-26T14:23:23';

/**
 * A mocked REST answer in the server's envelope `{timestamp, status, message, path, data}`, ready for
 * `route.fulfill(...)`. One copy for every spec; a status of 400 or more gives an error message.
 *
 * @example
 * await page.route('**\/api/sessions', (route) => route.fulfill(envelope('/api/sessions', [])));
 */
export const envelope = (path: string, data: unknown, status = 200) => ({
  status,
  contentType: 'application/json',
  body: JSON.stringify({
    timestamp: FIXED_TIMESTAMP,
    status,
    message: status < 400 ? 'OK' : 'connection refused',
    path,
    data,
  }),
});
