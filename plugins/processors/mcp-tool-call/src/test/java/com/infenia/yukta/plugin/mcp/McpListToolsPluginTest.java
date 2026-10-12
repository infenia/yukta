// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.util.VariableResolver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyStaticImports",
  "PMD.LawOfDemeter"
})
class McpListToolsPluginTest {

  private final McpClientGateway gateway = mock(McpClientGateway.class);
  private final VariableResolver resolver = mock(VariableResolver.class);
  private McpListToolsPlugin plugin;

  private static final List<Map<String, Object>> TOOLS =
      List.of(
          Map.of("name", "weather", "description", "W", "inputSchema", Map.of()),
          Map.of("name", "search", "description", "S", "inputSchema", Map.of()));

  @BeforeEach
  void setUp() {
    plugin = new McpListToolsPlugin(gateway, resolver);
    when(resolver.resolve(any(), any()))
        .thenAnswer(inv -> Mono.justOrEmpty(inv.<Object>getArgument(0)));
    when(gateway.listTools(any(), any())).thenReturn(Mono.just(TOOLS));
  }

  private Flux<Message<?>> run(final Map<String, Object> config) {
    return plugin.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), "in")), config);
  }

  private static Map<String, Object> config(final Object... extra) {
    final Map<String, Object> config = new java.util.concurrent.ConcurrentHashMap<>();
    config.put("url", "http://localhost/mcp");
    for (int i = 0; i < extra.length; i += 2) {
      config.put((String) extra[i], extra[i + 1]);
    }
    return config;
  }

  @Test
  @SuppressWarnings("unchecked")
  void listsTheServersTools_withHeadersAndToken() {
    StepVerifier.create(
            run(config("headers", Map.of("X-A", "1"), "authToken", "tok", "timeout", 7)))
        .assertNext(
            out -> {
              final Map<String, Object> payload = (Map<String, Object>) out.payload();
              assertThat(payload).containsEntry("count", 2).containsEntry("tools", TOOLS);
            })
        .verifyComplete();

    final ArgumentCaptor<McpCallSpec> spec = ArgumentCaptor.forClass(McpCallSpec.class);
    verify(gateway).listTools(spec.capture(), any());
    assertThat(spec.getValue().url()).isEqualTo("http://localhost/mcp");
    assertThat(spec.getValue().headers())
        .containsEntry("X-A", "1")
        .containsEntry("Authorization", "Bearer tok");
    assertThat(spec.getValue().timeout()).isEqualTo(Duration.ofSeconds(7));
  }

  @Test
  @SuppressWarnings("unchecked")
  void allowedTools_keepOnlyThoseListed() {
    StepVerifier.create(run(config("allowedTools", List.of("search"))))
        .assertNext(
            out -> assertThat(((Map<String, Object>) out.payload()).get("count")).isEqualTo(1))
        .verifyComplete();
    StepVerifier.create(run(config("allowedTools", List.of())))
        .assertNext(
            out -> assertThat(((Map<String, Object>) out.payload()).get("count")).isEqualTo(2))
        .verifyComplete();
  }

  @Test
  void withoutHeadersOrToken_noAuthorizationIsSent() {
    when(resolver.resolve(any(), any())).thenAnswer(inv -> Mono.just(inv.<Object>getArgument(0)));

    StepVerifier.create(run(config())).expectNextCount(1).verifyComplete();

    final ArgumentCaptor<McpCallSpec> spec = ArgumentCaptor.forClass(McpCallSpec.class);
    verify(gateway).listTools(spec.capture(), any());
    assertThat(spec.getValue().headers()).doesNotContainKey("Authorization");
  }

  @Test
  void configIsValidated_andDescribed() {
    StepVerifier.create(plugin.validateConfig(config())).verifyComplete();
    StepVerifier.create(plugin.validateConfig(config("url", "${payload.url}"))).verifyComplete();
    StepVerifier.create(plugin.validateConfig(config("url", "https://secure/mcp")))
        .verifyComplete();
    assertThatThrownBy(() -> plugin.validateConfig(config("url", " ")).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> plugin.validateConfig(Map.of()).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> plugin.validateConfig(config("url", "ftp://x")).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> plugin.validateConfig(config("timeout", 0)).block())
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(plugin.getType()).isEqualTo("MCP_LIST_TOOLS");
    assertThat(plugin.getDefaultTimeout(config("timeout", 10))).isEqualTo(Duration.ofSeconds(15));
    assertThat(plugin.describe().description()).contains("tools");
    assertThat(plugin.getConfigSchema()).extracting("name").contains("url", "allowedTools");
  }
}
