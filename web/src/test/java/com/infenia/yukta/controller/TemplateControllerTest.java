// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The templates are the examples bundled by the build. */
@NoArgsConstructor
@SuppressWarnings({"PMD.UnitTestShouldIncludeAssert", "PMD.TooManyMethods"})
class TemplateControllerTest {

  private static final String FOLDER = "1-x";

  private static final String TEMPLATES = "/api/templates";

  private final WebTestClient client =
      WebTestClient.bindToController(new TemplateController())
          .controllerAdvice(new GlobalExceptionHandler())
          .build();

  @Test
  void listsEveryExampleButNotTheDogfoodSetup() {
    client
        .get()
        .uri(TEMPLATES)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[?(@.id=='01-hello-world')].title")
        .isEqualTo("Hello world")
        .jsonPath("$.data[?(@.id=='01-hello-world')].category")
        .isEqualTo("1-standalone")
        .jsonPath("$.data[?(@.id=='01-hello-world')].pluginTypes[0]")
        .isEqualTo("API_TRIGGER")
        .jsonPath("$.data[?(@.id=='dogfood')]")
        .isEmpty()
        .jsonPath("$.data.length()")
        .value(count -> assertThat((Integer) count).isGreaterThanOrEqualTo(9));
  }

  @Test
  void listsInTheOrderOfTheIndexFile() {
    client
        .get()
        .uri(TEMPLATES)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[0].id")
        .isEqualTo("01-hello-world")
        .jsonPath("$.data[1].id")
        .isEqualTo("18-todo-run")
        .jsonPath("$.data[2].id")
        .isEqualTo("19-todo-ci");
  }

  @Test
  void cardsCarryTheirCategoryLabelAndMeaning() {
    client
        .get()
        .uri(TEMPLATES)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[?(@.id=='01-hello-world')].categoryLabel")
        .isEqualTo("Standalone")
        .jsonPath("$.data[?(@.id=='01-hello-world')].categoryDescription")
        .value(text -> assertThat(text.toString()).contains("Yukta server"));
  }

  @Test
  void theIndexFileNamesOnlyTemplatesThatExistAndEveryCategoryOfThem() throws Exception {
    final JsonNode index =
        JsonMapper.builder()
            .build()
            .readTree(new ClassPathResource("templates/index.json").getContentAsByteArray());
    final List<String> listed = new ArrayList<>();
    index.path("order").forEach(id -> listed.add(id.asString()));
    final var body = client.get().uri(TEMPLATES).exchange().expectBody(String.class).returnResult();
    final JsonNode templates = JsonMapper.builder().build().readTree(body.getResponseBody());
    final List<String> ids = new ArrayList<>();
    final List<String> categories = new ArrayList<>();
    templates.path("data").forEach(t -> ids.add(t.path("id").asString()));
    templates.path("data").forEach(t -> categories.add(t.path("category").asString()));

    assertThat(ids).containsAll(listed);
    assertThat(listed).doesNotHaveDuplicates();
    assertThat(categories)
        .allSatisfy(category -> assertThat(index.path("categories").has(category)).isTrue());
  }

  @Test
  void unlistedTemplatesComeAfterTheListedOnes() {
    final var index = new TemplateController.Index(List.of("b", "a"), Map.of());

    assertThat(index.rank("b")).isZero();
    assertThat(index.rank("a")).isEqualTo(1);
    assertThat(index.rank("unlisted")).isEqualTo(2);
  }

  @Test
  void unnamedCategoryIsCalledByItsFolder() {
    final var index =
        new TemplateController.Index(
            List.of(), Map.of(FOLDER, new TemplateController.Category("X", "what x means")));

    assertThat(index.category(FOLDER).label()).isEqualTo("X");
    assertThat(index.category(FOLDER).description()).isEqualTo("what x means");
    assertThat(index.category("9-y").label()).isEqualTo("9-y");
    assertThat(index.category("9-y").description()).isEmpty();
  }

  @Test
  void withoutAnIndexFileTheTemplatesAreUnorderedAndTheCategoriesBare() throws Exception {
    final var index = TemplateController.index(new ClassPathResource("templates/none.json"));

    assertThat(index.order()).isEmpty();
    assertThat(index.categories()).isEmpty();
  }

  @Test
  void theIndexFileIsReadForItsOrderAndItsCategories() throws Exception {
    final var json =
        "{\"order\":[\"b\",\"a\"],\"categories\":{\"1-x\":{\"label\":\"X\",\"description\":\"d\"},"
            + "\"2-y\":{}}}";
    final var index =
        TemplateController.index(new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)));

    assertThat(index.order()).containsExactly("b", "a");
    assertThat(index.category(FOLDER).label()).isEqualTo("X");
    assertThat(index.category("2-y").label()).isEqualTo("2-y");
    assertThat(index.category("2-y").description()).isEmpty();
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
