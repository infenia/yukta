// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.conformance.ConformanceFixture;
import com.infenia.yukta.plugin.conformance.PluginConformance;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Runs the {@link PluginConformance} checks over every registered plugin. A plugin without a
 * fixture in {@link #FIXTURES} or a reason in {@code conformance-exemptions.properties} fails
 * {@link #everyRegisteredPluginHasFixtureOrExemption}, so a new plugin is checked from day one. To
 * add a fixture, list the type with a minimal config that touches nothing outside the JVM.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@SuppressWarnings({
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.MutableStaticState",
  "PMD.CommentDefaultAccessModifier",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.UnitTestShouldIncludeAssert",
  "PMD.UseConcurrentHashMap",
  "PMD.AvoidDuplicateLiterals"
})
@ExpectedErrorLog(
    sometimes = {
      "ERROR com.infenia.yukta.plugin.core.router.BranchProcessor - ",
      "ERROR com.infenia.yukta.plugin.core.router.SplitterProcessor - ",
      // a cancelled run interrupts PATCH_GUARD's git call in flight
      "dropped error: java.io.IOException: interrupted"
    })
class PluginConformanceSweepTest {

  private static final String H2_URL = "jdbc:h2:mem:conformance;DB_CLOSE_DELAY=-1";

  private static FakeHttp http;

  @TempDir static Path dir;

  @Autowired private PluginRegistry registry;

  /** Payloads for plugins whose config reads fields of the input; the rest get a string. */
  private static final Map<String, List<Object>> INPUTS =
      Map.of(
          "KV_STORE", List.of(Map.of("value", "v")),
          "SPLITTER", List.of(Map.of("items", List.of(1, 2))));

  /** Type to a supplier of its minimal config; suppliers because some need the fake server. */
  private static final Map<String, Supplier<Map<String, Object>>> FIXTURES = fixtures();

  @BeforeAll
  static void startHttp() throws IOException {
    http = new FakeHttp();
    Files.writeString(dir.resolve("a.txt"), "body");
    // PATCH_GUARD judges a git checkout
    for (final List<String> git :
        List.of(
            List.of("init", "-q"),
            List.of("add", "a.txt"),
            List.of(
                "-c",
                "user.name=t",
                "-c",
                "user.email=t@t",
                "-c",
                "commit.gpgsign=false",
                "commit",
                "-q",
                "-m",
                "init"))) {
      final List<String> command = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
      command.addAll(git);
      try {
        new ProcessBuilder(command).inheritIO().start().waitFor();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @AfterAll
  static void stopHttp() {
    http.close();
  }

  @Test
  void everyRegisteredPluginHasFixtureOrExemption() throws IOException {
    final Properties exempt = exemptions();
    final List<String> types = registry.listPlugins().stream().map(Plugin::getType).toList();
    assertThat(types.stream().filter(t -> !FIXTURES.containsKey(t) && !exempt.containsKey(t)))
        .as("plugins with no conformance fixture and no reasoned exemption")
        .isEmpty();
    assertThat(exempt.stringPropertyNames().stream().filter(FIXTURES::containsKey))
        .as("exemptions for types that also have a fixture")
        .isEmpty();
    for (final String type : exempt.stringPropertyNames()) {
      assertThat(exempt.getProperty(type)).as("reason for exempting " + type).isNotBlank();
    }
  }

  @TestFactory
  Stream<DynamicNode> conformance() {
    return registry.listPlugins().stream()
        .filter(p -> FIXTURES.containsKey(p.getType()))
        .sorted(Comparator.comparing(Plugin::getType))
        .map(
            plugin -> {
              final ConformanceFixture fixture = fixture(plugin.getType());
              return org.junit.jupiter.api.DynamicContainer.dynamicContainer(
                  plugin.getType(),
                  PluginConformance.checks(plugin, fixture).stream()
                      .map(c -> DynamicTest.dynamicTest(c.name(), c.body()::run)));
            });
  }

  private static ConformanceFixture fixture(final String type) {
    final Map<String, Object> config = FIXTURES.get(type).get();
    return new ConformanceFixture() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public Map<String, Object> config() {
        return Map.copyOf(config);
      }

      @Override
      public List<Object> inputs() {
        return INPUTS.getOrDefault(type, List.of("conformance"));
      }
    };
  }

  private static Properties exemptions() throws IOException {
    final Properties props = new Properties();
    try (InputStream input =
        PluginConformanceSweepTest.class.getResourceAsStream(
            "/conformance-exemptions.properties")) {
      props.load(input);
    }
    return props;
  }

  private static Map<String, Supplier<Map<String, Object>>> fixtures() {
    final Map<String, Supplier<Map<String, Object>>> fixtures = new HashMap<>();
    fixtures.put("API_TRIGGER", Map::of);
    fixtures.put("CONSTANT_SOURCE", () -> Map.of("variables", Map.of("a", 1)));
    fixtures.put("SCHEDULE_TRIGGER", () -> Map.of("intervalMs", 50, "maxRuns", 2));
    fixtures.put("CONSOLE_TERMINAL", Map::of);
    fixtures.put("OUTCOME_TERMINAL", () -> Map.of("outcome", "SUCCESS"));
    fixtures.put("NOTIFY_TERMINAL", () -> Map.of("url", http.url() + "/hook", "text", "deployed"));
    fixtures.put("AGGREGATE", () -> Map.of("size", 1));
    fixtures.put(
        "BRANCH",
        () ->
            Map.of(
                "mode", "SELECT_KEY", "selector", "payload", "cases", Map.of("conformance", "a")));
    fixtures.put("CONTENT_FILTER", () -> Map.of("mode", "INCLUDE", "paths", List.of("a")));
    fixtures.put("DELAY", () -> Map.of("delayMs", 10));
    fixtures.put("FILTER", () -> Map.of("condition", "true"));
    fixtures.put("MAPPER", () -> Map.of("mode", "PROJECTION", "mapping", Map.of("y", "'v'")));
    fixtures.put("RECIPIENT_LIST", () -> Map.of("mode", "STATIC", "recipients", List.of("p1")));
    fixtures.put("SPLITTER", () -> Map.of("itemsPath", "payload.items"));
    fixtures.put("VALIDATE", () -> Map.of("required", List.of()));
    fixtures.put("KV_STORE", () -> Map.of("op", "put", "key", "k1", "namespace", "conformance"));
    fixtures.put(
        "LOOP_PREDICATE",
        () ->
            Map.of(
                "targetPluginId",
                "MAPPER",
                "targetConfig",
                Map.of("mode", "PROJECTION", "mapping", Map.of("n", "1")),
                "exitCondition",
                "true"));
    fixtures.put(
        "LOOP_STREAM",
        () ->
            Map.of(
                "targetPluginId",
                "MAPPER",
                "targetConfig",
                Map.of("mode", "PROJECTION", "mapping", Map.of("n", "1")),
                "maxIterations",
                1,
                "exitCondition",
                "false"));
    fixtures.put("HTTP_REQUEST", () -> Map.of("url", http.url() + "/ping", "method", "GET"));
    fixtures.put("PROCESS_EXECUTOR", () -> Map.of("command", List.of("echo", "hi")));
    fixtures.put("FILE_WRITE", () -> Map.of("baseDir", dir.toString(), "path", "out/a.txt"));
    fixtures.put("FILE_READ", () -> Map.of("baseDir", dir.toString(), "path", "a.txt"));
    fixtures.put("PATCH_GUARD", () -> Map.of("dir", dir.toString()));
    fixtures.put(
        "SQL_QUERY",
        () -> Map.of("url", H2_URL, "username", "sa", "password", "", "sql", "select 1"));
    fixtures.put("SESSION_MEMORY", () -> Map.of("mode", "append", "assistant", "hi"));
    return Map.copyOf(fixtures);
  }
}
