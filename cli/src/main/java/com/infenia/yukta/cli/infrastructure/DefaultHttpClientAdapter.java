/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.cli.infrastructure;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Default implementation of HTTP client adapter using Java HTTP client. */
@Component
@Slf4j
public class DefaultHttpClientAdapter implements HttpClientAdapter {

  /** HTTP client with one-second connection timeout. */
  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

  /**
   * Performs a health check against the daemon's health endpoint.
   *
   * @param port the daemon port
   * @return true if health check succeeds (200 status)
   * @throws Exception if connection or timeout occurs
   */
  @Override
  public boolean healthCheck(int port) throws Exception {
    String url = "http://127.0.0.1:" + port + "/actuator/health";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(1)).GET().build();

    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200;
    } catch (ConnectException | java.net.SocketTimeoutException e) {
      throw new ConnectException("Daemon not responding: " + e.getMessage());
    }
  }
}
