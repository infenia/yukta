// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.http;

import java.util.Map;

/**
 * Outcome of an HTTP request. Non-2xx statuses are data, not errors.
 *
 * @param status HTTP status code, or 0 when the request failed before a response arrived
 * @param headers response headers (first value of each)
 * @param json whether the response declared a JSON content type
 * @param body response body text (the error message when status is 0)
 */
public record HttpRequestResult(
    int status, Map<String, String> headers, boolean json, String body) {

  /** Defensively copies the headers. */
  public HttpRequestResult {
    headers = Map.copyOf(headers);
  }

  /**
   * Whether the status is 2xx.
   *
   * @return true for a successful response
   */
  public boolean isSuccess() {
    return status >= 200 && status < 300;
  }

  /**
   * Creates the result for a request that failed before any response arrived.
   *
   * @param message the failure description
   * @return a result with status 0
   */
  public static HttpRequestResult transportFailure(final String message) {
    return new HttpRequestResult(0, Map.of(), false, message);
  }
}
