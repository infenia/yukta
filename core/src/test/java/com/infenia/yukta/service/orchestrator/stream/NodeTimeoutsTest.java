// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.store.SecretProvider;
import com.infenia.yukta.util.VariableResolver;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals", "PMD.LawOfDemeter"})
class NodeTimeoutsTest {

  private final VariableResolver resolver = new VariableResolver(mock(SecretProvider.class));
  private final Plugin plugin = mock(Plugin.class);
  private final VirtualTimeScheduler time = VirtualTimeScheduler.create();
  private final NodeActivity activity = new NodeActivity(time);

  private static Message<?> message(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  private Flux<Message<?>> retime(final Map<String, Object> config, final Message<?> input) {
    return NodeTimeouts.retimeOnFirstInput(
        Flux.just(input), new WorkflowNode("n", "t", config), plugin, resolver, activity);
  }

  @Test
  void expressionTimeout_isResolvedAgainstTheFirstMessage_andPassedToThePlugin() {
    when(plugin.getDefaultTimeout(anyMap())).thenReturn(Duration.ofSeconds(90));
    final Message<?> input = message(Map.of("secs", 7));

    StepVerifier.create(retime(Map.of("timeout", "${payload.secs:int}"), input))
        .expectNext(input)
        .verifyComplete();

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Map<String, Object>> config = ArgumentCaptor.forClass(Map.class);
    verify(plugin).getDefaultTimeout(config.capture());
    assertThat(config.getValue()).containsEntry("timeout", 7);
    activity.touch();
    // the node now idles out after 90 s, not the 30 s it started with
    StepVerifier.create(activity.guard(Flux.never(), Duration.ofSeconds(30), time, () -> false))
        .then(() -> time.advanceTimeBy(Duration.ofSeconds(60)))
        .thenCancel()
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void expressionTimeoutSeconds_winsOverThePluginDefault() {
    final Message<?> input = message(Map.of("secs", 120));

    StepVerifier.create(retime(Map.of("timeoutSeconds", "${payload.secs}"), input))
        .expectNextCount(1)
        .verifyComplete();

    activity.touch();
    StepVerifier.create(activity.guard(Flux.never(), Duration.ofSeconds(30), time, () -> false))
        .then(() -> time.advanceTimeBy(Duration.ofSeconds(100)))
        .thenCancel()
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void staticConfig_isLeftAlone() {
    final Flux<Message<?>> input = Flux.just(message("x"));

    final Flux<Message<?>> result =
        NodeTimeouts.retimeOnFirstInput(
            input, new WorkflowNode("n", "t", Map.of("timeout", 5)), plugin, resolver, activity);

    assertThat(result).isSameAs(input);
  }

  @Test
  void unresolvableExpression_keepsTheDefault_andTheMessageStillFlows() {
    final Message<?> input = message("not a map");
    when(plugin.getDefaultTimeout(anyMap())).thenThrow(new IllegalStateException("bad"));

    StepVerifier.create(retime(Map.of("timeout", "${payload.secs}"), input))
        .expectNext(input)
        .verifyComplete();
  }

  @Test
  void emptyInput_completesWithoutResolving() {
    StepVerifier.create(
            NodeTimeouts.retimeOnFirstInput(
                Flux.empty(),
                new WorkflowNode("n", "t", Map.of("timeout", "${payload.x}")),
                plugin,
                resolver,
                activity))
        .verifyComplete();
  }
}
