// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.service.control.directive.DirectiveDispatcher;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.testsupport.YuktaSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** The host hands the commands of its supervisors to the arbiter. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.UnitTestShouldIncludeAssert"})
class SupervisorHostArbiterTest {

  private static ExecutionSupervisor emitting(
      final String id, final int order, final ExecutionControlCommand command) {
    return new ExecutionSupervisor() {
      @Override
      @SuppressWarnings("PMD.ShortMethodName")
      public String id() {
        return id;
      }

      @Override
      public int order() {
        return order;
      }

      @Override
      public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
        return Flux.just(command).concatWith(Flux.never());
      }
    };
  }

  private static double count(
      final SimpleMeterRegistry meters, final String id, final String outcome) {
    final var counter =
        meters.find("yukta.supervisor.commands").tags("id", id, "outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  @SuppressWarnings("unchecked")
  void theSupervisorWithTheLowerOrderWinsAndTheOtherIsRecordedAsArbitrated() {
    final ObjectProvider<ExecutionSupervisor> provider = mock(ObjectProvider.class);
    when(provider.stream())
        .thenAnswer(
            _ ->
                Stream.of(
                    emitting("slow", 5, new ExecutionControlCommand.PauseWorkflowCommand("e1")),
                    emitting("fast", 1, new ExecutionControlCommand.ResumeWorkflowCommand("e1"))));
    final DirectiveDispatcher dispatcher = mock(DirectiveDispatcher.class);
    when(dispatcher.dispatchOrdered(any())).thenReturn(Mono.empty());
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final CommandArbiter arbiter =
        new CommandArbiter(
            new YuktaProperties.Arbiter(
                Duration.ofMillis(200), Duration.ofSeconds(60), 4, Duration.ofMinutes(5)),
            Clock.systemUTC(),
            Schedulers.parallel());
    final SupervisorHost host =
        new SupervisorHost(
            provider,
            new ExecutionEventFeed(),
            dispatcher,
            new InMemoryExecutionLogStore(),
            meters,
            false,
            20,
            100,
            arbiter);

    host.start();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(count(meters, "fast", "applied")).isEqualTo(1.0);
              assertThat(count(meters, "slow", "arbitrated")).isEqualTo(1.0);
            });
    host.stop();
  }

  @Test
  void theArbiterSettingsHaveDefaultsAndRefuseNonsense() {
    final YuktaProperties.Arbiter defaults = YuktaSettings.bind(Map.of()).supervisor().arbiter();

    assertThat(defaults.window()).isEqualTo(Duration.ofMillis(500));
    assertThat(defaults.flapPeriod()).isEqualTo(Duration.ofSeconds(60));
    assertThat(defaults.flapLimit()).isEqualTo(4);
    assertThat(defaults.flapCooldown()).isEqualTo(Duration.ofMinutes(5));
    assertThat(
            YuktaSettings.bind(Map.of("yukta.supervisor.arbiter.window", "0"))
                .supervisor()
                .arbiter()
                .window())
        .isZero();
    assertThatThrownBy(() -> YuktaSettings.bind(Map.of("yukta.supervisor.arbiter.window", "-1s")))
        .hasRootCauseMessage("yukta.supervisor.arbiter.window must not be negative");
    assertThatThrownBy(
            () -> YuktaSettings.bind(Map.of("yukta.supervisor.arbiter.flap-period", "0s")))
        .hasRootCauseMessage("yukta.supervisor.arbiter.flap-period must be positive");
    assertThatThrownBy(
            () -> YuktaSettings.bind(Map.of("yukta.supervisor.arbiter.flap-period", "-5s")))
        .hasRootCauseMessage("yukta.supervisor.arbiter.flap-period must be positive");
    assertThatThrownBy(
            () -> YuktaSettings.bind(Map.of("yukta.supervisor.arbiter.flap-cooldown", "-5s")))
        .hasRootCauseMessage("yukta.supervisor.arbiter.flap-cooldown must be positive");
    assertThatThrownBy(
            () -> YuktaSettings.bind(Map.of("yukta.supervisor.arbiter.flap-cooldown", "0s")))
        .hasRootCauseMessage("yukta.supervisor.arbiter.flap-cooldown must be positive");
  }
}
