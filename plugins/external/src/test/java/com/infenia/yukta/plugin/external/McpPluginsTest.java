// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.mcp.McpCallResult;
import com.infenia.yukta.plugin.mcp.McpClientGateway;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

/** {@code run.mcp} plugins against {@code fake-mcp-server.py} over stdio, and over HTTP. */
@DisabledOnOs(OS.WINDOWS)
@SuppressWarnings({
  "PMD.TooManyStaticImports",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.ExcessiveImports"
})
class McpPluginsTest {

  private static final String TOOL = "TOOL";

  @TempDir private Path root;

  private ProcessExecutorGateway gateway;
  private VariableResolver resolver;
  private McpClientGateway http;
  private ExternalPlugins source;

  @BeforeEach
  void setUp() {
    gateway = new ProcessExecutorGateway();
    resolver = mock(VariableResolver.class);
    when(resolver.resolve(any(), any()))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0, Object.class)));
    http = mock(McpClientGateway.class);
  }

  @AfterEach
  void tearDown() {
    if (source != null) {
      source.close();
    }
  }

  @Test
  void stdioTool_formComesFromItsSchema_andEachMessageIsOneCall() throws IOException {
    final ProcessorPlugin echo = processor(stdio("echo", "processor"));

    assertThat(echo.getConfigSchema())
        .extracting(ConfigField::name, ConfigField::type, ConfigField::required)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("token", ConfigField.Type.SECRET, true),
            org.assertj.core.groups.Tuple.tuple("text", ConfigField.Type.STRING, true),
            org.assertj.core.groups.Tuple.tuple("n", ConfigField.Type.NUMBER, false),
            org.assertj.core.groups.Tuple.tuple("mode", ConfigField.Type.ENUM, false),
            org.assertj.core.groups.Tuple.tuple("flag", ConfigField.Type.BOOLEAN, false),
            org.assertj.core.groups.Tuple.tuple("items", ConfigField.Type.LIST, false),
            org.assertj.core.groups.Tuple.tuple("opts", ConfigField.Type.MAP, false));
    StepVerifier.create(
            echo.process(Flux.just(input()), Map.of("text", "hi", "undeclared", "kept out")))
        .assertNext(
            out -> {
              assertThat(out.payload()).isEqualTo(Map.of("text", "hi"));
              assertThat(out.sourcePort()).isEqualTo("default");
            })
        .verifyComplete();
  }

  @Test
  void textResult_toolError_rpcError_andTimeout() throws IOException {
    assertOutput("text", "plain");
    assertFails("fail", "Plugin TOOL: tool fail failed: it broke");
    assertFails("rpcerror", "Plugin TOOL: bad call");
    assertFails("slow", "Plugin TOOL: tools/call timed out after PT2S");
  }

  @Test
  void serverThatExits_failsTheCall_andStartsAgainForTheNext() throws IOException {
    final ProcessorPlugin exit = processor(stdio("exit", "processor"));
    StepVerifier.create(exit.process(Flux.just(input()), Map.of()))
        .expectErrorMessage("Plugin TOOL: the MCP server exited")
        .verify(Duration.ofSeconds(10));
    StepVerifier.create(exit.process(Flux.just(input()), Map.of()))
        .expectErrorMessage("Plugin TOOL: the MCP server exited")
        .verify(Duration.ofSeconds(10));
  }

  @Test
  void terminal_callsTheTool() throws IOException {
    final TerminalPlugin text = (TerminalPlugin) only(stdio("text", "terminal"));
    StepVerifier.create(text.consume(Flux.just(input()), Map.of())).verifyComplete();
  }

  @Test
  void missingTool_orServerThatDoesNotStart_failsStartup() throws IOException {
    assertThatThrownBy(() -> stdio("nope", "processor"))
        .hasMessageContaining("the MCP server has no tool 'nope'");
    source.close();
    manifest("{\"command\":[\"no-such-mcp-server\"],\"tool\":\"t\"}", "processor");
    assertThatThrownBy(this::load).hasMessageContaining("the MCP server exited");
  }

  @Test
  void pin_coversTheToolSchema() throws IOException {
    stdio("echo", "processor");
    source.close();
    final Path dir = root.resolve("plugins").resolve(TOOL);
    final String pin =
        McpTools.digest(
            PluginDigest.compute(dir.toAbsolutePath().normalize()),
            JsonMapper.builder().build().readValue(schemaJson(), Map.class));
    assertThat(load(List.of(TOOL + "@" + pin))).hasSize(1);
    source.close();
    assertThatThrownBy(() -> load(List.of(TOOL + "@" + PluginDigest.compute(dir))))
        .hasMessageContaining("pinned");
  }

  @Test
  void closedPlugin_refusesCalls() throws IOException {
    final ProcessorPlugin echo = processor(stdio("echo", "processor"));
    source.close();
    source.close();
    StepVerifier.create(echo.process(Flux.just(input()), Map.of("text", "x")))
        .expectErrorMessage("Plugin TOOL is closed")
        .verify();
  }

  @Test
  void imageTool_runsServerInContainer_andRemovesItOnClose() throws IOException {
    final Path calls = root.resolve("calls.log");
    final Path cli = root.resolve("fake-docker");
    Files.writeString(
        cli,
        "#!/bin/sh\necho \"$*\" >> '"
            + calls
            + "'\ncase \"$1\" in image|rm) exit 0 ;; esac\nexec python3 '"
            + server()
            + "'\n");
    assertThat(cli.toFile().setExecutable(true)).isTrue();
    manifest("{\"image\":\"ghcr.io/acme/mcp:1\",\"tool\":\"text\"}", "processor");
    source =
        new ExternalPlugins(
            root.resolve("plugins"),
            List.of(TOOL),
            gateway,
            resolver,
            1 << 20,
            1000,
            new Containers(cli.toString(), Map.of()),
            http);
    final ProcessorPlugin text = (ProcessorPlugin) only(source.plugins());
    StepVerifier.create(text.process(Flux.just(input()), Map.of()))
        .assertNext(out -> assertThat(out.payload()).isEqualTo("plain"))
        .verifyComplete();
    source.close();
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> Files.readString(calls).contains("rm -f yukta-tool-"));
    assertThat(Files.readString(calls))
        .startsWith("image inspect ghcr.io/acme/mcp:1\nrun --rm -i --name yukta-tool-");
  }

  @Test
  void httpTool_goesThroughTheMcpClientGateway() throws IOException {
    when(http.listTools(any(), any()))
        .thenReturn(
            Mono.just(
                List.of(
                    Map.of("name", "other"),
                    Map.of(
                        "name",
                        "remote",
                        "inputSchema",
                        Map.of("properties", Map.of("q", Map.of("type", "string")))))));
    when(http.call(any(), any()))
        .thenReturn(Mono.just(new McpCallResult(false, "", List.of(), Map.of("ok", 1), null)));
    manifest("{\"url\":\"http://localhost:1/mcp\",\"tool\":\"remote\"}", "processor");
    final ProcessorPlugin remote = (ProcessorPlugin) only(load());

    StepVerifier.create(remote.process(Flux.just(input()), Map.of("q", "x")))
        .assertNext(out -> assertThat(out.payload()).isEqualTo(Map.of("ok", 1)))
        .verifyComplete();
    final ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
    verify(http).call(any(), heartbeat.capture());
    heartbeat.getValue().run();
    source.close();
    manifest("{\"url\":\"http://localhost:1/mcp\",\"tool\":\"absent\"}", "processor");
    assertThatThrownBy(this::load)
        .hasMessageContaining("the MCP server at http://localhost:1/mcp has no tool 'absent'");
  }

  private void assertOutput(final String tool, final Object payload) throws IOException {
    final ProcessorPlugin plugin = processor(stdio(tool, "processor"));
    StepVerifier.create(plugin.process(Flux.just(input()), Map.of()))
        .assertNext(out -> assertThat(out.payload()).isEqualTo(payload))
        .verifyComplete();
    source.close();
  }

  private void assertFails(final String tool, final String message) throws IOException {
    final ProcessorPlugin plugin = processor(stdio(tool, "processor"));
    StepVerifier.create(plugin.process(Flux.just(input()), Map.of()))
        .expectErrorMessage(message)
        .verify(Duration.ofSeconds(10));
    source.close();
  }

  private List<Plugin> stdio(final String tool, final String category) throws IOException {
    manifest(
        "{\"command\":[\"python3\",\"" + server() + "\"],\"tool\":\"" + tool + "\"}", category);
    return load();
  }

  private Path server() throws IOException {
    final Path server = root.resolve("fake-mcp-server.py");
    if (!Files.exists(server)) {
      try (InputStream in = getClass().getResourceAsStream("/fake-mcp-server.py")) {
        Files.copy(in, server);
      }
    }
    return server;
  }

  private void manifest(final String mcp, final String category) throws IOException {
    final Path dir = Files.createDirectories(root.resolve("plugins").resolve(TOOL));
    Files.writeString(
        dir.resolve(ExternalPlugins.MANIFEST),
        "{\"apiVersion\":\"yukta/plugin/v1\",\"type\":\"TOOL\",\"category\":\""
            + category
            + "\",\"description\":\"d\",\"timeout\":\"2s\","
            + "\"config\":[{\"name\":\"token\",\"type\":\"SECRET\",\"required\":true}],"
            + "\"run\":{\"mcp\":"
            + mcp
            + "}}");
  }

  private List<Plugin> load() {
    return load(List.of(TOOL));
  }

  private List<Plugin> load(final List<String> enabled) {
    source =
        new ExternalPlugins(
            root.resolve("plugins"),
            enabled,
            gateway,
            resolver,
            1 << 20,
            1000,
            new Containers("docker", Map.of()),
            http);
    return source.plugins();
  }

  private static String schemaJson() {
    return "{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{"
        + "\"text\":{\"type\":\"string\",\"description\":\"What to echo\"},"
        + "\"n\":{\"type\":\"integer\",\"default\":1},\"mode\":{\"enum\":[\"a\",\"b\"]},"
        + "\"flag\":{\"type\":\"boolean\"},\"items\":{\"type\":\"array\"},"
        + "\"opts\":{\"type\":\"object\"},\"token\":{\"type\":\"string\"},\"odd\":true}}";
  }

  private static ProcessorPlugin processor(final List<Plugin> plugins) {
    return (ProcessorPlugin) only(plugins);
  }

  private static Plugin only(final List<Plugin> plugins) {
    assertThat(plugins).hasSize(1);
    return plugins.get(0);
  }

  private static Message<?> input() {
    return DefaultMessage.create(UUID.randomUUID(), Map.of("in", 1));
  }
}
