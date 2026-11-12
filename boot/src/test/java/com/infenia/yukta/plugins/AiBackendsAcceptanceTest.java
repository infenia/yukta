// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugins;

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
    WebTestClient.bindToApplicationContext(context)
        .build()
        .get()
        .uri("/api/ai/backends")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[?(@.kind=='SYSTEMONE')].options[?(@.name=='model')]")
        .isNotEmpty()
        .jsonPath("$.data[?(@.kind=='LLM')].options[?(@.name=='provider')]")
        .isNotEmpty();
  }
}
