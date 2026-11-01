// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static com.infenia.yukta.service.input.InputTestSupport.limits;
import static com.infenia.yukta.service.input.InputTestSupport.spec;
import static com.infenia.yukta.service.input.InputTestSupport.who;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.input.InputExpiredException;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import com.infenia.yukta.testsupport.YuktaSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

@NoArgsConstructor
class InputDeadlineSweeperTest {

  private final MutableClock clock = new MutableClock();
  private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
  private final AtomicBoolean leader = new AtomicBoolean(true);
  private final InputBroker broker =
      new InputBroker(
          new InMemoryInputRequestStore(10),
          new ExecutionEventFeed(),
          new InMemoryExecutionLogStore(),
          new SimpleMeterRegistry(),
          new InputRedactor(List.of()),
          limits(),
          clock);
  private final InputDeadlineSweeper sweeper =
      new InputDeadlineSweeper(broker, leader::get, Duration.ofSeconds(1), scheduler);
  private final List<Throwable> failures = new ArrayList<>();

  @AfterEach
  void tearDown() {
    sweeper.stop();
  }

  private void askWithTenSecondTtl() {
    broker
        .ask(who("e1", "n1"), spec("k1", Duration.ofSeconds(10), TimeoutAction.FAIL, null))
        .subscribe(r -> {}, failures::add);
  }

  @Test
  void requestPastItsDeadlineEndsAtTheNextTick() {
    askWithTenSecondTtl();
    sweeper.start();
    assertThat(sweeper.isRunning()).isTrue();

    clock.advance(Duration.ofSeconds(11));
    scheduler.advanceTimeBy(Duration.ofMillis(999));
    assertThat(failures).isEmpty();
    scheduler.advanceTimeBy(Duration.ofMillis(1));

    assertThat(failures).singleElement().isInstanceOf(InputExpiredException.class);
  }

  @Test
  void requestBeforeItsDeadlineIsLeftAlone() {
    askWithTenSecondTtl();
    sweeper.start();

    clock.advance(Duration.ofSeconds(5));
    scheduler.advanceTimeBy(Duration.ofSeconds(5));

    assertThat(failures).isEmpty();
    assertThat(broker.listOpen(null, null).collectList().block()).hasSize(1);
  }

  @Test
  void serverThatIsNotTheLeaderDoesNotSweep() {
    askWithTenSecondTtl();
    leader.set(false);
    sweeper.start();

    clock.advance(Duration.ofSeconds(11));
    scheduler.advanceTimeBy(Duration.ofSeconds(3));
    assertThat(failures).isEmpty();

    leader.set(true);
    scheduler.advanceTimeBy(Duration.ofSeconds(1));
    assertThat(failures).hasSize(1);
  }

  @Test
  void stopEndsTheTimer() {
    sweeper.start();
    sweeper.stop();

    assertThat(sweeper.isRunning()).isFalse();
  }

  @Test
  void startingTwiceKeepsOneTimer() {
    sweeper.start();
    sweeper.start();

    assertThat(sweeper.isRunning()).isTrue();
    sweeper.stop();
    assertThat(sweeper.isRunning()).isFalse();
  }

  @Test
  void springConstructorTakesTheIntervalFromTheSettings() {
    final InputDeadlineSweeper fromSettings =
        new InputDeadlineSweeper(broker, leader::get, YuktaSettings.bind(Map.of()));

    assertThat(fromSettings.isRunning()).isFalse();
  }

  @Test
  void failingSweepIsLoggedAndTheNextTickSweepsAgain() {
    final InputBroker failing = org.mockito.Mockito.mock(InputBroker.class);
    org.mockito.Mockito.when(failing.sweep())
        .thenReturn(Mono.error(new IllegalStateException("store down")));
    final InputDeadlineSweeper failingSweeper =
        new InputDeadlineSweeper(failing, leader::get, Duration.ofSeconds(1), scheduler);

    failingSweeper.start();
    scheduler.advanceTimeBy(Duration.ofSeconds(2));
    failingSweeper.stop();

    org.mockito.Mockito.verify(failing, org.mockito.Mockito.times(2)).sweep();
  }
}
