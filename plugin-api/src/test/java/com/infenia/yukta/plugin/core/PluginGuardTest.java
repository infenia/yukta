// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings("PMD.CommentRequired")
class PluginGuardTest {

  private final Message<?> input = DefaultMessage.create(UUID.randomUUID(), Map.of("a", 1));
  private final List<String> counts = new ArrayList<>();
  private final PluginMetricsReporter reporter =
      (node, metric, tag) -> counts.add(node + ":" + metric + ":" + tag);

  private Mono<Message<?>> run(final Mono<Message<?>> op, final FailureMode mode) {
    return PluginGuard.guard(op, input, mode, reporter, "M")
        .contextWrite(c -> c.put("nodeId", "n1"));
  }

  @Test
  void successCountsOk() {
    StepVerifier.create(run(Mono.just(input), FailureMode.ERROR))
        .expectNext(input)
        .verifyComplete();
    assertThat(counts).containsExactly("n1:M:OK");
  }

  @Test
  void errorModePropagatesAndCounts() {
    StepVerifier.create(run(Mono.error(new IllegalStateException("x")), FailureMode.ERROR))
        .expectError(IllegalStateException.class)
        .verify();
    assertThat(counts).containsExactly("n1:M:ERROR");
  }

  @Test
  void continueModeRoutesToErrorPort() {
    StepVerifier.create(run(Mono.error(new IllegalStateException("boom")), FailureMode.CONTINUE))
        .assertNext(
            m -> {
              assertThat(m.sourcePort()).isEqualTo("error");
              assertThat(m.failureReason()).isEqualTo("IllegalStateException");
              assertThat(m.exceptionDetail()).isEqualTo("boom");
              assertThat(m.payload()).isEqualTo(Map.of("a", 1));
            })
        .verifyComplete();
  }

  @Test
  void noopReporterIgnoresCounts() {
    PluginMetricsReporter.NOOP.increment("n", "m", "OK");
    assertThat(PluginMetricsReporter.NOOP).isNotNull();
  }
}
