// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** Tests for SystemOneModel against a local JDK HTTP server posing as Ollama. */
@NoArgsConstructor
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.UseConcurrentHashMap"})
class SystemOneModelTest {

  /** Last request body. */
  private final AtomicReference<String> received = new AtomicReference<>();

  /** Answer of the stub. */
  private final AtomicReference<String> reply = new AtomicReference<>("");

  /** Status of the stub. */
  private final AtomicInteger status = new AtomicInteger(200);

  /** Stub Ollama. */
  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/v1/systemone",
        exchange -> {
          received.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          final byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status.get(), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private ModelSpec spec() {
    return new ModelSpec(
        ModelSpec.Backend.SYSTEMONE,
        null,
        "nimble",
        "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort(),
        null,
        Duration.ofSeconds(5));
  }

  private static Map<String, Question> questions() {
    final Map<String, Question> questions = new LinkedHashMap<>();
    questions.put("pick", new Question.Choice("Which?", Map.of("a", "first", "b", "second")));
    questions.put("yes", new Question.YesNo("Really?"));
    questions.put("rate", new Question.Score("How bad?", List.of("low", "high")));
    return questions;
  }

  @Test
  void answer_allQuestionTypes_sentAndParsed() {
    reply.set(
        """
        {"model":"nimble","answers":{
          "pick":{"type":"choice","choice":"b","probabilities":{"a":0.2,"b":0.8},"confidence":0.5},
          "yes":{"type":"noul","noul":0.9},
          "rate":{"type":"score","score":0.7,"probabilities":{"0":0.3,"1":0.7}}}}
        """);

    final Map<String, Answer> answers =
        new SystemOneModel(WebClient.builder()).answer(spec(), "some state", questions()).block();

    assertThat(answers.get("pick")).isEqualTo(new Answer.Choice("b", Map.of("a", 0.2, "b", 0.8)));
    assertThat(answers.get("yes")).isEqualTo(new Answer.YesNo(0.9));
    assertThat(answers.get("rate")).isEqualTo(new Answer.Score(0.7));
    assertThat(received.get())
        .contains("\"model\":\"nimble\"", "\"state\":\"some state\"")
        .contains("\"type\":\"choice\"", "\"type\":\"noul\"", "\"type\":\"score\"")
        .contains("\"criteria\":[\"low\",\"high\"]");
  }

  @Test
  void answer_missingAnswer_fails() {
    reply.set("{\"answers\":{}}");

    assertThatThrownBy(
            () -> new SystemOneModel(WebClient.builder()).answer(spec(), "s", questions()).block())
        .hasMessageContaining("no answer to 'pick'");
  }

  @Test
  void answer_httpError_fails() {
    status.set(404);
    reply.set("{\"error\":\"model not found\"}");

    assertThatThrownBy(
            () -> new SystemOneModel(WebClient.builder()).answer(spec(), "s", questions()).block())
        .hasMessageContaining("404");
  }

  @Test
  void answer_incompleteAnswers_defaultToZero() {
    reply.set(
        """
        {"answers":{"pick":{"choice":"a"},"yes":{},"rate":{}}}
        """);

    final Map<String, Answer> answers =
        new SystemOneModel(WebClient.builder()).answer(spec(), "s", questions()).block();

    assertThat(answers.get("pick")).isEqualTo(new Answer.Choice("a", Map.of()));
    assertThat(answers.get("yes")).isEqualTo(new Answer.YesNo(0));
    assertThat(answers.get("rate")).isEqualTo(new Answer.Score(0));
  }
}
