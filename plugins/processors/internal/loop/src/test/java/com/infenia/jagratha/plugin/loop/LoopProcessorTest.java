/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.jagratha.plugin.loop;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.plugin.Message;
import com.infenia.jagratha.service.TaskTrackerService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class LoopProcessorTest {

  @Mock private TaskTrackerService tracker;

  private TestLoopProcessor processor;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    processor = new TestLoopProcessor();
    processor.setTracker(tracker);

    when(tracker.appendLog(anyString(), anyString())).thenReturn(Mono.empty());
    when(tracker.updateTaskStatus(anyString(), anyString(), anyString(), anyString(), anyMap()))
        .thenReturn(Mono.empty());
  }

  @Test
  void testSimpleLoopUntilExitCondition() {
    Map<String, Object> config =
        Map.of(
            "maxIterations", 5,
            "maxDuration", "PT1M",
            "delayInterval", "PT0S",
            "failureStrategy", "ABORT",
            "emitIntermediate", false);

    Message inputMsg = Message.create(UUID.randomUUID(), 0);

    processor.setIterationLogic(
        msg -> {
          int val = (int) msg.payload();
          return Mono.just(Message.create(msg.traceId(), val + 1));
        });

    processor.setExitLogic(
        msg -> {
          int val = (int) msg.payload();
          return Mono.just(val >= 3);
        });

    StepVerifier.create(
            processor
                .process(Flux.just(inputMsg), config)
                .contextWrite(Context.of("sessionId", "s1", "nodeId", "n1")))
        .expectNextMatches(msg -> (int) msg.payload() == 3)
        .verifyComplete();

    verify(tracker, atLeastOnce()).appendLog(eq("s1"), anyString());
    verify(tracker).updateTaskStatus(eq("s1"), eq("n1"), anyString(), anyString(), anyMap());
  }

  @Test
  void testMaxIterationsReached() {
    Map<String, Object> config =
        Map.of(
            "maxIterations",
            2,
            "maxDuration",
            "PT1M",
            "delayInterval",
            "PT0S",
            "emitIntermediate",
            true);

    Message inputMsg = Message.create(UUID.randomUUID(), 0);

    processor.setIterationLogic(
        msg -> {
          int val = (int) msg.payload();
          return Mono.just(Message.create(msg.traceId(), val + 1));
        });

    processor.setExitLogic(msg -> Mono.just(false));

    StepVerifier.create(
            processor
                .process(Flux.just(inputMsg), config)
                .contextWrite(Context.of("sessionId", "s1", "nodeId", "n1")))
        .expectNextMatches(msg -> (int) msg.payload() == 1)
        .expectNextMatches(msg -> (int) msg.payload() == 2)
        .verifyComplete();

    verify(tracker).appendLog(eq("s1"), eq("[Loop] Node: n1 - Max iterations reached (2)"));
  }

  @Test
  void testSkipFailureStrategy() {
    Map<String, Object> config =
        Map.of(
            "maxIterations", 3,
            "maxDuration", "PT1M",
            "delayInterval", "PT0S",
            "failureStrategy", "SKIP",
            "emitIntermediate", true);

    Message inputMsg = Message.create(UUID.randomUUID(), 0);

    AtomicInteger count = new AtomicInteger(0);
    processor.setIterationLogic(
        msg -> {
          if (count.incrementAndGet() == 2) {
            return Mono.error(new RuntimeException("Oops"));
          }
          int val = (int) msg.payload();
          return Mono.just(Message.create(msg.traceId(), val + 1));
        });

    processor.setExitLogic(msg -> Mono.just(false));

    StepVerifier.create(
            processor
                .process(Flux.just(inputMsg), config)
                .contextWrite(Context.of("sessionId", "s1", "nodeId", "n1")))
        .expectNextMatches(msg -> (int) msg.payload() == 1) // Iteration 1 success
        .expectNextMatches(
            msg -> (int) msg.payload() == 1) // Iteration 2 failed, SKIP used old payload
        .expectNextMatches(msg -> (int) msg.payload() == 2) // Iteration 3 success (1 + 1)
        .verifyComplete();
  }

  @Test
  void testRetryCurrentStrategy() {
    Map<String, Object> config =
        Map.of(
            "maxIterations", 3,
            "maxDuration", "PT1M",
            "delayInterval", "PT0S",
            "failureStrategy", "RETRY_CURRENT",
            "emitIntermediate", false);

    Message inputMsg = Message.create(UUID.randomUUID(), 0);

    AtomicInteger count = new AtomicInteger(0);
    processor.setIterationLogic(
        msg -> {
          if (count.incrementAndGet() < 3) {
            return Mono.error(new RuntimeException("Oops"));
          }
          int val = (int) msg.payload();
          return Mono.just(Message.create(msg.traceId(), val + 1));
        });

    processor.setExitLogic(msg -> Mono.just(false));

    // Iteration 1: fails -> retry (count=1)
    // Iteration 2: fails -> retry (count=2)
    // Iteration 3: succeeds -> returns 1 (count=3)
    // Max iterations reached (3)
    StepVerifier.create(
            processor
                .process(Flux.just(inputMsg), config)
                .contextWrite(Context.of("sessionId", "s1", "nodeId", "n1")))
        .expectNextMatches(msg -> (int) msg.payload() == 1)
        .verifyComplete();
  }

  @Test
  void testValidateConfig() {
    StepVerifier.create(
            processor.validateConfig(Map.of("maxDuration", "PT1M", "delayInterval", "PT1S")))
        .verifyComplete();

    StepVerifier.create(processor.validateConfig(Map.of("maxDuration", "invalid")))
        .expectError(IllegalArgumentException.class)
        .verify();

    StepVerifier.create(processor.validateConfig(Map.of("failureStrategy", "INVALID")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  private static class TestLoopProcessor extends LoopProcessor {
    private java.util.function.Function<Message, Mono<Message>> iterationLogic;
    private java.util.function.Function<Message, Mono<Boolean>> exitLogic;

    public void setTracker(TaskTrackerService tracker) {
      this.tracker = tracker;
    }

    public void setIterationLogic(java.util.function.Function<Message, Mono<Message>> logic) {
      this.iterationLogic = logic;
    }

    public void setExitLogic(java.util.function.Function<Message, Mono<Boolean>> logic) {
      this.exitLogic = logic;
    }

    @Override
    public String getType() {
      return "test-loop";
    }

    @Override
    protected Mono<Message> doIteration(Message message, Map<String, Object> config) {
      return iterationLogic.apply(message);
    }

    @Override
    protected Mono<Boolean> checkExitCondition(Message message, Map<String, Object> config) {
      return exitLogic.apply(message);
    }
  }
}
