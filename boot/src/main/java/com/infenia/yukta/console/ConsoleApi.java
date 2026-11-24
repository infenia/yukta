// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The HTTP calls of the console to the server's REST API, and the time of day of its stamps. */
final class ConsoleApi {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private ConsoleApi() {}

  /** The time of day of an ISO timestamp ({@code 2026-01-01T10:00:07.5Z} gives 10:00:07). */
  /* default */ static String time(final String iso) {
    final int t = iso.indexOf('T');
    return t < 0 ? iso : iso.substring(t + 1, Math.min(iso.length(), t + 9));
  }

  /* default */ static JsonNode get(final String url, final String key)
      throws IOException, InterruptedException {
    final HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2));
    if (!key.isBlank()) {
      b.header("Authorization", "Bearer " + key);
    }
    return JSON.readTree(HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()).body());
  }
}
