// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.result;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.result.ResultEvent.Kind;
import com.infenia.yukta.testsupport.YuktaSettings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ExecutionResultServiceTest {

  private static ExecutionResultService service(final int retained) {
    return new ExecutionResultService(retained, Duration.ofMinutes(5), 10);
  }

  @Test
  void settingsBuildTheServiceThatKeepsPublishedExecution() {
    final ExecutionResultService fromSettings =
        new ExecutionResultService(YuktaSettings.bind(Map.of("yukta.results.max-executions", "5")));
    assertThat(fromSettings.has("e1")).isFalse();

    fromSettings.publish("e1", "n1", "payload");

    assertThat(fromSettings.has("e1")).isTrue();
  }

  @Test
  void liveRunsAreNeverEvictedBySize() throws InterruptedException {
    final ExecutionResultService results = service(10); // cap of 10 finished runs
    for (int i = 0; i < 30; i++) {
      results.publish("e" + i, "t", i);
    }
    Thread.sleep(200); // Caffeine evicts asynchronously
    for (int i = 0; i < 30; i++) {
      assertThat(results.has("e" + i)).isTrue();
    }
  }

  @Test
  void liveSubscriberGetsResultsThenEndsWithTheExecution() {
    final ExecutionResultService results = service(100);

    StepVerifier.create(results.stream("e1"))
        .then(() -> results.publish("e1", "t1", "hello"))
        .assertNext(
            event -> {
              assertThat(event.kind()).isEqualTo(Kind.RESULT);
              assertThat(event.nodeId()).isEqualTo("t1");
              assertThat(event.payload()).isEqualTo("hello");
              assertThat(event.sequence()).isZero();
            })
        .then(() -> results.nodeEnd("e1", "t1", "SUCCESS"))
        .assertNext(event -> assertThat(event.kind()).isEqualTo(Kind.NODE_END))
        .then(() -> results.complete("e1", "SUCCESS"))
        .assertNext(
            event -> {
              assertThat(event.kind()).isEqualTo(Kind.EXECUTION_END);
              assertThat(event.status()).isEqualTo("SUCCESS");
              assertThat(event.truncated()).isFalse();
            })
        .verifyComplete();
  }

  @Test
  void lateSubscriberReplaysWhatWasRecorded() {
    final ExecutionResultService results = service(100);
    results.publish("e1", "t1", 1);
    results.publish("e1", "t2", 2);
    results.complete("e1", "SUCCESS");

    final List<ResultEvent> replayed =
        results.stream("e1").collectList().block(Duration.ofSeconds(2));

    assertThat(replayed)
        .extracting(ResultEvent::kind)
        .containsExactly(Kind.RESULT, Kind.RESULT, Kind.EXECUTION_END);
    assertThat(replayed).extracting(ResultEvent::sequence).containsExactly(0L, 1L, 2L);
  }

  @Test
  void replayKeepsTheNewestEventsAndFlagsTruncationButLiveSawEverything() {
    final ExecutionResultService results = service(2);
    final List<ResultEvent> live = new java.util.concurrent.CopyOnWriteArrayList<>();
    results.stream("e1").subscribe(live::add);

    for (int i = 0; i < 5; i++) {
      results.publish("e1", "t1", i);
    }
    results.complete("e1", "SUCCESS");

    assertThat(live).hasSize(6);
    final List<ResultEvent> late = results.stream("e1").collectList().block(Duration.ofSeconds(2));
    assertThat(late).hasSize(2);
    assertThat(late.getLast().kind()).isEqualTo(Kind.EXECUTION_END);
    assertThat(late.getLast().truncated()).isTrue();
  }

  @Test
  void readReturnsWhatIsKeptFromTheCursorOn() {
    final ExecutionResultService results = service(100);
    results.publish("e1", "t1", "a");
    results.publish("e1", "t1", "b");

    assertThat(results.read("e1", 0).events()).hasSize(2);
    assertThat(results.read("e1", 1).events())
        .extracting(ResultEvent::payload)
        .containsExactly("b");
  }

  @Test
  void readOfAnUnknownExecutionIsEmptyAndIncomplete() {
    final ExecutionResults read = service(10).read("nope", -3);

    assertThat(read.events()).isEmpty();
    assertThat(read.complete()).isFalse();
    assertThat(read.status()).isNull();
    assertThat(read.nextCursor()).isZero();
  }

  @Test
  void readReportsCompletionStatusAndTruncation() {
    final ExecutionResultService results = service(2);
    results.publish("e1", "t1", "a");
    results.publish("e1", "t1", "b");
    assertThat(results.read("e1", 0).complete()).isFalse();

    results.complete("e1", "FAILURE");
    final ExecutionResults read = results.read("e1", 0);

    assertThat(read.complete()).isTrue();
    assertThat(read.status()).isEqualTo("FAILURE");
    assertThat(read.truncated()).isTrue();
    assertThat(read.nextCursor()).isEqualTo(3);
    assertThat(results.read("e1", 3).events()).isEmpty();
    assertThat(results.read("e1", 3).nextCursor()).isEqualTo(3);
  }

  @Test
  void hasIsFalseUntilSomethingIsRecorded() {
    final ExecutionResultService results = service(10);
    assertThat(results.has("e1")).isFalse();
    results.publish("e1", "t1", "x");
    assertThat(results.has("e1")).isTrue();
  }
}
