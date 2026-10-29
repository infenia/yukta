// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugins;

import static com.infenia.yukta.plugins.PluginWorkflows.node;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Happy path of the model-backed plugins. The model is a local server that speaks the OpenAI chat
 * protocol, so the real gateway, client and answer parsing run.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods", "PMD.UseConcurrentHashMap"})
@NoArgsConstructor
class AiPluginsAcceptanceTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  static {
    // a definition may not hold a key; this one is read from a yukta.var.* property
    System.setProperty("yukta.var.llm", "key");
  }

  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private SubWorkflowGateway runner;
  @Autowired private PluginRegistry registry;

  private PluginWorkflows wf;
  private FakeHttp model;

  @BeforeEach
  void setUp() throws IOException {
    wf = new PluginWorkflows(definitions, runner, registry);
    model = new FakeHttp();
  }

  @AfterEach
  void tearDown() {
    model.close();
  }

  /** The model answers with this text. */
  private void modelSays(final String text) {
    model.reply(
        JSON.writeValueAsString(
            Map.of(
                "id",
                "c1",
                "object",
                "chat.completion",
                "created",
                1,
                "model",
                "m",
                "choices",
                List.of(
                    Map.of(
                        "index",
                        0,
                        "message",
                        Map.of("role", "assistant", "content", text),
                        "finish_reason",
                        "stop")),
                "usage",
                Map.of("prompt_tokens", 3, "completion_tokens", 2, "total_tokens", 5))));
  }

  private Map<String, Object> chat(final Object... extra) {
    final Map<String, Object> config = new HashMap<>();
    config.put("provider", "openai");
    config.put("model", "m");
    config.put("baseUrl", model.url() + "/v1");
    config.put("apiKey", "${sys.yukta.var.llm}");
    for (int i = 0; i + 1 < extra.length; i += 2) {
      config.put((String) extra[i], extra[i + 1]);
    }
    return config;
  }

  private Map<String, Object> decide(final Object... extra) {
    final Map<String, Object> config = chat(extra);
    config.put("backend", "LLM");
    return config;
  }

  /** Runs one decision node and returns what it put under this metadata key. */
  private Object decision(
      final String type, final Map<String, Object> config, final String metadataKey) {
    wf.requirePlugin(type);
    final var key =
        wf.chain(
            node("d", type, config),
            node(
                "m",
                "MAPPER",
                Map.of("mode", "PROJECTION", "mapping", Map.of("out", "metadata." + metadataKey))));
    return wf.runOne(key, Map.of("text", "the checkout page crashes")).get("out");
  }

  @Test
  void llmCall_returnsTheModelAnswerAndUsage() {
    wf.requirePlugin("LLM_CALL");
    modelSays("hello from the model");
    final var key = wf.chain(node("llm", "LLM_CALL", chat("prompt", "say hello")));

    final Map<String, Object> out = wf.runOne(key, Map.of());

    assertThat(out).containsEntry("content", "hello from the model");
    assertThat(out.get("usage").toString()).contains("inputTokens=3", "outputTokens=2");
    assertThat(model.requests())
        .singleElement()
        .satisfies(r -> assertThat(r.body()).contains("say hello"));
  }

  @Test
  void sessionMemory_remembersTheConversation() {
    wf.requirePlugin("SESSION_MEMORY");
    final var key =
        wf.chain(
            node(
                "load",
                "SESSION_MEMORY",
                Map.of(
                    "mode", "load",
                    "sessionId", "${payload.sessionId}",
                    "message", "${payload.message}")),
            node("save", "SESSION_MEMORY", Map.of("mode", "append", "assistant", "answer-one")));
    final String session = "chat-" + UUID.randomUUID();
    wf.run(key, Map.of("sessionId", session, "message", "question-one"));

    final Map<String, Object> second =
        wf.runOne(key, Map.of("sessionId", session, "message", "question-two"));

    assertThat(second.toString()).contains("question-one").contains("answer-one");
  }

  @Test
  void classify_picksTheLabel() {
    modelSays("{\"label\":{\"choice\":\"bug\",\"probability\":0.95}}");

    final Object out =
        decision(
            "CLASSIFY",
            decide("labels", Map.of("bug", "software errors", "billing", "payments")),
            "classification");

    assertThat(out.toString()).contains("bug");
  }

  @Test
  void gate_passesWhenTheModelSaysYes() {
    modelSays("{\"gate\":{\"probability\":0.95}}");

    final Object out = decision("GATE", decide("question", "is it a bug?"), "gate");

    assertThat(out.toString()).contains("passed=true");
  }

  @Test
  void score_readsTheLevel() {
    modelSays("{\"score\":{\"score\":2}}");

    final Object out =
        decision(
            "SCORE",
            decide("question", "how bad?", "levels", List.of("low", "mid", "high")),
            "score");

    assertThat(out.toString()).contains("high");
  }

  @Test
  void ask_answersEveryQuestion() {
    modelSays("{\"q1\":{\"probability\":0.9}}");

    final Object out =
        decision(
            "ASK",
            decide("questions", Map.of("q1", Map.of("type", "yesno", "instructions", "urgent?"))),
            "answers");

    assertThat(out.toString()).contains("q1");
  }

  @Test
  void anomaly_startsWithWarmup() {
    modelSays("{\"anomaly\":{\"probability\":0.1}}");

    final Object out = decision("ANOMALY", decide(), "anomaly");

    assertThat(out.toString()).contains("warmup=true");
  }

  @Test
  void dedupe_marksTheFirstItemUnique() {
    modelSays("{\"duplicate\":{\"choice\":\"unique\",\"probability\":0.9}}");

    final Object out = decision("DEDUPE", decide(), "dedupe");

    assertThat(out.toString()).contains("duplicate=false");
  }
}
