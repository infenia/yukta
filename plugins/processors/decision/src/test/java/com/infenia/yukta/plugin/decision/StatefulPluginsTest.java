// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for AnomalyPlugin and DedupePlugin. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.LawOfDemeter",
  "PMD.TooManyMethods",
  "PMD.UseConcurrentHashMap"
})
class StatefulPluginsTest {

  private static Map<String, Object> config(final Object... extra) {
    final Map<String, Object> config = new HashMap<>();
    config.put("model", "nimble");
    for (int i = 0; i < extra.length; i += 2) {
      config.put((String) extra[i], extra[i + 1]);
    }
    return config;
  }

  private static List<Message<?>> run(
      final DecisionPlugin plugin, final Map<String, Object> config, final String... items) {
    return plugin
        .process(Flux.fromArray(items).map(FakeModel::message), config)
        .collectList()
        .block();
  }

  private static List<String> ports(final List<Message<?>> messages) {
    return messages.stream().map(Message::sourcePort).toList();
  }

  // --- ANOMALY ---

  @Test
  void anomaly_judgesOnlyAfterWarmup() {
    final FakeModel model = FakeModel.always(Map.of("anomaly", new Answer.YesNo(0.9)));
    final AnomalyPlugin plugin = new AnomalyPlugin(model, FakeModel.plainResolver());

    final List<Message<?>> out = run(plugin, config("minHistory", 2), "a", "b", "c", "d");

    assertThat(ports(out)).containsExactly("normal", "normal", "anomalous", "anomalous");
    assertThat(model.asked).hasSize(2);
    assertThat(model.asked.getFirst().state())
        .isEqualTo("Recent items, oldest first:\n- a\n- b\n\nCurrent item:\nc");
    assertThat(out.getFirst().metadata().get("anomaly"))
        .isEqualTo(Map.of("probability", 0.0, "warmup", true));
    assertThat(out.get(2).metadata().get("anomaly"))
        .isEqualTo(Map.of("probability", 0.9, "warmup", false));
  }

  @Test
  void anomaly_belowThresholdIsNormal() {
    final AnomalyPlugin plugin =
        new AnomalyPlugin(
            FakeModel.always(Map.of("anomaly", new Answer.YesNo(0.4))), FakeModel.plainResolver());

    assertThat(ports(run(plugin, config("minHistory", 0), "a"))).containsExactly("normal");
  }

  @Test
  void anomaly_windowKeepsTheLastItemsAndEachRunStartsEmpty() {
    final FakeModel model = FakeModel.always(Map.of("anomaly", new Answer.YesNo(0)));
    final AnomalyPlugin plugin = new AnomalyPlugin(model, FakeModel.plainResolver());
    final Map<String, Object> config = config("minHistory", 0, "window", 2, "question", "Odd?");

    run(plugin, config, "a", "b", "c", "d");
    run(plugin, config, "x");

    assertThat(model.asked.get(3).state()).contains("- b\n- c\n").doesNotContain("- a");
    assertThat(model.asked.get(4).state())
        .isEqualTo("Recent items, oldest first:\n\nCurrent item:\nx");
    assertThat(model.asked.getFirst().questions())
        .isEqualTo(Map.of("anomaly", new Question.YesNo("Odd?")));
  }

  @Test
  void anomaly_longItemsAreShortenedInTheWindow() {
    final FakeModel model = FakeModel.always(Map.of("anomaly", new Answer.YesNo(0)));

    run(
        new AnomalyPlugin(model, FakeModel.plainResolver()),
        config("minHistory", 0),
        "y".repeat(900),
        "z");

    assertThat(model.asked.get(1).state()).contains("- " + "y".repeat(500) + "\n\n");
  }

  @Test
  void anomaly_decideHandlesOneMessage() {
    final AnomalyPlugin plugin =
        new AnomalyPlugin(
            FakeModel.always(Map.of("anomaly", new Answer.YesNo(1))), FakeModel.plainResolver());

    assertThat(
            plugin.process(Flux.just(FakeModel.message("a")), config("minHistory", 0)).blockFirst())
        .isNotNull();
    StepVerifier.create(plugin.decide(FakeModel.message("a"), config("minHistory", 0)))
        .assertNext(message -> assertThat(message.sourcePort()).isEqualTo("anomalous"))
        .verifyComplete();
  }

  @Test
  void anomaly_declaresPortsSchemaAndValidation() {
    final AnomalyPlugin plugin =
        new AnomalyPlugin(FakeModel.always(Map.of()), FakeModel.plainResolver());

    assertThat(plugin.getType()).isEqualTo("ANOMALY");
    assertThat(plugin.describe().description()).contains("unusual");
    assertThat(plugin.getOutputPorts(config())).containsExactly("normal", "anomalous");
    assertThat(plugin.getConfigSchema().stream().map(ConfigField::name))
        .startsWith("window", "minHistory", "threshold", "question");
    assertThat(
            plugin.getConfigSchema().stream().filter(ConfigField::required).map(ConfigField::name))
        .containsExactly("model");
    StepVerifier.create(plugin.validateConfig(config())).verifyComplete();
    StepVerifier.create(plugin.validateConfig(config("window", 0)))
        .expectErrorMessage("window must be 1 or more and minHistory 0 or more")
        .verify();
    StepVerifier.create(plugin.validateConfig(config("minHistory", -1)))
        .expectErrorMessage("window must be 1 or more and minHistory 0 or more")
        .verify();
  }

  // --- DEDUPE ---

  private static Answer.Choice same(final String key, final double probability) {
    return new Answer.Choice(key, Map.of(key, probability));
  }

  @Test
  void dedupe_firstItemIsUniqueWithoutAskingTheModel() {
    final FakeModel model = FakeModel.always(Map.of());
    final DedupePlugin plugin = new DedupePlugin(model, FakeModel.plainResolver());

    final List<Message<?>> out = run(plugin, config(), "hello");

    assertThat(ports(out)).containsExactly("unique");
    assertThat(model.asked).isEmpty();
    assertThat(out.getFirst().metadata().get("dedupe")).isEqualTo(Map.of("duplicate", false));
  }

  @Test
  void dedupe_repeatIsMarkedWithTheMessageItRepeats() {
    final FakeModel model = FakeModel.always(Map.of("same", same("1", 0.9)));
    final DedupePlugin plugin = new DedupePlugin(model, FakeModel.plainResolver());
    final Message<?> first = FakeModel.message("order 5 shipped");
    final Message<?> second = FakeModel.message("5 was shipped");

    final List<Message<?>> out =
        plugin.process(Flux.just(first, second), config()).collectList().block();

    assertThat(ports(out)).containsExactly("unique", "duplicate");
    assertThat(out.get(1).metadata().get("dedupe"))
        .isEqualTo(
            Map.of(
                "duplicate", true, "duplicateOf", String.valueOf(first.id()), "probability", 0.9));
    final Question.Choice question =
        (Question.Choice) model.asked.getFirst().questions().get("same");
    assertThat(question.criteria())
        .containsOnlyKeys("1", "none")
        .containsEntry("1", "Means the same as: order 5 shipped");
    assertThat(model.asked.getFirst().state()).isEqualTo("Current item:\n5 was shipped");
  }

  @Test
  void dedupe_noneUnknownKeyOrLowProbabilityIsUnique() {
    for (final Answer.Choice answer :
        List.of(same("none", 0.99), same("7", 0.99), same("1", 0.3))) {
      final DedupePlugin plugin =
          new DedupePlugin(FakeModel.always(Map.of("same", answer)), FakeModel.plainResolver());

      assertThat(ports(run(plugin, config(), "a", "b"))).containsExactly("unique", "unique");
    }
  }

  @Test
  void dedupe_windowKeepsTheNewestUniqueItemsNewestFirst() {
    final FakeModel model = FakeModel.always(Map.of("same", same("none", 1)));
    final DedupePlugin plugin = new DedupePlugin(model, FakeModel.plainResolver());

    run(plugin, config("window", 2), "a", "b", "c", "d");

    final Question.Choice last = (Question.Choice) model.asked.get(2).questions().get("same");
    assertThat(last.criteria().values())
        .containsExactly(
            "Means the same as: c",
            "Means the same as: b",
            "Means something different from every recent item");
  }

  @Test
  void dedupe_duplicatesAreNotRememberedAndLongItemsAreShortened() {
    final AtomicInteger calls = new AtomicInteger();
    final FakeModel model =
        new FakeModel(
            _ ->
                Mono.just(
                    Map.of("same", calls.incrementAndGet() == 1 ? same("1", 1) : same("none", 1))));
    final DedupePlugin plugin = new DedupePlugin(model, FakeModel.plainResolver());

    run(plugin, config(), "w".repeat(900), "again", "third");

    final Question.Choice third = (Question.Choice) model.asked.get(1).questions().get("same");
    assertThat(third.criteria()).containsOnlyKeys("1", "none");
    assertThat(third.criteria().get("1")).isEqualTo("Means the same as: " + "w".repeat(500));
  }

  @Test
  void dedupe_decideHandlesOneMessage() {
    final DedupePlugin plugin =
        new DedupePlugin(FakeModel.always(Map.of()), FakeModel.plainResolver());

    StepVerifier.create(plugin.decide(FakeModel.message("a"), config()))
        .assertNext(message -> assertThat(message.sourcePort()).isEqualTo("unique"))
        .verifyComplete();
  }

  @Test
  void dedupe_declaresPortsSchemaAndValidation() {
    final DedupePlugin plugin =
        new DedupePlugin(FakeModel.always(Map.of()), FakeModel.plainResolver());

    assertThat(plugin.getType()).isEqualTo("DEDUPE");
    assertThat(plugin.describe().description()).contains("means the same");
    assertThat(plugin.getOutputPorts(config())).containsExactly("unique", "duplicate");
    assertThat(plugin.getConfigSchema().stream().map(ConfigField::name))
        .startsWith("window", "minProbability");
    StepVerifier.create(plugin.validateConfig(config())).verifyComplete();
    StepVerifier.create(plugin.validateConfig(config("window", 0)))
        .expectErrorMessage("window must be 1 or more")
        .verify();
  }

  // --- shared and persisted windows ---

  private static List<Message<?>> runIn(
      final DecisionPlugin plugin,
      final Map<String, Object> config,
      final String session,
      final String execution,
      final String node,
      final String... items) {
    return plugin
        .process(Flux.fromArray(items).map(FakeModel::message), config)
        .contextWrite(
            context ->
                context.put("sessionId", session).put("executionId", execution).put("nodeId", node))
        .collectList()
        .block();
  }

  @Test
  void dedupe_nodesWithOneWindowKeyShareTheirWindowInTheExecution() {
    final FakeModel model = FakeModel.always(Map.of("same", same("1", 0.9)));
    final DedupePlugin plugin = new DedupePlugin(model, FakeModel.plainResolver());
    final Map<String, Object> shared = config("persist", "execution", "windowKey", "attempts");

    final List<Message<?>> first = runIn(plugin, shared, "s", "x-share", "attempt1", "boom");
    final List<Message<?>> second = runIn(plugin, shared, "s", "x-share", "attempt2", "bang");
    final List<Message<?>> other = runIn(plugin, shared, "s", "x-other", "attempt2", "bang");

    assertThat(ports(first)).containsExactly("unique");
    assertThat(ports(second)).containsExactly("duplicate");
    assertThat(ports(other)).containsExactly("unique");
  }

  @Test
  void dedupe_persistSession_laterExecutionFlagsEarlierRepeat() {
    final FakeModel model = FakeModel.always(Map.of("same", same("1", 0.9)));
    final DedupePlugin plugin = new DedupePlugin(model, FakeModel.plainResolver());
    final Map<String, Object> persisted = config("persist", "session");

    assertThat(ports(runIn(plugin, persisted, "s-keep", "run-1", "n", "boom")))
        .containsExactly("unique");
    assertThat(ports(runIn(plugin, persisted, "s-keep", "run-2", "n", "bang")))
        .containsExactly("duplicate");
    assertThat(ports(runIn(plugin, persisted, "s-elsewhere", "run-3", "n", "bang")))
        .containsExactly("unique");
  }

  @Test
  void dedupe_persistNone_neverSharesEvenWithOneWindowKey() {
    final FakeModel model = FakeModel.always(Map.of("same", same("1", 0.9)));
    final DedupePlugin plugin = new DedupePlugin(model, FakeModel.plainResolver());
    final Map<String, Object> own = config("windowKey", "attempts");

    assertThat(ports(runIn(plugin, own, "s-none", "x-none", "n1", "boom")))
        .containsExactly("unique");
    assertThat(ports(runIn(plugin, own, "s-none", "x-none", "n2", "bang")))
        .containsExactly("unique");
  }

  @Test
  void anomaly_persistSession_historySurvivesTheExecution() {
    final FakeModel model = FakeModel.always(Map.of("anomaly", new Answer.YesNo(0.9)));
    final AnomalyPlugin plugin = new AnomalyPlugin(model, FakeModel.plainResolver());
    final Map<String, Object> persisted = config("persist", "session", "minHistory", 2);

    assertThat(ports(runIn(plugin, persisted, "s-an", "run-1", "n", "a", "b")))
        .containsExactly("normal", "normal");
    assertThat(ports(runIn(plugin, persisted, "s-an", "run-2", "n", "c")))
        .containsExactly("anomalous");
  }

  @Test
  void windows_concurrentWritersLoseNoEntries() {
    final DecisionWindow<Integer> window = new DecisionWindow<>();

    java.util.stream.IntStream.range(0, 2000).parallel().forEach(i -> window.add(i, 5000));

    assertThat(window.snapshot()).hasSize(2000).doesNotHaveDuplicates();
    window.add(-1, 10);
    assertThat(window.snapshot()).hasSize(10).endsWith(-1);
  }

  @Test
  void windows_persistMustBeNoneExecutionOrSession() {
    final DedupePlugin plugin =
        new DedupePlugin(FakeModel.always(Map.of()), FakeModel.plainResolver());

    StepVerifier.create(plugin.validateConfig(config("persist", "forever")))
        .expectErrorMessage("persist must be none, execution or session")
        .verify();
    StepVerifier.create(plugin.validateConfig(config("persist", "session"))).verifyComplete();
  }
}
