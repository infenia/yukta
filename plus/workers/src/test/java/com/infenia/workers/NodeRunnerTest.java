// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.type.TriggerPlugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class NodeRunnerTest {

  private final TriggerPlugin trigger = mock(TriggerPlugin.class);
  private final NodeRunner runner = buildRunner();

  private NodeRunner buildRunner() {
    when(trigger.getType()).thenReturn("TRIGGER");
    return new NodeRunner(
        new PluginRegistry(List.of(new FakePlugin("UPPER", new AtomicInteger()), trigger)),
        Set.of("UPPER", "TRIGGER"),
        new ObjectMapper());
  }

  private NodeFrame answerTo(final Flux<NodeFrame> frames) {
    return runner.run(frames).blockFirst();
  }

  private static void assertRefused(final NodeFrame answer, final String why) {
    assertThat(answer.kind()).isEqualTo(NodeFrame.Kind.ERROR);
    assertThat(answer.errorClass()).isEqualTo(IllegalArgumentException.class.getName());
    assertThat(answer.errorMessage()).contains(why);
  }

  @Test
  void firstFrameNotStart_isRefused() {
    assertRefused(answerTo(Flux.just(NodeFrame.complete())), "must be START");
    assertRefused(answerTo(Flux.empty()), "must be START");
  }

  @Test
  void unagreedOrNonProcessorType_isRefused() {
    assertRefused(
        answerTo(Flux.just(NodeFrame.start("e", "n", "PROCESS_EXECUTOR", Map.of()))),
        "does not run");
    assertRefused(
        answerTo(Flux.just(NodeFrame.start("e", "n", "TRIGGER", Map.of()))), "not a processor");
  }

  @Test
  void startWithoutConfig_runsWithEmptyOne() {
    final NodeFrame start =
        new NodeFrame(NodeFrame.Kind.START, "e", "n", "UPPER", null, null, null, null, null);

    StepVerifier.create(runner.run(Flux.just(start, NodeFrame.complete())))
        .assertNext(frame -> assertThat(frame.kind()).isEqualTo(NodeFrame.Kind.COMPLETE))
        .verifyComplete();
  }

  @Test
  void types_listsClassesOfTheFailureAndItsCauses_andStopsOnACycle() {
    final IllegalStateException first = new IllegalStateException("a");
    final java.io.IOException second = new java.io.IOException("b", first);
    first.initCause(second);

    assertThat(NodeRunner.types(first))
        .contains(
            IllegalStateException.class.getName(),
            RuntimeException.class.getName(),
            java.io.IOException.class.getName())
        .doesNotHaveDuplicates();
  }
}
