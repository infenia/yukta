// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.conformance;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The common bar for every plugin: a schema that matches its config, a clear error for bad config,
 * a happy-path run, cancellation that reaches the input and frees threads, a large message handled
 * or rejected cleanly, upstream errors that surface as signals, and no secret in the logs.
 *
 * <p>{@link #checks} returns the named checks so a test can report each one on its own; a check
 * throws {@link AssertionError} when the plugin falls short.
 */
@SuppressWarnings({
  "PMD.GodClass",
  "PMD.TooManyMethods",
  "PMD.FieldDeclarationsShouldBeAtStartOfClass",
  "PMD.UseConcurrentHashMap",
  "PMD.AvoidCatchingGenericException",
})
public final class PluginConformance {

  /**
   * A named check of one plugin.
   *
   * @param name what is checked
   * @param body throws {@link AssertionError} when the plugin falls short
   */
  public record Check(String name, Runnable body) {}

  private static final Duration WAIT = Duration.ofSeconds(15);
  private static final int CYCLES = 20;
  private static final int THREAD_SLACK = 5;
  private static final int LARGE_BYTES = 10 * 1024 * 1024;
  private static final String CANARY = "conformance-secret-canary-7f3a";

  private PluginConformance() {}

  /**
   * The checks that apply to {@code plugin}.
   *
   * @param plugin the plugin under test
   * @param fixture how to run it hermetically
   * @return the checks, in the order they should run
   */
  public static List<Check> checks(final Plugin plugin, final ConformanceFixture fixture) {
    final List<Check> checks = new ArrayList<>();
    checks.add(new Check("schema", () -> schema(plugin, fixture)));
    checks.add(new Check("badConfigIsAClearError", () -> badConfig(plugin, fixture)));
    checks.add(new Check("minimalConfigRuns", () -> happyPath(plugin, fixture)));
    checks.add(new Check("cancelAndRestartLeakNothing", () -> cancellation(plugin, fixture)));
    checks.add(new Check("noSecretInLogs", () -> noSecretInLogs(plugin, fixture)));
    if (!(plugin instanceof TriggerPlugin)) {
      checks.add(new Check("largeMessage", () -> largeMessage(plugin, fixture)));
      checks.add(new Check("upstreamErrorIsASignal", () -> upstreamError(plugin, fixture)));
    }
    return checks;
  }

  private static void schema(final Plugin plugin, final ConformanceFixture fixture) {
    final Set<String> seen = new HashSet<>();
    for (final ConfigField field : plugin.getConfigSchema()) {
      require(field.name() != null && !field.name().isBlank(), "a schema field has no name");
      require(seen.add(field.name()), "schema field declared twice: " + field.name());
      require(field.type() != null, "schema field " + field.name() + " has no type");
      require(
          !field.required() || fixture.config().containsKey(field.name()),
          "fixture config lacks required field " + field.name());
    }
    for (final String key : fixture.config().keySet()) {
      require(
          seen.isEmpty() || seen.contains(key),
          "fixture config key " + key + " is not in the schema");
    }
  }

  private static void badConfig(final Plugin plugin, final ConformanceFixture fixture) {
    final Map<String, Object> unknown = new HashMap<>(fixture.config());
    unknown.put("noSuchOption", "x");
    final Map<String, Map<String, Object>> cases = new LinkedHashMap<>();
    // no null case: a Node turns a missing config into an empty map before any plugin sees it
    cases.put("empty config", Map.of());
    cases.put("unknown field", unknown);
    cases.forEach(
        (label, config) -> {
          final Throwable error = validationError(plugin, config);
          require(
              error == null || isClearError(error),
              "validateConfig(" + label + ") failed with " + error);
        });
  }

  private static boolean isClearError(final Throwable error) {
    return !(error instanceof NullPointerException
        || error instanceof ClassCastException
        || error instanceof IndexOutOfBoundsException
        || error instanceof Error);
  }

  private static Throwable validationError(final Plugin plugin, final Map<String, Object> config) {
    try {
      plugin.validateConfig(config).block(WAIT);
      return null;
    } catch (RuntimeException e) {
      return unwrap(e);
    }
  }

  private static void happyPath(final Plugin plugin, final ConformanceFixture fixture) {
    final Map<String, Object> config = fixture.config();
    plugin.validateConfig(config).block(WAIT);
    runLifecycle(plugin, config, p -> output(p, config, messages(fixture.inputs())), true);
  }

  private static void cancellation(final Plugin plugin, final ConformanceFixture fixture) {
    final Map<String, Object> config = fixture.config();
    final AtomicBoolean upstreamCancelled = new AtomicBoolean();
    cycle(plugin, config, Flux.<Message<?>>never().doOnCancel(() -> upstreamCancelled.set(true)));
    if (!(plugin instanceof TriggerPlugin)) {
      await(upstreamCancelled, "the plugin did not cancel its input when cancelled");
    }
    final int before = threads();
    for (int i = 0; i < CYCLES; i++) {
      cycle(plugin, config, Flux.never());
    }
    int after = threads();
    final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (after > before + THREAD_SLACK && System.nanoTime() < deadline) {
      pause(100);
      after = threads();
    }
    require(
        after <= before + THREAD_SLACK,
        "threads grew from " + before + " to " + after + " over " + CYCLES + " start/cancel runs");
  }

  private static void cycle(
      final Plugin plugin, final Map<String, Object> config, final Flux<Message<?>> input) {
    final Plugin node = plugin.forNode(config);
    node.initialize(config).block(WAIT);
    node.prepare(config).block(WAIT);
    final Disposable run = output(node, config, input).subscribe(m -> {}, e -> {});
    pause(50);
    run.dispose();
    node.shutdown(config).block(WAIT);
  }

  private static void noSecretInLogs(final Plugin plugin, final ConformanceFixture fixture) {
    final Map<String, Object> config = new HashMap<>(fixture.config());
    for (final ConfigField field : plugin.getConfigSchema()) {
      if (field.type() == ConfigField.Type.SECRET) {
        config.put(field.name(), CANARY);
      }
    }
    final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      plugin.validateConfig(config).onErrorResume(e -> Mono.empty()).block(WAIT);
      runLifecycle(plugin, config, p -> output(p, config, messages(fixture.inputs())), false);
    } finally {
      root.detachAppender(appender);
    }
    for (final ILoggingEvent event : List.copyOf(appender.list)) {
      require(
          !event.getFormattedMessage().contains(CANARY)
              && !(event.getThrowableProxy() != null
                  && String.valueOf(event.getThrowableProxy().getMessage()).contains(CANARY)),
          "a secret config value was logged: " + event.getFormattedMessage());
    }
  }

  private static void largeMessage(final Plugin plugin, final ConformanceFixture fixture) {
    final Map<String, Object> config = fixture.config();
    final Message<?> big = message("x".repeat(LARGE_BYTES));
    try {
      runLifecycle(plugin, config, p -> output(p, config, Flux.just(big)), true);
    } catch (RuntimeException e) {
      require(isClearError(unwrap(e)), "a 10 MB message failed with " + unwrap(e));
    }
  }

  private static void upstreamError(final Plugin plugin, final ConformanceFixture fixture) {
    final Map<String, Object> config = fixture.config();
    final Flux<Message<?>> input =
        Flux.concat(
            messages(fixture.inputs()),
            Flux.<Message<?>>error(new IllegalStateException("upstream failed")));
    runLifecycle(plugin, config, p -> output(p, config, input), false);
  }

  private static Throwable unwrap(final Throwable error) {
    return error.getCause() != null && error.getClass().getName().startsWith("reactor.")
        ? error.getCause()
        : error;
  }

  /** Runs initialize, prepare, the plugin, shutdown; shutdown happens even if the run fails. */
  private static void runLifecycle(
      final Plugin plugin,
      final Map<String, Object> config,
      final Function<Plugin, Flux<Message<?>>> run,
      final boolean mustSucceed) {
    final Plugin node = plugin.forNode(config);
    node.initialize(config).block(WAIT);
    node.prepare(config).block(WAIT);
    try {
      final Flux<Message<?>> out = run.apply(node);
      if (node instanceof TriggerPlugin) {
        // a trigger may wait for outside input, so it need not emit; it only must not fail
        out.take(Duration.ofMillis(300)).collectList().block(WAIT);
      } else if (mustSucceed) {
        out.collectList().block(WAIT);
      } else {
        out.collectList().onErrorResume(e -> Mono.empty()).block(WAIT);
      }
    } finally {
      node.shutdown(config).block(WAIT);
    }
  }

  /** The plugin's output as a Flux, whatever its category (a terminal yields nothing). */
  private static Flux<Message<?>> output(
      final Plugin plugin, final Map<String, Object> config, final Flux<Message<?>> input) {
    if (plugin instanceof ProcessorPlugin p) {
      return p.process(input, config);
    }
    if (plugin instanceof TerminalPlugin t) {
      return t.consume(input, config).thenMany(Flux.empty());
    }
    if (plugin instanceof TriggerPlugin t) {
      return t.start(config);
    }
    throw new AssertionError("unknown plugin category for " + plugin.getType());
  }

  private static Flux<Message<?>> messages(final List<Object> payloads) {
    return Flux.fromIterable(payloads).map(PluginConformance::message);
  }

  private static Message<?> message(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  private static int threads() {
    return ManagementFactory.getThreadMXBean().getThreadCount();
  }

  private static void await(final AtomicBoolean flag, final String failure) {
    final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!flag.get() && System.nanoTime() < deadline) {
      pause(50);
    }
    require(flag.get(), failure);
  }

  private static void pause(final long millis) {
    try {
      // fixture has no test-dependency on Awaitility; polls and settles
      Thread.sleep(millis); // nosem: yukta-test-thread-sleep
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted", e);
    }
  }

  private static void require(final boolean condition, final String failure) {
    if (!condition) {
      throw new AssertionError(failure);
    }
  }
}
