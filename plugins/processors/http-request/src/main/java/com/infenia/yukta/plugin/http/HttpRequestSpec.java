// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.http;

import java.time.Duration;
import java.util.Map;

/**
 * Fully resolved HTTP request.
 *
 * @param method HTTP method, upper case
 * @param url absolute request URL
 * @param headers request headers
 * @param body request body (String or JSON-serializable object), or null
 * @param timeout overall request timeout
 */
public record HttpRequestSpec(
    String method, String url, Map<String, String> headers, Object body, Duration timeout) {

  /** Defensively copies the headers. */
  public HttpRequestSpec {
    headers = Map.copyOf(headers);
  }
}
