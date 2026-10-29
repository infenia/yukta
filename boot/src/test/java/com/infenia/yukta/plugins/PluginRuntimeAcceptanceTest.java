// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * What a plugin does while it runs, for every plugin that can run without the outside world: a
 * cancel reaches the upstream, a 10 MB message is handled or refused cleanly, and initialize and
 * shutdown can repeat. A plugin needs a fixture in {@link #FIXTURES} or a reasoned entry in {@link
 * #EXEMPT}; one that has neither fails the first test, so a new plugin is checked from its first
 * day. {@code PluginConformanceAcceptanceTest} checks the static contract.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidCatchingGenericException",
  "PMD.AvoidInstantiatingObjectsInLoops"
})
@NoArgsConstructor
class PluginRuntimeAcceptanceTest {

  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final int BIG = 10_000_000;

  /** Type to a valid config; the input is {@link #payload()} for every fixture. */
  private static final Map<String, Map<String, Object>> FIXTURES =
      Map.ofEntries(
          Map.entry("AGGREGATE", Map.of("size", 2)),
          Map.entry("CONTENT_FILTER", Map.of("mode", "INCLUDE", "paths", List.of("user.name"))),
          Map.entry("DELAY", Map.of("delayMs", 10)),
          Map.entry("FILTER", Map.of("condition", "true")),
          Map.entry("MAPPER", Map.of("mode", "PROJECTION", "mapping", Map.of("y", "payload.x"))),
          Map.entry("SPLITTER", Map.of("itemsPath", "payload.items")),
          Map.entry("PROCESS_EXECUTOR", Map.of("command", List.of("echo", "hi"))),
          Map.entry("CONSOLE_TERMINAL", Map.of()),
          Map.entry("API_TRIGGER", Map.of()),
          Map.entry("CONSTANT_SOURCE", Map.of()),
          Map.entry("KV_STORE", Map.of("op", "put", "key", "k", "namespace", "runtime")),
          Map.entry("OUTCOME_TERMINAL", Map.of("outcome", "SUCCESS")),
          Map.entry("SCHEDULE_TRIGGER", Map.of("intervalMs", 1000)));

  /** Type to why it has no fixture. */
  private static final Map<String, String> EXEMPT =
      Map.ofEntries(
          Map.entry("ANOMALY", "needs a model (LLM or classifier) to run"),
          Map.entry("ASK", "needs an LLM"),
          Map.entry("CLASSIFY", "needs an LLM"),
          Map.entry("DEDUPE", "needs a model to compare messages"),
          Map.entry("GATE", "needs an LLM"),
          Map.entry("SCORE", "needs an LLM"),
          Map.entry("LLM_CALL", "calls an LLM endpoint"),
          Map.entry("HTTP_REQUEST", "calls a URL"),
          Map.entry("NOTIFY_TERMINAL", "posts to a URL"),
          Map.entry("MCP_LIST_TOOLS", "talks to an MCP server"),
          Map.entry("MCP_TOOL_CALL", "talks to an MCP server"),
          Map.entry("SQL_QUERY", "needs a database"),
          Map.entry("SUB_WORKFLOW", "runs another workflow of a session"),
          Map.entry("LOOP_PREDICATE", "drives another plugin of a running workflow"),
          Map.entry("LOOP_STREAM", "drives another plugin of a running workflow"),
          Map.entry("WAIT_FOR_SIGNAL", "waits for a signal of a running execution"),
          Map.entry("SESSION_MEMORY", "needs the history store of a session"),
          Map.entry(
              "FILE_READ", "needs a directory and a file; covered by IoPluginsAcceptanceTest"),
          Map.entry("FILE_WRITE", "needs a directory; covered by IoPluginsAcceptanceTest"),
          Map.entry("PATCH_GUARD", "needs a git checkout; covered by IoPluginsAcceptanceTest"),
          Map.entry("BRANCH", "needs routing cases; covered by CorePluginsAcceptanceTest"),
          Map.entry("RECIPIENT_LIST", "needs the outgoing edges of a workflow"),
          Map.entry("VALIDATE", "needs a schema or rules in its input"));

  @Autowired private PluginRegistry registry;

  private static Map<String, Object> payload() {
    return Map.of("x", 1, "items", List.of(1, 2), "user", Map.of("name", "ada"));
  }

  private static Message<?> message(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  @Test
  void everyRegisteredPlugin_hasFixtureOrReasonedExemption() {
    final Set<String> registered = new TreeSet<>();
    registry.listPlugins().forEach(plugin -> registered.add(plugin.getType()));
    final Set<String> unlisted = new TreeSet<>(registered);
    unlisted.removeAll(FIXTURES.keySet());
    unlisted.removeAll(EXEMPT.keySet());
    final Set<String> stale = new TreeSet<>(FIXTURES.keySet());
    stale.addAll(EXEMPT.keySet());
    stale.removeAll(registered);
    final Set<String> both = new TreeSet<>(FIXTURES.keySet());
    both.retainAll(EXEMPT.keySet());
    assertThat(unlisted).as("plugins with no fixture and no exemption").isEmpty();
    assertThat(stale).as("fixtures or exemptions of plugins that no longer exist").isEmpty();
    assertThat(both).as("plugins listed as both fixture and exempt").isEmpty();
  }

  @Test
  void cancelling_reachesTheUpstream_ofEveryPlugin() {
    final List<String> failures = new ArrayList<>();
    for (final Map.Entry<String, Map<String, Object>> fixture : FIXTURES.entrySet()) {
      final Plugin plugin = registry.get(fixture.getKey());
      final AtomicBoolean cancelled = new AtomicBoolean();
      final Sinks.Many<Message<?>> source = Sinks.many().unicast().onBackpressureBuffer();
      final Flux<Message<?>> input = source.asFlux().doOnCancel(() -> cancelled.set(true));
      final Disposable running;
      if (plugin instanceof ProcessorPlugin processor) {
        running = processor.process(input, fixture.getValue()).subscribe(m -> {}, e -> {});
      } else if (plugin instanceof TerminalPlugin terminal) {
        running = terminal.consume(input, fixture.getValue()).subscribe(v -> {}, e -> {});
      } else {
        final TriggerPlugin trigger = (TriggerPlugin) plugin;
        running = trigger.start(fixture.getValue()).subscribe(m -> {}, e -> {});
        running.dispose();
        if (!running.isDisposed()) {
          failures.add(fixture.getKey() + ": a trigger stays subscribed after a cancel");
        }
        continue;
      }
      source.tryEmitNext(message(payload()));
      running.dispose();
      try {
        await().atMost(Duration.ofSeconds(3)).untilTrue(cancelled);
      } catch (final org.awaitility.core.ConditionTimeoutException e) {
        failures.add(fixture.getKey() + ": a cancel does not reach the upstream");
      }
    }
    assertThat(failures).isEmpty();
  }

  /**
   * A refusal with a validation error is a clean answer to a 10 MB message; a crash or a hang is
   * not.
   */
  private static List<String> problemsOf(final String type, final RuntimeException error) {
    final List<String> problems = new ArrayList<>();
    if (String.valueOf(error.getMessage()).contains("Timeout")) {
      problems.add(type + ": a 10 MB message hangs for " + WAIT);
    } else if (error instanceof NullPointerException
        || error instanceof ClassCastException
        || error instanceof IndexOutOfBoundsException) {
      problems.add(type + ": a 10 MB message crashes with " + error);
    }
    return problems;
  }

  @Test
  void tenMegabyteMessage_isHandledOrRefusedCleanly_byEveryProcessor() {
    final List<String> failures = new ArrayList<>();
    for (final Map.Entry<String, Map<String, Object>> fixture : FIXTURES.entrySet()) {
      if (!(registry.get(fixture.getKey()) instanceof ProcessorPlugin processor)) {
        continue;
      }
      final Map<String, Object> big = Map.of("x", 1, "items", List.of(1), "blob", "x".repeat(BIG));
      try {
        processor.process(Flux.just(message(big)), fixture.getValue()).collectList().block(WAIT);
      } catch (final RuntimeException e) {
        failures.addAll(problemsOf(fixture.getKey(), e));
      }
    }
    assertThat(failures).isEmpty();
  }

  @Test
  void initializeAndShutdown_canRepeat_forEveryPlugin() {
    final List<String> failures = new ArrayList<>();
    for (final Map.Entry<String, Map<String, Object>> fixture : FIXTURES.entrySet()) {
      final Plugin plugin = registry.get(fixture.getKey());
      try {
        for (int i = 0; i < 2; i++) {
          plugin.initialize(fixture.getValue()).block(WAIT);
          plugin.shutdown(fixture.getValue()).block(WAIT);
        }
      } catch (final RuntimeException e) {
        failures.add(fixture.getKey() + ": " + e);
      }
    }
    assertThat(failures).isEmpty();
  }
}
