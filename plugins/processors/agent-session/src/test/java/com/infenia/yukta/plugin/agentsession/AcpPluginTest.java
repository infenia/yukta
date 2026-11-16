// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.agent.AgentCapability;
import com.infenia.yukta.plugin.agent.AgentConversationGateway;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentConversationSpec;
import com.infenia.yukta.plugin.agent.AgentProtocol;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.process.ProcessExecutionResult;
import com.infenia.yukta.plugin.process.ProcessExecutionSpec;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import tools.jackson.databind.ObjectMapper;

/** The plugin runs a harness of protocol {@code acp} through the ACP adapter. */
@NoArgsConstructor
class AcpPluginTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final AtomicReference<AgentConversationSpec> opened = new AtomicReference<>();
  private final AtomicReference<ProcessExecutionSpec> started = new AtomicReference<>();

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(final T value) {
    final ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }

  private AgentSessionPlugin plugin() throws java.io.IOException {
    return plugin(value -> value);
  }

  private AgentSessionPlugin plugin(final java.util.function.UnaryOperator<Object> resolved)
      throws java.io.IOException {
    final ProcessExecutorGateway gateway = mock(ProcessExecutorGateway.class);
    // an agent that exits at once: the run fails, which is all this test needs of it
    when(gateway.executeInteractive(any(), any(), any()))
        .thenAnswer(call -> {
          started.set(call.getArgument(0));
          return Mono.just(new ProcessExecutionResult(2, List.of(), List.of(), 1, false, false));
        });
    final VariableResolver resolver = mock(VariableResolver.class);
    when(resolver.resolve(any(), any()))
        .thenAnswer(i -> Mono.just(resolved.apply(i.getArgument(0))));
    return new AgentSessionPlugin(
        gateway,
        resolver,
        new AgentHarnesses(JSON, ""),
        new InMemoryAgentSessionStore(),
        provider(mock(InputGateway.class)),
        provider(
            (AgentConversationGateway)
                spec -> {
                  opened.set(spec);
                  return Mono.just(AgentConversationHandle.none());
                }),
        JSON,
        Duration.ofSeconds(10),
        new PtySettings(true, "yukta-cli", 120, 40));
  }

  private static Message<?> run(final AgentSessionPlugin plugin, final Map<String, Object> config) {
    return plugin
        .process(Flux.just(DefaultMessage.create(UUID.randomUUID(), "in")), config)
        .contextWrite(
            Context.of("sessionId", "s", "workflowId", "w", "executionId", "e", "nodeId", "n"))
        .blockFirst(Duration.ofSeconds(10));
  }

  @Test
  void theAntigravityPresetIsAnAcpHarnessWithMessagesAndInterruptButNoSteer()
      throws java.io.IOException {
    run(plugin(), Map.of("harness", "antigravity", "prompt", "p", "approvals", "ASK"));

    assertThat(opened.get().protocol()).isEqualTo(AgentProtocol.ACP);
    assertThat(opened.get().capabilities())
        .containsExactlyInAnyOrder(
            AgentCapability.MESSAGE,
            AgentCapability.INTERRUPT,
            AgentCapability.ASK,
            AgentCapability.RESUME);
    assertThat(started.get().command()).containsExactly("agy", "acp");
  }

  @Test
  void withoutApprovalsTheAcpHarnessDoesNotClaimToAsk() throws java.io.IOException {
    run(plugin(), Map.of("harness", "antigravity", "prompt", "p"));

    assertThat(opened.get().capabilities()).doesNotContain(AgentCapability.ASK);
  }

  @Test
  void terminalRunsItsProgramThroughTheHostWithTheSizeAndTheTerminalCapabilities()
      throws java.io.IOException {
    run(
        plugin(),
        Map.of(
            "harness",
            "terminal",
            "prompt",
            "fix it",
            "command",
            List.of("some-agent", "--task", "{prompt}"),
            "terminalSize",
            Map.of("cols", 90, "rows", 25)));

    assertThat(opened.get().protocol()).isEqualTo(AgentProtocol.PTY);
    assertThat(opened.get().capabilities())
        .containsExactlyInAnyOrder(
            AgentCapability.MESSAGE,
            AgentCapability.STEER,
            AgentCapability.INTERRUPT,
            AgentCapability.TERMINAL);
    // the allow-list sees the program; the host only launches it
    assertThat(started.get().command()).containsExactly("some-agent", "--task", "fix it");
    assertThat(started.get().launcher())
        .containsExactly("yukta-cli", "pty-host", "--cols", "90", "--rows", "25", "--");
  }

  @Test
  void anArgumentMayBeAnExpressionAndAPlainOneStaysAsItIs() throws java.io.IOException {
    run(
        plugin(v -> "${start.budget}".equals(v) ? "3" : v),
        Map.of(
            "harness",
            "claude-code",
            "prompt",
            "p",
            "args",
            List.of("--max-budget-usd", "${start.budget}", "--permission-mode", "auto")));

    assertThat(started.get().command())
        .endsWith("--max-budget-usd", "3", "--permission-mode", "auto");
  }
}
