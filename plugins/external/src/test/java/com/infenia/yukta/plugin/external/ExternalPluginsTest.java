// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.core.PluginTier;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.mcp.McpClientGateway;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Loads community plugins from a temporary plugins directory and runs them as the engine would. */
@DisabledOnOs(OS.WINDOWS)
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports",
  "PMD.TooManyStaticImports",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.UseConcurrentHashMap"
})
class ExternalPluginsTest {

  private static final String ECHO = "ECHO";
  private static final String RUN_SH = "run.sh";
  private static final Containers NO_CONTAINERS = new Containers("docker", Map.of());
  private static final McpClientGateway NO_HTTP = mock(McpClientGateway.class);

  /** Writes the whole stdin envelope back as the payload, and one line to stderr. */
  private static final String ECHO_SCRIPT =
      "printf '{\"payload\":'; cat; printf '}\\n'; echo 'plugin says hi' >&2\n";

  @TempDir private Path root;

  private ProcessExecutorGateway gateway;
  private VariableResolver resolver;

  @BeforeEach
  void setUp() {
    gateway = new ProcessExecutorGateway();
    resolver = mock(VariableResolver.class);
    when(resolver.resolve(any(), any()))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0, Object.class)));
  }

  @Test
  void processor_getsTheEnvelopeOnStdin_andItsStderrGoesToTheLog() throws IOException {
    plugin(ECHO, "processor", ECHO_SCRIPT, ",\"ports\":[\"default\",\"other\"]");
    final ProcessorPlugin echo = (ProcessorPlugin) only(load(List.of(ECHO)));
    final List<String> log = new CopyOnWriteArrayList<>();
    final PluginLogSink sink = (stream, line, level) -> log.add(stream + ":" + line);

    StepVerifier.create(
            echo.process(
                    Flux.just(input(Map.of("text", "hi"))),
                    Map.of("greeting", "hello", "undeclared", "kept out"))
                .contextWrite(ctx -> ctx.put(WorkflowContext.LOG_SINK_KEY, sink)))
        .assertNext(
            out -> {
              @SuppressWarnings("unchecked")
              final Map<String, Object> envelope = (Map<String, Object>) out.payload();
              assertThat(envelope)
                  .containsEntry("apiVersion", "yukta/message/v1")
                  .containsEntry("config", Map.of("greeting", "hello"));
              assertThat(envelope.get("message").toString()).contains("text=hi");
              assertThat(out.sourcePort()).isEqualTo("default");
            })
        .verifyComplete();
    assertThat(log).containsExactly("stderr:plugin says hi");
  }

  @Test
  void outputs_goToTheirPort_withTheirMetadata() throws IOException {
    plugin(
        ECHO,
        "processor",
        "echo '{\"payload\":1,\"port\":\"other\",\"metadata\":{\"k\":\"v\"}}'\necho\n"
            + "echo '{\"payload\":2}'\n",
        ",\"ports\":[\"default\",\"other\"]");
    final ProcessorPlugin echo = (ProcessorPlugin) only(load(List.of(ECHO)));

    StepVerifier.create(echo.process(Flux.just(input("x")), Map.of()))
        .assertNext(
            out -> {
              assertThat(out.payload()).isEqualTo(1);
              assertThat(out.sourcePort()).isEqualTo("other");
              assertThat(out.metadata()).containsEntry("k", "v");
            })
        .assertNext(out -> assertThat(out.sourcePort()).isEqualTo("default"))
        .verifyComplete();
  }

  @Test
  void nonZeroExit_failsTheMessage() throws IOException {
    plugin(ECHO, "processor", "echo boom >&2; exit 3\n", "");
    final ProcessorPlugin echo = (ProcessorPlugin) only(load(List.of(ECHO)));

    StepVerifier.create(echo.process(Flux.just(input("x")), Map.of()))
        .expectErrorMessage("Plugin ECHO exited with code 3")
        .verify();
  }

  @Test
  void invalidOutput_failsTheMessage() throws IOException {
    assertFails("echo not json\n", "wrote a line that is not JSON");
    assertFails("echo '{\"payload\":1,\"traceId\":\"forged\"}'\n", "wrote an invalid output");
    assertFails("echo '{\"payload\":1,\"port\":\"nowhere\"}'\n", "wrote to port 'nowhere'");
  }

  @Test
  void tooManyOutputs_failTheMessage() throws IOException {
    plugin(ECHO, "processor", "echo '{\"payload\":1}'; echo '{\"payload\":2}'\n", "");
    final ProcessorPlugin echo =
        (ProcessorPlugin)
            only(
                new ExternalPlugins(
                        root, List.of(ECHO), gateway, resolver, 1024, 1, NO_CONTAINERS, NO_HTTP)
                    .plugins());

    StepVerifier.create(echo.process(Flux.just(input("x")), Map.of()))
        .expectErrorMessage("Plugin ECHO wrote 2 outputs, more than 1")
        .verify();
  }

  @Test
  void terminal_runsOncePerMessage_andIgnoresStdout() throws IOException {
    plugin("SINK", "terminal", "cat >> seen.txt; echo >> seen.txt; echo ignored\n", "");
    final TerminalPlugin sink = (TerminalPlugin) only(load(List.of("SINK")));

    StepVerifier.create(sink.consume(Flux.just(input("a"), input("b")), Map.of())).verifyComplete();
    assertThat(Files.readAllLines(root.resolve("SINK/seen.txt"))).hasSize(2);
  }

  @Test
  void pluginNotNamedInTheEnableList_isNotLoadedEvenWithAll() throws IOException {
    plugin(ECHO, "processor", ECHO_SCRIPT, "");

    assertThat(load(List.of("all", "OTHER"))).isEmpty();
  }

  @Test
  void pinnedDigest_mustMatch() throws IOException {
    plugin(ECHO, "processor", ECHO_SCRIPT, "");
    final String digest = PluginDigest.compute(root.resolve(ECHO));

    assertThat(load(List.of(ECHO + "@" + digest))).hasSize(1);
    assertThatThrownBy(() -> load(List.of(ECHO + "@sha256:" + "0".repeat(64))))
        .hasMessageContaining("has digest " + digest);
    assertThatThrownBy(() -> load(List.of("MISSING@" + digest)))
        .hasMessageContaining("no plugin in");
  }

  @Test
  void enabledInvalidManifest_failsStartup() throws IOException {
    plugin(ECHO, "processor", ECHO_SCRIPT, ",\"timout\":\"30s\"");

    assertThatThrownBy(() -> load(List.of(ECHO))).hasMessageContaining("is invalid");
  }

  @Test
  void executableOutsideThePluginDirectory_isRefused() throws IOException {
    final Path dir = Files.createDirectories(root.resolve("ESC"));
    Files.writeString(
        dir.resolve(ExternalPlugins.MANIFEST),
        "{\"apiVersion\":\"yukta/plugin/v1\",\"type\":\"ESC\",\"category\":\"processor\","
            + "\"description\":\"d\",\"run\":{\"command\":[\"../ESC2/run\"]}}");

    assertThatThrownBy(() -> load(List.of("ESC"))).hasMessageContaining("is not a file inside");
  }

  @Test
  void symbolicLinkInThePluginDirectory_isRefused() throws IOException {
    plugin(ECHO, "processor", ECHO_SCRIPT, "");
    Files.createSymbolicLink(root.resolve(ECHO + "/secrets"), Path.of(".."));

    assertThatThrownBy(() -> load(List.of(ECHO))).hasMessageContaining("symbolic link");
  }

  @Test
  void requiredField_isChecked() throws IOException {
    plugin(ECHO, "processor", ECHO_SCRIPT, "");
    final Plugin echo = only(load(List.of(ECHO)));

    StepVerifier.create(echo.validateConfig(Map.of()))
        .expectErrorMessage("ECHO: required config 'greeting' is missing")
        .verify();
    StepVerifier.create(echo.validateConfig(Map.of("greeting", "x"))).verifyComplete();
  }

  @Test
  void digest_isTheHashOfTheSortedFileLines() throws IOException, NoSuchAlgorithmException {
    final Path dir = Files.createDirectories(root.resolve("D"));
    Files.writeString(dir.resolve("b.txt"), "two");
    Files.createDirectories(dir.resolve("a"));
    Files.writeString(dir.resolve("a/x.txt"), "one");

    final String lines = "a/x.txt\0" + sha("one") + "\nb.txt\0" + sha("two") + "\n";
    assertThat(PluginDigest.compute(dir)).isEqualTo("sha256:" + sha(lines));
  }

  @Test
  void executableInsideThePluginDirectory_runsByItsPath() throws IOException {
    plugin(ECHO, "processor", "", "");
    final Path dir = root.resolve(ECHO);
    Files.writeString(dir.resolve("run"), "#!/bin/sh\ncat > /dev/null; echo '{\"payload\":7}'\n");
    assertThat(dir.resolve("run").toFile().setExecutable(true)).isTrue();
    Files.writeString(
        dir.resolve(ExternalPlugins.MANIFEST),
        Files.readString(dir.resolve(ExternalPlugins.MANIFEST))
            .replace("[\"sh\",\"run.sh\"]", "[\"./run\"]"));
    final ProcessorPlugin echo = (ProcessorPlugin) only(load(List.of(ECHO)));

    StepVerifier.create(echo.process(Flux.just(input("x")), Map.of()))
        .assertNext(out -> assertThat(out.payload()).isEqualTo(7))
        .verifyComplete();
  }

  @Test
  void missingExecutableInsideThePluginDirectory_isRefused() throws IOException {
    plugin(ECHO, "processor", "", "");
    final Path manifest = root.resolve(ECHO).resolve(ExternalPlugins.MANIFEST);
    Files.writeString(
        manifest, Files.readString(manifest).replace("[\"sh\",\"run.sh\"]", "[\"./gone\"]"));

    assertThatThrownBy(() -> load(List.of(ECHO))).hasMessageContaining("is not a file inside");
  }

  @Test
  void timeoutAndOversizedOutput_failTheMessage() throws IOException {
    plugin(ECHO, "processor", "sleep 5\n", "");
    final Path manifest = root.resolve(ECHO).resolve(ExternalPlugins.MANIFEST);
    Files.writeString(manifest, Files.readString(manifest).replace("\"10s\"", "\"500ms\""));
    final ProcessorPlugin slow = (ProcessorPlugin) only(load(List.of(ECHO)));
    StepVerifier.create(slow.process(Flux.just(input("x")), Map.of()))
        .expectErrorMessage("Plugin ECHO timed out after PT0.5S")
        .verify();

    plugin("BIG", "processor", "printf '%0200d' 0\n", "");
    final ProcessorPlugin big =
        (ProcessorPlugin)
            only(
                new ExternalPlugins(
                        root, List.of("BIG"), gateway, resolver, 50, 10, NO_CONTAINERS, NO_HTTP)
                    .plugins());
    StepVerifier.create(big.process(Flux.just(input("x")), Map.of()))
        .expectErrorMessage("Plugin BIG wrote more than 50 bytes")
        .verify();
  }

  @Test
  void messageWithoutTraceId_isSentWithoutOne() throws IOException {
    plugin(ECHO, "processor", ECHO_SCRIPT, "");
    final ProcessorPlugin echo = (ProcessorPlugin) only(load(List.of(ECHO)));

    StepVerifier.create(echo.process(Flux.just(input("x").withTraceId(null)), Map.of()))
        .assertNext(out -> assertThat(out.payload().toString()).doesNotContain("traceId"))
        .verifyComplete();
  }

  @Test
  void adapter_reportsItsManifest() throws IOException {
    plugin(ECHO, "processor", ECHO_SCRIPT, "");
    plugin("SINK", "terminal", ECHO_SCRIPT, "");
    final List<Plugin> plugins = load(List.of(ECHO, "SINK"));
    final Plugin echo = plugins.stream().filter(p -> ECHO.equals(p.getType())).findFirst().get();
    final Plugin sink = plugins.stream().filter(p -> "SINK".equals(p.getType())).findFirst().get();

    assertThat(echo.getCategory()).isEqualTo(PluginCategory.PROCESSOR);
    assertThat(echo.describe().description()).isEqualTo("test plugin");
    assertThat(echo.describe().usagePattern()).contains(ECHO);
    assertThat(echo.getConfigSchema())
        .extracting(ConfigField::name)
        .containsExactly("greeting", "tone");
    assertThat(echo.getOutputPorts()).containsExactly("default");
    assertThat(echo.getDefaultTimeout()).isEqualTo(Duration.ofSeconds(15));
    assertThat(echo.getTier()).isEqualTo(PluginTier.SPECIFIC);
    assertThat(echo.isRestricted()).isTrue();
    assertThat(sink.getCategory()).isEqualTo(PluginCategory.TERMINAL);
    assertThat(sink.getOutputPorts()).isEmpty();
    StepVerifier.create(echo.validateConfig(null))
        .expectErrorMessage("ECHO: required config 'greeting' is missing")
        .verify();
  }

  @Test
  void manifest_readsEveryDurationAndOptionalField() {
    final Map<String, Object> json =
        Map.of(
            "type", "T",
            "category", "processor",
            "description", "d",
            "config", List.of(Map.of("name", "n", "type", "STRING")),
            "run", Map.of("command", List.of("x")));
    final PluginManifest plain = PluginManifest.from(json, root);
    assertThat(plain.timeout()).isEqualTo(PluginManifest.DEFAULT_TIMEOUT);
    assertThat(plain.config().get(0).required()).isFalse();
    assertThat(plain.config().get(0).options()).isEmpty();
    for (final var d :
        Map.of(
                "250ms", Duration.ofMillis(250),
                "2m", Duration.ofMinutes(2),
                "1h", Duration.ofHours(1))
            .entrySet()) {
      final Map<String, Object> timed = new java.util.HashMap<>(json);
      timed.put("timeout", d.getKey());
      assertThat(PluginManifest.from(timed, root).timeout()).isEqualTo(d.getValue());
    }
  }

  @Test
  void pluginsDirectory_problems() throws IOException {
    assertThat(
            new ExternalPlugins(
                    null, List.of(ECHO), gateway, resolver, 1, 1, NO_CONTAINERS, NO_HTTP)
                .plugins())
        .isEmpty();
    assertThatThrownBy(() -> sourceAt(root.resolve("missing")).plugins())
        .hasMessageContaining("is not a directory");
    assertThat(sourceAt(root)).hasToString("yukta.plugins.dir " + root);

    plugin(ECHO, "processor", ECHO_SCRIPT, "");
    Files.createDirectories(root.resolve("twin"));
    Files.copy(
        root.resolve(ECHO).resolve(ExternalPlugins.MANIFEST),
        root.resolve("twin").resolve(ExternalPlugins.MANIFEST));
    assertThatThrownBy(() -> load(List.of(ECHO))).hasMessageContaining("is in both");

    Files.writeString(root.resolve("twin").resolve(ExternalPlugins.MANIFEST), "[1, 2]");
    assertThat(load(List.of(ECHO))).hasSize(1);
    Files.writeString(root.resolve("twin").resolve(ExternalPlugins.MANIFEST), "{");
    assertThatThrownBy(() -> load(List.of(ECHO))).hasMessageContaining("Cannot read");
  }

  @Test
  void readOnlyPluginsDirectory_loads() throws IOException {
    plugin(ECHO, "processor", ECHO_SCRIPT, "");
    assertThat(root.toFile().setWritable(false)).isTrue();
    try {
      assertThat(load(List.of(ECHO))).hasSize(1);
    } finally {
      assertThat(root.toFile().setWritable(true)).isTrue();
    }
  }

  @Test
  void digest_failsOnFilesItCannotRead() throws IOException {
    assertThatThrownBy(() -> PluginDigest.compute(root.resolve("missing")))
        .isInstanceOf(java.io.UncheckedIOException.class);
    final Path secret = Files.writeString(root.resolve("secret"), "x");
    assertThat(secret.toFile().setReadable(false)).isTrue();
    try {
      assertThatThrownBy(() -> PluginDigest.compute(root))
          .isInstanceOf(java.io.UncheckedIOException.class);
    } finally {
      assertThat(secret.toFile().setReadable(true)).isTrue();
    }
  }

  @Test
  void autoConfiguration_buildsTheSource() {
    final ExternalPluginsAutoConfiguration config = new ExternalPluginsAutoConfiguration();
    assertThat(
            config
                .externalPlugins(
                    "",
                    List.of(),
                    1,
                    1,
                    "docker",
                    new StandardEnvironment(),
                    gateway,
                    mock(McpClientGateway.class),
                    resolver)
                .plugins())
        .isEmpty();
    assertThat(
            config.externalPlugins(
                root.toString(),
                List.of(),
                1,
                1,
                "docker",
                new StandardEnvironment(),
                gateway,
                mock(McpClientGateway.class),
                resolver))
        .hasToString("yukta.plugins.dir " + root);
  }

  @Test
  void digest_failsForAnUnknownAlgorithm() {
    assertThatThrownBy(() -> PluginDigest.digest("NOPE")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void unreadablePluginsDirectory_failsStartup() throws IOException {
    plugin(ECHO, "processor", ECHO_SCRIPT, "");
    assertThat(root.toFile().setReadable(false)).isTrue();
    try {
      assertThatThrownBy(() -> load(List.of(ECHO)))
          .isInstanceOf(java.io.UncheckedIOException.class);
    } finally {
      assertThat(root.toFile().setReadable(true)).isTrue();
    }
  }

  private ExternalPlugins sourceAt(final Path dir) {
    return new ExternalPlugins(dir, List.of(), gateway, resolver, 1, 1, NO_CONTAINERS, NO_HTTP);
  }

  private void assertFails(final String script, final String message) throws IOException {
    plugin(ECHO, "processor", script, "");
    final ProcessorPlugin echo = (ProcessorPlugin) only(load(List.of(ECHO)));
    StepVerifier.create(echo.process(Flux.just(input("x")), Map.of()))
        .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining(message))
        .verify();
  }

  private void plugin(
      final String type, final String category, final String script, final String extra)
      throws IOException {
    final Path dir = Files.createDirectories(root.resolve(type));
    Files.writeString(dir.resolve(RUN_SH), script);
    Files.writeString(
        dir.resolve(ExternalPlugins.MANIFEST),
        "{\"apiVersion\":\"yukta/plugin/v1\",\"type\":\""
            + type
            + "\",\"category\":\""
            + category
            + "\",\"description\":\"test plugin\",\"timeout\":\"10s\","
            + "\"config\":[{\"name\":\"greeting\",\"type\":\"STRING\",\"required\":true},"
            + "{\"name\":\"tone\",\"type\":\"STRING\"}],"
            + "\"run\":{\"command\":[\"sh\",\""
            + RUN_SH
            + "\"]}"
            + extra
            + "}");
  }

  private List<Plugin> load(final List<String> enabled) {
    return new ExternalPlugins(
            root, enabled, gateway, resolver, 1 << 20, 1000, NO_CONTAINERS, NO_HTTP)
        .plugins();
  }

  private static Plugin only(final List<Plugin> plugins) {
    assertThat(plugins).hasSize(1);
    return plugins.get(0);
  }

  private static Message<?> input(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  private static String sha(final String text) throws NoSuchAlgorithmException {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }
}
