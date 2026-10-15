// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for ClassifyPlugin, and through it for the behaviour all decision plugins share. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.LawOfDemeter",
  "PMD.TooManyMethods",
  "PMD.UseConcurrentHashMap"
})
class ClassifyPluginTest {

  private static Map<String, Object> config(final Object... extra) {
    final Map<String, Object> config = new HashMap<>();
    config.put("model", "nimble");
    config.put("labels", Map.of("bug", "software errors", "billing", "payments"));
    for (int i = 0; i < extra.length; i += 2) {
      config.put((String) extra[i], extra[i + 1]);
    }
    return config;
  }

  private static Answer.Choice choice(final String label, final double probability) {
    return new Answer.Choice(label, Map.of(label, probability));
  }

  private static ClassifyPlugin plugin(final FakeModel model) {
    return new ClassifyPlugin(model, FakeModel.plainResolver());
  }

  private static Message<?> run(
      final ClassifyPlugin plugin, final Map<String, Object> config, final Object payload) {
    return plugin.process(Flux.just(FakeModel.message(payload)), config).blockFirst();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> result(final Message<?> message, final String key) {
    return (Map<String, Object>) message.metadata().get(key);
  }

  @Test
  void process_routesByLabel() {
    final FakeModel model = FakeModel.always(Map.of("label", choice("bug", 0.9)));

    final Message<?> out = run(plugin(model), config("routeByLabel", true), "checkout broke");

    assertThat(out.sourcePort()).isEqualTo("bug");
    assertThat(out.payload()).isEqualTo("checkout broke");
    assertThat(result(out, "classification"))
        .containsEntry("label", "bug")
        .containsEntry("probability", 0.9)
        .containsEntry("uncertain", false);
    assertThat(model.asked.getFirst().state()).isEqualTo("checkout broke");
  }

  @Test
  void process_lowProbability_isUncertain() {
    final Message<?> out =
        run(
            plugin(FakeModel.always(Map.of("label", choice("bug", 0.3)))),
            config("routeByLabel", true),
            "x");

    assertThat(out.sourcePort()).isEqualTo("uncertain");
    assertThat(result(out, "classification")).containsEntry("uncertain", true);
  }

  @Test
  void process_unknownLabel_isUncertain() {
    final Message<?> out =
        run(
            plugin(FakeModel.always(Map.of("label", choice("spam", 0.99)))),
            config("routeByLabel", true),
            "x");

    assertThat(out.sourcePort()).isEqualTo("uncertain");
  }

  @Test
  void process_withoutRouting_usesDefaultPortAndCustomKey() {
    final Message<?> out =
        run(
            plugin(FakeModel.always(Map.of("label", choice("billing", 0.8)))),
            config("into", "ticket", "minProbability", 0.7),
            "x");

    assertThat(out.sourcePort()).isEqualTo("default");
    assertThat(result(out, "ticket")).containsEntry("label", "billing");
  }

  @Test
  void outputPorts_followRouting() {
    final ClassifyPlugin plugin = plugin(FakeModel.always(Map.of()));

    assertThat(plugin.getOutputPorts(config("routeByLabel", true)))
        .containsExactlyInAnyOrder("bug", "billing", "uncertain");
    assertThat(plugin.getOutputPorts(config())).containsExactly("default");
  }

  @Test
  void configSchema_pinsNamesAndRequired() {
    final List<ConfigField> schema = plugin(FakeModel.always(Map.of())).getConfigSchema();

    assertThat(schema.stream().map(ConfigField::name))
        .containsExactly(
            "labels",
            "question",
            "minProbability",
            "routeByLabel",
            "backend",
            "provider",
            "model",
            "baseUrl",
            "apiKey",
            "timeout",
            "state",
            "into",
            "concurrency");
    assertThat(schema.stream().filter(ConfigField::required).map(ConfigField::name))
        .containsExactly("labels", "model");
  }

  @Test
  void describeAndType_areSet() {
    final ClassifyPlugin plugin = plugin(FakeModel.always(Map.of()));

    assertThat(plugin.getType()).isEqualTo("CLASSIFY");
    assertThat(plugin.describe().description()).contains("Classifies");
  }

  @Test
  void validateConfig_rejectsBadConfig() {
    final ClassifyPlugin plugin = plugin(FakeModel.always(Map.of()));

    StepVerifier.create(plugin.validateConfig(config())).verifyComplete();
    StepVerifier.create(plugin.validateConfig(config("labels", Map.of("only", "one"))))
        .expectErrorMessage("labels needs at least two labels")
        .verify();
    StepVerifier.create(plugin.validateConfig(config("labels", "text")))
        .expectErrorMessage("labels must be a non-empty map")
        .verify();
    StepVerifier.create(plugin.validateConfig(config("model", " ")))
        .expectErrorMessage("model is required")
        .verify();
    StepVerifier.create(plugin.validateConfig(config("backend", "LLM")))
        .expectErrorMessage("provider is required")
        .verify();
    StepVerifier.create(plugin.validateConfig(config("backend", "llm", "provider", "openai")))
        .verifyComplete();
  }

  @Test
  void defaultTimeout_coversTheModelTimeout() {
    final ClassifyPlugin plugin = plugin(FakeModel.always(Map.of()));

    assertThat(plugin.getDefaultTimeout(config())).isEqualTo(Duration.ofSeconds(35));
    assertThat(plugin.getDefaultTimeout(config("timeout", 10))).isEqualTo(Duration.ofSeconds(15));
  }

  @Test
  void ask_systemOneDefaultsToLocalOllama() {
    final FakeModel model = FakeModel.always(Map.of("label", choice("bug", 0.9)));

    run(plugin(model), config(), "x");

    final ModelSpec spec = model.asked.getFirst().spec();
    assertThat(spec.backend()).isEqualTo(ModelSpec.Backend.SYSTEMONE);
    assertThat(spec.baseUrl()).isEqualTo("http://localhost:11434");
    assertThat(spec.apiKey()).isNull();
    assertThat(spec.model()).isEqualTo("nimble");
    assertThat(spec.timeout()).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void blankOptionsFallBackToDefaults() {
    final FakeModel model = FakeModel.always(Map.of("label", choice("bug", 0.9)));

    run(plugin(model), config("question", " ", "baseUrl", " ", "apiKey", " "), "x");

    assertThat(model.asked.getFirst().spec().baseUrl()).isEqualTo("http://localhost:11434");
    assertThat(model.asked.getFirst().spec().apiKey()).isNull();
    assertThat(((Question.Choice) model.asked.getFirst().questions().get("label")).instructions())
        .isEqualTo("Which label fits this?");
  }

  @Test
  void validateConfig_rejectsEmptyLabels() {
    StepVerifier.create(
            plugin(FakeModel.always(Map.of())).validateConfig(config("labels", Map.of())))
        .expectErrorMessage("labels must be a non-empty map")
        .verify();
  }

  @Test
  void ask_llmBackendPassesProviderEndpointAndKey() {
    final FakeModel model = FakeModel.always(Map.of("label", choice("bug", 0.9)));

    run(
        plugin(model),
        config("backend", "LLM", "provider", "anthropic", "baseUrl", "http://api", "apiKey", "k"),
        "x");

    final ModelSpec spec = model.asked.getFirst().spec();
    assertThat(spec.backend()).isEqualTo(ModelSpec.Backend.LLM);
    assertThat(spec.provider()).isEqualTo("anthropic");
    assertThat(spec.baseUrl()).isEqualTo("http://api");
    assertThat(spec.apiKey()).isEqualTo("k");
  }

  @Test
  void ask_llmBackendWithoutEndpointKeepsProviderDefault() {
    final FakeModel model = FakeModel.always(Map.of("label", choice("bug", 0.9)));

    run(plugin(model), config("backend", "LLM", "provider", "openai"), "x");

    assertThat(model.asked.getFirst().spec().baseUrl()).isNull();
  }

  @Test
  void state_nonTextPayloadIsSentAsJson() {
    final FakeModel model = FakeModel.always(Map.of("label", choice("bug", 0.9)));

    run(plugin(model), config(), Map.of("text", "hello"));

    assertThat(model.asked.getFirst().state()).isEqualTo("{\"text\":\"hello\"}");
  }

  @Test
  void state_expressionPicksPartOfTheMessage() {
    final FakeModel model = FakeModel.always(Map.of("label", choice("bug", 0.9)));

    run(plugin(model), config("state", "payload['text']"), Map.of("text", "hello"));
    run(plugin(model), config("state", " "), "whole");

    assertThat(model.asked.get(0).state()).isEqualTo("hello");
    assertThat(model.asked.get(1).state()).isEqualTo("whole");
  }

  @Test
  void state_isCutBelowTheRequestLimit() {
    final FakeModel model = FakeModel.always(Map.of("label", choice("bug", 0.9)));

    run(plugin(model), config(), "x".repeat(40_000));
    run(plugin(model), config(), "short");

    assertThat(model.asked.get(0).state()).hasSize(15_000);
    assertThat(model.asked.get(1).state()).isEqualTo("short");
  }

  @Test
  void ask_modelFailure_failsTheMessage() {
    final ClassifyPlugin plugin =
        plugin(new FakeModel(_ -> Mono.error(new IllegalStateException("down"))));

    StepVerifier.create(plugin.process(Flux.just(FakeModel.message("x")), config()))
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(WorkflowExecutionException.class)
                    .hasMessage("Decision model failed: down"))
        .verify();
  }

  @Test
  void ask_workflowExceptionPassesThrough() {
    final ClassifyPlugin plugin =
        plugin(new FakeModel(_ -> Mono.error(new WorkflowExecutionException("no usable answer"))));

    StepVerifier.create(plugin.process(Flux.just(FakeModel.message("x")), config()))
        .expectErrorMessage("no usable answer")
        .verify();
  }

  @Test
  void answer_ofTheWrongKind_failsTheMessage() {
    final ClassifyPlugin plugin = plugin(FakeModel.always(Map.of("label", new Answer.YesNo(1))));

    StepVerifier.create(plugin.process(Flux.just(FakeModel.message("x")), config()))
        .expectErrorMessage("The model gave no usable answer to 'label'")
        .verify();
  }
}
