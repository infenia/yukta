// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** A request body above {@code spring.codec.max-in-memory-size} is refused with 413. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@NoArgsConstructor
@SuppressWarnings("PMD.SignatureDeclareThrowsException")
class RequestSizeLimitTest {

  @LocalServerPort private int port;

  private int post(final String path, final int bytes) throws Exception {
    final String body = "{\"x\":\"" + "a".repeat(bytes) + "\"}";
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  @Test
  void oversizedBodyIsAnswered413() throws Exception {
    assertThat(post("/api/sessions/validate", 2 * 1024 * 1024)).isEqualTo(413);
  }

  @Test
  void normalBodyIsNotRefusedForSize() throws Exception {
    assertThat(post("/api/sessions/validate", 100)).isNotEqualTo(413);
  }
}
