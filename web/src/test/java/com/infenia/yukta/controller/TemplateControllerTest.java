// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The templates are the examples bundled by the build. */
@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.LawOfDemeter", "PMD.UnitTestShouldIncludeAssert"})
class TemplateControllerTest {

  private final WebTestClient client =
      WebTestClient.bindToController(new TemplateController())
          .controllerAdvice(new GlobalExceptionHandler())
          .build();

  @Test
  void listsEveryExampleButNotTheDogfoodSetup() {
    client
        .get()
        .uri("/api/templates")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[?(@.id=='01-hello-world')].title")
        .isEqualTo("Hello world")
        .jsonPath("$.data[?(@.id=='01-hello-world')].pluginTypes[0]")
        .isEqualTo("CONSOLE_TERMINAL")
        .jsonPath("$.data[?(@.id=='11-dogfood')]")
        .isEmpty()
        .jsonPath("$.data.length()")
        .value(count -> assertThat((Integer) count).isGreaterThanOrEqualTo(9));
  }

  @Test
  void readmeAndSessionAreReturned() {
    client
        .get()
        .uri("/api/templates/01-hello-world")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.readme")
        .value(readme -> assertThat((String) readme).startsWith("# 01"))
        .jsonPath("$.data.session.sessionId")
        .isEqualTo("example-01-hello")
        .jsonPath("$.data.summary")
        .value(summary -> assertThat((String) summary).isNotBlank());
  }

  @Test
  void unknownOrOddIdsAre404() {
    client.get().uri("/api/templates/nope").exchange().expectStatus().isNotFound();
    client.get().uri("/api/templates/..").exchange().expectStatus().is4xxClientError();
  }

  @Test
  void headingTakesTitleAndUseCaseOfReadme() {
    final var heading =
        TemplateController.heading(
            "# 01 — Hello world\n\n## Part\n**Use case:** Say hi.\nlater", "t", "s");

    assertThat(heading.title()).isEqualTo("Hello world");
    assertThat(heading.summary()).isEqualTo("Say hi.");
  }

  @Test
  void headingKeepsFallbacksForReadmeWithoutThem() {
    final var heading = TemplateController.heading("## only a subheading\ntext", "t", "s");

    assertThat(heading.title()).isEqualTo("t");
    assertThat(heading.summary()).isEqualTo("s");
  }
}
