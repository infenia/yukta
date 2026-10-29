// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
@NoArgsConstructor
class LlmGatewayTest {

  private static final String OPENAI_BODY =
      "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m-served\","
          + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},"
          + "\"finish_reason\":\"stop\"}],"
          + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}";
  private static final String TOOL_CALL_BODY =
      "{\"id\":\"c2\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m-served\","
          + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,"
          + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":"
          + "{\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"Pune\\\"}\"}}]},"
          + "\"finish_reason\":\"tool_calls\"}],"
          + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":4,\"total_tokens\":11}}";
  private static final String ANTHROPIC_BODY =
      "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m-served\","
          + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"stop_reason\":\"end_turn\","
          + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}";
  private static final String OLLAMA_BODY =
      "{\"model\":\"m-served\",\"created_at\":\"2024-01-01T00:00:00Z\","
          + "\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"done\":true,"
          + "\"done_reason\":\"stop\",\"prompt_eval_count\":3,\"eval_count\":2}";

  private HttpServer server;
  private String base;
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private final LlmGateway gateway = new LlmGateway(new ObjectMapper());

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    reply("/v1/chat/completions", OPENAI_BODY);
    reply("/v1/messages", ANTHROPIC_BODY);
    reply("/api/chat", OLLAMA_BODY);
    reply("/tools/v1/chat/completions", TOOL_CALL_BODY);
    reply(
        "/empty/v1/chat/completions",
        "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,"
            + "\"model\":\"m\",\"choices\":[]}");
    server.start();
    base = "http://localhost:" + server.getAddress().getPort();
  }

  private void reply(final String path, final String json) {
    server.createContext(
        path,
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          send(exchange, json);
        });
  }

  private static void send(final HttpExchange exchange, final String json) throws IOException {
    final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private LlmRequest request(final Provider provider, final String path) {
    return new LlmRequest(
        provider,
        base + path,
        "key",
        "m",
        0.2,
        64,
        Duration.ofSeconds(10),
        "be brief",
        List.of(
            new ChatTurn("user", "q1"),
            new ChatTurn("Assistant", "a1"),
            new ChatTurn("user", "q2")));
  }

  @Test
  void promptAndAnswerFiltersRefuseTheCall() {
    gateway.setDenyInput(List.of("(?i)ignore previous", "q2"));
    assertThatThrownBy(() -> gateway.call(request(Provider.OPENAI, "/v1")))
        .hasMessageContaining("deny-input-patterns");
    assertThat(bodies).isEmpty();

    gateway.setDenyInput(List.of());
    gateway.setDenyOutput(List.of("^hi$"));
    assertThatThrownBy(() -> gateway.call(request(Provider.OPENAI, "/v1")))
        .hasMessageContaining("deny-output-patterns");
  }

  @Test
  void providerRateLimit_refusesCallThatWouldWaitLongerThanItMayTake() {
    gateway.setRateLimits(java.util.Map.of("openai", 1)); // one per minute, call timeout is 10 s

    assertThat(gateway.call(request(Provider.OPENAI, "/v1")).text()).isEqualTo("hi");
    assertThatThrownBy(() -> gateway.call(request(Provider.OPENAI, "/v1")))
        .hasMessageContaining("Rate limit of OPENAI");
    // another provider is not held back
    assertThat(gateway.call(request(Provider.OLLAMA, "")).text()).isNotNull();
  }

  @Test
  void promptLengthIsLimited() {
    gateway.setMaxInputChars(5);
    assertThatThrownBy(() -> gateway.call(request(Provider.OPENAI, "/v1")))
        .hasMessageContaining("max-input-chars");

    gateway.setMaxInputChars(10_000);
    assertThat(gateway.call(request(Provider.OPENAI, "/v1")).text()).isEqualTo("hi");
  }

  @Test
  void openAiCompatible() {
    final LlmResponse response = gateway.call(request(Provider.OPENAI, "/v1"));

    assertThat(response).isEqualTo(new LlmResponse("hi", 3, 2, "m-served"));
    assertThat(bodies.getFirst()).contains("be brief", "q1", "a1", "q2");
  }

  @Test
  void modelCacheIsBoundedAndDroppedModelIsBuiltAgain() {
    for (int i = 0; i < 130; i++) {
      gateway.call(modelRequest("m" + i));
    }

    assertThat(gateway.modelCacheSize()).isLessThanOrEqualTo(100L);
    assertThat(gateway.call(modelRequest("m0")).text()).isEqualTo("hi");
  }

  private LlmRequest modelRequest(final String model) {
    return new LlmRequest(
        Provider.OPENAI,
        base + "/v1",
        "key",
        model,
        null,
        null,
        Duration.ofSeconds(10),
        null,
        List.of(new ChatTurn("user", "q")));
  }

  @Test
  void toolsAreSentToTheModel_andItsToolCallsComeBackWithoutBeingRun() {
    final LlmRequest request =
        new LlmRequest(
            Provider.OPENAI,
            base + "/tools/v1",
            "key",
            "m",
            null,
            null,
            Duration.ofSeconds(10),
            null,
            List.of(new ChatTurn("user", "weather in Pune?")),
            List.of(
                new ToolSpec(
                    "weather",
                    "Current weather",
                    java.util.Map.of(
                        "type",
                        "object",
                        "properties",
                        java.util.Map.of("city", java.util.Map.of("type", "string"))))));

    final LlmResponse response = gateway.call(request);

    assertThat(response.toolCalls())
        .containsExactly(new ToolCallRequest("call_1", "weather", "{\"city\":\"Pune\"}"));
    assertThat(response.inputTokens()).isEqualTo(7);
    assertThat(bodies.getFirst()).contains("\"tools\"", "weather", "Current weather", "city");
  }

  private LlmRequest withTool(final Provider provider, final String path) {
    return new LlmRequest(
        provider,
        base + path,
        "key",
        "m",
        null,
        null,
        Duration.ofSeconds(10),
        null,
        List.of(new ChatTurn("user", "q")),
        List.of(new ToolSpec("lookup", "Look something up", java.util.Map.of())));
  }

  @Test
  void toolExchange_isSentAsAssistantToolCallsAndToolResult() {
    final LlmRequest base = request(Provider.OPENAI, "/v1");
    final LlmRequest exchange =
        new LlmRequest(
            base.provider(),
            base.baseUrl(),
            base.apiKey(),
            base.model(),
            base.temperature(),
            base.maxTokens(),
            base.timeout(),
            base.system(),
            List.of(
                new ChatTurn("user", "weather in Oslo?"),
                new ChatTurn(
                    "assistant",
                    "",
                    null,
                    null,
                    List.of(new ToolCallRequest("call_1", "weather", "{\"city\":\"Oslo\"}"))),
                new ChatTurn("tool", "rain", "call_1", "weather", List.of())));

    gateway.call(exchange);

    final String sent = bodies.getLast();
    assertThat(sent).contains("\"tool_calls\"").contains("call_1").contains("weather");
    assertThat(sent).contains("\"role\":\"tool\"").contains("\"tool_call_id\":\"call_1\"");
  }

  @Test
  void toolsAreSentToAnthropicAndOllamaToo() {
    assertThat(gateway.call(withTool(Provider.ANTHROPIC, "")).text()).isEqualTo("hi");
    assertThat(gateway.call(withTool(Provider.OLLAMA, "")).text()).isEqualTo("hi");

    assertThat(bodies).hasSize(2).allSatisfy(body -> assertThat(body).contains("lookup"));
  }

  @Test
  void definitionOnlyTool_isDescribedButNeverRun() {
    final LlmGateway.DefinitionOnlyTool tool =
        new LlmGateway.DefinitionOnlyTool(
            new ToolSpec("t", "d", java.util.Map.of("k", "v")), new ObjectMapper());

    assertThat(tool.getToolDefinition().name()).isEqualTo("t");
    assertThat(tool.getToolDefinition().inputSchema()).contains("k");
    assertThatThrownBy(() -> tool.call("{}")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void anthropic() {
    final LlmResponse response = gateway.call(request(Provider.ANTHROPIC, ""));

    assertThat(response.text()).isEqualTo("hi");
    assertThat(response.inputTokens()).isEqualTo(3);
    assertThat(response.outputTokens()).isEqualTo(2);
    assertThat(bodies.getFirst()).contains("be brief", "q1", "a1", "q2");
  }

  @Test
  void ollama() {
    final LlmResponse response = gateway.call(request(Provider.OLLAMA, ""));

    assertThat(response.text()).isEqualTo("hi");
    assertThat(response.inputTokens()).isEqualTo(3);
    assertThat(response.outputTokens()).isEqualTo(2);
    assertThat(bodies.getFirst()).contains("be brief", "q1", "a1", "q2");
  }

  @Test
  void ollamaWithDefaultBaseUrlFailsFastWithoutBuiltInRetries() {
    // Nothing listens on Ollama's default port in tests; a built-in retry loop would hang here.
    final LlmRequest request =
        new LlmRequest(
            Provider.OLLAMA,
            null,
            null,
            "m",
            null,
            null,
            Duration.ofSeconds(1),
            null,
            List.of(new ChatTurn("user", "q")));

    assertThatThrownBy(() -> gateway.call(request)).isInstanceOf(RuntimeException.class);
  }

  @Test
  void ollamaHonoursTheCallTimeoutWhenTheServerHangs() {
    server.createContext(
        "/slow/api/chat",
        exchange -> {
          try {
            // a hung server is the input of the timeout test
            Thread.sleep(5_000); // nosem: yukta-test-thread-sleep
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    final LlmRequest request =
        new LlmRequest(
            Provider.OLLAMA,
            base + "/slow",
            null,
            "m",
            null,
            null,
            Duration.ofMillis(500),
            null,
            List.of(new ChatTurn("user", "q")));

    final long start = System.nanoTime();
    assertThatThrownBy(() -> gateway.call(request)).isInstanceOf(RuntimeException.class);

    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
  }

  @Test
  void reusesModelAndSkipsBlankSystemPrompt() {
    final LlmRequest request =
        new LlmRequest(
            Provider.OPENAI,
            base + "/v1",
            "key",
            "m",
            null,
            null,
            Duration.ofSeconds(10),
            " ",
            List.of(new ChatTurn("user", "q")));

    gateway.call(request);
    gateway.call(request);

    assertThat(bodies).hasSize(2);
    assertThat(bodies.getFirst()).doesNotContain("\"system\"");
  }

  @Test
  void noChoicesYieldsEmptyText() {
    final LlmRequest request =
        new LlmRequest(
            Provider.OPENAI,
            base + "/empty/v1",
            "key",
            "m",
            null,
            null,
            Duration.ofSeconds(10),
            null,
            List.of(new ChatTurn("user", "q")));

    final LlmResponse response = gateway.call(request);

    assertThat(response.text()).isEmpty();
    assertThat(response.inputTokens()).isZero();
  }

  @Test
  void indexedPatternsKeepTheirCommas_andAnchorsApplyToEachTurn() {
    gateway.readPatterns(
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("yukta.llm.deny-input-patterns[0]", "^q{1,1}2$"));

    assertThatThrownBy(() -> gateway.call(request(Provider.OPENAI, "/v1")))
        .hasMessageContaining("deny-input-patterns");
    assertThat(bodies).isEmpty();
  }

  @Test
  void rateLimitFromTheEnvironment_spacesCalls_andAnInterruptedWaitFails() {
    gateway.readPatterns(
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("yukta.llm.rate-limit.openai", "600"));

    // 600 per minute is 100 ms apart: the second call waits for its turn and still succeeds
    assertThat(gateway.call(request(Provider.OPENAI, "/v1")).text()).isEqualTo("hi");
    assertThat(gateway.call(request(Provider.OPENAI, "/v1")).text()).isEqualTo("hi");

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> gateway.call(request(Provider.OPENAI, "/v1")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Interrupted");
    } finally {
      assertThat(Thread.interrupted()).isTrue(); // the flag is kept for the caller; clear it here
    }
  }
}
