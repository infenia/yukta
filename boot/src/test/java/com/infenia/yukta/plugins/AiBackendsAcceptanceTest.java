// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The application starts with no Ollama and lists the decision backends with their options, so a
 * client can configure one from the API alone.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@NoArgsConstructor
class AiBackendsAcceptanceTest {

  @Autowired private ApplicationContext context;

  @Test
  void listsSystemOneAndLlmWithTheirOptions() {
    final String body =
        WebTestClient.bindToApplicationContext(context)
            .build()
            .get()
            .uri("/api/ai/backends")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    assertThat(body).contains("SYSTEMONE", "LLM", "\"provider\"", "\"model\"");
  }
}
