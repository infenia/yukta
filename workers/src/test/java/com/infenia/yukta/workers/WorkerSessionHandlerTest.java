// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.workers;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.worker.WorkerRegistration;
import com.infenia.yukta.service.worker.WorkerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals"})
class WorkerSessionHandlerTest {

  private final WorkerRegistry registry =
      new WorkerRegistry(Duration.ofSeconds(15), new SimpleMeterRegistry());
  private final WorkerSessionHandler handler = new WorkerSessionHandler(registry, "secret");

  private static WorkerFrame hello(final String token, final String workerId, final int capacity) {
    return WorkerFrame.register(
        token,
        new WorkerRegistration(workerId, Set.of("default"), Set.of("HTTP_REQUEST"), capacity));
  }

  @Test
  void workerWithToken_registersRenewsAndLeavesWithItsStream() {
    final Sinks.Many<WorkerFrame> frames = Sinks.many().unicast().onBackpressureBuffer();

    StepVerifier.create(handler.session(frames.asFlux()))
        .then(() -> frames.tryEmitNext(hello("secret", "w1", 4)))
        .assertNext(frame -> assertThat(frame.type()).isEqualTo(WorkerFrame.Type.ACK))
        .then(() -> assertThat(registry.find("w1")).isPresent())
        .then(() -> frames.tryEmitNext(WorkerFrame.lease(3)))
        .assertNext(frame -> assertThat(frame.type()).isEqualTo(WorkerFrame.Type.ACK))
        .then(() -> assertThat(registry.find("w1").orElseThrow().usedSlots()).isEqualTo(3))
        .then(frames::tryEmitComplete)
        .verifyComplete();

    assertThat(registry.find("w1")).isEmpty();
  }

  @Test
  void badTokenOrFirstFrame_isRefusedAndNothingRegistered() {
    StepVerifier.create(handler.session(Flux.just(hello("wrong", "w1", 4))))
        .assertNext(frame -> assertThat(frame.message()).isEqualTo("token refused"))
        .verifyComplete();
    StepVerifier.create(handler.session(Flux.just(hello(null, "w1", 4))))
        .assertNext(frame -> assertThat(frame.type()).isEqualTo(WorkerFrame.Type.REJECT))
        .verifyComplete();
    StepVerifier.create(handler.session(Flux.just(WorkerFrame.lease(0))))
        .assertNext(frame -> assertThat(frame.message()).contains("must be REGISTER"))
        .verifyComplete();
    StepVerifier.create(handler.session(Flux.empty()))
        .assertNext(frame -> assertThat(frame.type()).isEqualTo(WorkerFrame.Type.REJECT))
        .verifyComplete();

    assertThat(registry.list()).isEmpty();
  }

  @Test
  void refusedRegistration_isRejectedWithItsReason() {
    StepVerifier.create(handler.session(Flux.just(hello("secret", "w1", 0))))
        .assertNext(frame -> assertThat(frame.message()).contains("capacity"))
        .verifyComplete();
  }

  @Test
  void unexpectedFrame_endsTheSessionWithReject() {
    final Sinks.Many<WorkerFrame> frames = Sinks.many().unicast().onBackpressureBuffer();

    StepVerifier.create(handler.session(frames.asFlux()))
        .then(() -> frames.tryEmitNext(hello("secret", "w1", 2)))
        .expectNextCount(1)
        .then(() -> frames.tryEmitNext(hello("secret", "w1", 2)))
        .assertNext(frame -> assertThat(frame.message()).contains("unexpected REGISTER"))
        .verifyComplete();

    assertThat(registry.find("w1")).isEmpty();
  }

  @Test
  void lapsedLease_isRejectedSoTheWorkerRegistersAgain() {
    final Sinks.Many<WorkerFrame> frames = Sinks.many().unicast().onBackpressureBuffer();

    StepVerifier.create(handler.session(frames.asFlux()))
        .then(() -> frames.tryEmitNext(hello("secret", "w1", 2)))
        .expectNextCount(1)
        .then(() -> registry.remove("w1"))
        .then(() -> frames.tryEmitNext(WorkerFrame.lease(0)))
        .assertNext(frame -> assertThat(frame.message()).contains("register again"))
        .verifyComplete();
  }

  @Test
  void theControllerDelegatesToTheHandler() {
    StepVerifier.create(new WorkerController(handler).session(Flux.empty()))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void frameSetsAreCopiedAndNullSetsBecomeEmpty() {
    final WorkerFrame frame =
        new WorkerFrame(WorkerFrame.Type.ACK, null, null, null, null, 0, 0, null);

    assertThat(frame.pools()).isEmpty();
    assertThat(frame.pluginTypes()).isEmpty();
  }
}
