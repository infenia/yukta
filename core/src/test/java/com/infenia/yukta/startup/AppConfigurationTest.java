// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.service.log.LogRetention;
import com.infenia.yukta.service.sessionstore.SessionConfigStore;
import com.infenia.yukta.service.store.CheckpointJson;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

/** Tests for {@link AppConfiguration}. */
@NoArgsConstructor
class AppConfigurationTest {

  /** Application context runner for testing Spring configuration. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AppConfiguration.class));

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void shouldProvideDefaultBeans() {
    this.contextRunner.run(context -> {
      assertThat(context).hasSingleBean(Scheduler.class);
    });
  }

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void shouldNotOverrideExistingSessionConfigStore() {
    final SessionConfigStore customStore = mock(SessionConfigStore.class);
    this.contextRunner
        .withBean(SessionConfigStore.class, () -> customStore)
        .run(context -> {
          assertThat(context).hasSingleBean(SessionConfigStore.class);
          assertThat(context.getBean(SessionConfigStore.class)).isSameAs(customStore);
        });
  }

  @Test
  void shouldBeInstantiable() {
    final AppConfiguration config = new AppConfiguration();
    assertThat(config).isNotNull();
  }

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void shouldRegisterSessionConfigProperties() {
    this.contextRunner.run(
        context -> assertThat(context).hasSingleBean(SessionConfigProperties.class));
  }

  @Test
  void virtualThreadScheduler_runsMoreBlockingTasksThanCpusConcurrently() {
    final Scheduler scheduler = new AppConfiguration().virtualThreadScheduler();
    try {
      final int tasks = Runtime.getRuntime().availableProcessors() * 4;
      final long start = System.nanoTime();

      Flux.range(0, tasks)
          .flatMap(
              _ ->
                  Mono.fromRunnable(
                          () -> {
                            assertThat(Thread.currentThread().isVirtual()).isTrue();
                            LockSupport.parkNanos(Duration.ofMillis(300).toNanos());
                          })
                      .subscribeOn(scheduler),
              tasks)
          .blockLast(Duration.ofSeconds(10));

      // A CPU-sized pool would need four rounds (>= 1.2 s); concurrent execution needs one.
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(900));
    } finally {
      scheduler.dispose();
    }
  }

  @Test
  void virtualThreadScheduler_firesDelayedTasksForTimeouts() {
    final Scheduler scheduler = new AppConfiguration().virtualThreadScheduler();
    try {
      StepVerifier.create(Mono.never().timeout(Duration.ofMillis(100), scheduler))
          .expectError(java.util.concurrent.TimeoutException.class)
          .verify(Duration.ofSeconds(5));
    } finally {
      scheduler.dispose();
    }
  }

  @Test
  void applyPayloadPackages_handsTheSettingToTheCheckpointReader() {
    final YuktaProperties properties = mock(YuktaProperties.class);
    final YuktaProperties.Restart restart =
        new YuktaProperties.Restart(1, 1L, "", false, 1, List.of("java.util.concurrent.atomic."));
    when(properties.restart()).thenReturn(restart);
    try {
      new AppConfiguration().applyPayloadPackages(properties);
      final JsonMapper json = JsonMapper.builder().build();
      final String line =
          CheckpointJson.write(
              json, DefaultMessage.create(UUID.randomUUID(), new AtomicInteger(5)));
      assertThat(CheckpointJson.read(json, line).payload()).isInstanceOf(AtomicInteger.class);
    } finally {
      CheckpointJson.allowPackages(List.of());
    }
  }

  @Test
  void applyLogRetentionFloor_handsTheSettingToTheLogRetention() {
    final YuktaProperties properties = mock(YuktaProperties.class);
    when(properties.executionLog()).thenReturn(new YuktaProperties.ExecutionLog(20, 100, 1));
    final List<String> runs = new java.util.ArrayList<>(List.of("quiet-1", "quiet-2"));
    for (int i = 0; i < 50; i++) {
      runs.add("busy-" + i);
    }
    try {
      new AppConfiguration().applyLogRetentionFloor(properties);
      assertThat(LogRetention.keep(runs, id -> id.startsWith("busy") ? "busy" : "quiet", 20))
          .contains("quiet-2")
          .doesNotContain("quiet-1");
    } finally {
      LogRetention.minPerSession(LogRetention.DEFAULT_MIN_PER_SESSION);
    }
  }
}
