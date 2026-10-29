// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Tests for GatePlugin and ScorePlugin. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UseConcurrentHashMap"})
class GateAndScorePluginTest {

  private static Map<String, Object> config(final Object... extra) {
    final Map<String, Object> config = new HashMap<>();
    config.put("model", "nimble");
    config.put("question", "Is it fine?");
    for (int i = 0; i < extra.length; i += 2) {
      config.put((String) extra[i], extra[i + 1]);
    }
    return config;
  }

  private static Message<?> run(final DecisionPlugin plugin, final Map<String, Object> config) {
    return plugin.process(Flux.just(FakeModel.message("payload")), config).blockFirst();
  }

  private static GatePlugin gate(final double yes) {
    return new GatePlugin(
        FakeModel.always(Map.of("gate", new Answer.YesNo(yes))), FakeModel.plainResolver());
  }

  private static ScorePlugin score(final double score) {
    return new ScorePlugin(
        FakeModel.always(Map.of("score", new Answer.Score(score))), FakeModel.plainResolver());
  }

  @Test
  void gate_passesAtOrAboveThreshold() {
    assertThat(run(gate(0.5), config()).sourcePort()).isEqualTo("pass");
    assertThat(run(gate(0.7), config("threshold", 0.8)).sourcePort()).isEqualTo("reject");
    final Message<?> out = run(gate(0.9), config("into", "safe"));
    assertThat(out.metadata().get("safe")).isEqualTo(Map.of("probability", 0.9, "passed", true));
  }

  @Test
  void gate_asksTheConfiguredQuestion() {
    final FakeModel model = FakeModel.always(Map.of("gate", new Answer.YesNo(1)));

    new GatePlugin(model, FakeModel.plainResolver())
        .process(Flux.just(FakeModel.message("x")), config())
        .blockFirst();

    assertThat(model.asked.getFirst().questions())
        .isEqualTo(Map.of("gate", new Question.YesNo("Is it fine?")));
  }

  @Test
  void gate_declaresPortsSchemaAndValidation() {
    final GatePlugin plugin = gate(1);

    assertThat(plugin.getType()).isEqualTo("GATE");
    assertThat(plugin.describe().description()).contains("yes/no");
    assertThat(plugin.getOutputPorts(config())).containsExactly("pass", "reject");
    final List<ConfigField> schema = plugin.getConfigSchema();
    assertThat(schema.stream().map(ConfigField::name)).startsWith("question", "threshold");
    assertThat(schema.stream().filter(ConfigField::required).map(ConfigField::name))
        .containsExactly("question", "model");
    StepVerifier.create(plugin.validateConfig(config())).verifyComplete();
    StepVerifier.create(plugin.validateConfig(config("question", "")))
        .expectErrorMessage("question is required")
        .verify();
  }

  @Test
  void score_nearestLevelAndDefaultPort() {
    final Map<String, Object> config = config("levels", List.of("low", "mid", "high"));

    final Message<?> out = run(score(1.4), config);

    assertThat(out.sourcePort()).isEqualTo("default");
    assertThat(out.metadata().get("score")).isEqualTo(Map.of("score", 1.4, "level", "mid"));
    assertThat(((Map<?, ?>) run(score(9), config).metadata().get("score")).get("level"))
        .isEqualTo("high");
    assertThat(((Map<?, ?>) run(score(-1), config).metadata().get("score")).get("level"))
        .isEqualTo("low");
  }

  @Test
  void score_withThresholdRoutesHighOrLow() {
    final Map<String, Object> config = config("levels", List.of("low", "high"), "threshold", 0.5);

    assertThat(run(score(0.5), config).sourcePort()).isEqualTo("high");
    assertThat(run(score(0.4), config).sourcePort()).isEqualTo("low");
  }

  @Test
  void score_declaresPortsSchemaAndValidation() {
    final ScorePlugin plugin = score(0);
    final Map<String, Object> valid = config("levels", List.of("low", "high"));

    assertThat(plugin.getType()).isEqualTo("SCORE");
    assertThat(plugin.describe().description()).contains("ordered scale");
    assertThat(plugin.getOutputPorts(valid)).containsExactly("default");
    assertThat(plugin.getOutputPorts(config("threshold", 1))).containsExactly("high", "low");
    assertThat(
            plugin.getConfigSchema().stream().filter(ConfigField::required).map(ConfigField::name))
        .containsExactly("question", "levels", "model");
    StepVerifier.create(plugin.validateConfig(valid)).verifyComplete();
    StepVerifier.create(plugin.validateConfig(config("levels", List.of("one"))))
        .expectErrorMessage("levels needs 2 to 26 entries")
        .verify();
    StepVerifier.create(plugin.validateConfig(config("levels", "text")))
        .expectErrorMessage("levels needs 2 to 26 entries")
        .verify();
    StepVerifier.create(
            plugin.validateConfig(
                config("levels", java.util.stream.IntStream.range(0, 27).boxed().toList())))
        .expectErrorMessage("levels needs 2 to 26 entries")
        .verify();
  }
}
