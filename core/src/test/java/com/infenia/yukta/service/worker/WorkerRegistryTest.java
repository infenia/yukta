// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.LawOfDemeter"})
class WorkerRegistryTest {

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final WorkerRegistry registry =
      new WorkerRegistry(
          Duration.ofSeconds(15),
          meters,
          new Clock() {
            @Override
            public ZoneOffset getZone() {
              return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final java.time.ZoneId zone) {
              return this;
            }

            @Override
            public Instant instant() {
              return now.get();
            }
          });

  private static WorkerRegistration worker(final String name, final int capacity) {
    return new WorkerRegistration(name, Set.of("default"), Set.of("HTTP_REQUEST"), capacity);
  }

  @Test
  void registeredWorkers_areListedWithFreeSlotsInIdOrder() {
    registry.register(worker("w2", 4));
    registry.register(worker("w1", 2));
    assertThat(registry.heartbeat("w1", 1)).isTrue();

    assertThat(registry.list()).extracting(WorkerInfo::workerId).containsExactly("w1", "w2");
    assertThat(registry.find("w1")).hasValueSatisfying(w -> assertThat(w.freeSlots()).isEqualTo(1));
    assertThat(registry.find("none")).isEmpty();
    assertThat(registry.lease()).isEqualTo(Duration.ofSeconds(15));
    assertThat(meters.get("yukta.workers").gauge().value()).isEqualTo(2.0);
    assertThat(meters.get("yukta.worker.slots").gauge().value()).isEqualTo(5.0);
  }

  @Test
  void workerMissingItsLease_isDroppedAndMustRegisterAgain() {
    registry.register(worker("w1", 2));
    now.set(now.get().plusSeconds(10));
    assertThat(registry.heartbeat("w1", 0)).isTrue();

    now.set(now.get().plusSeconds(16));

    assertThat(registry.heartbeat("w1", 0)).isFalse();
    assertThat(registry.list()).isEmpty();
    assertThat(registry.heartbeat("never-seen", 0)).isFalse();
  }

  @Test
  void expiredLease_isNotRenewedByHeartbeat() {
    registry.register(worker("w1", 2));
    now.set(now.get().plusSeconds(60));

    assertThat(registry.heartbeat("w1", 1)).isFalse();
    assertThat(registry.find("w1")).isEmpty();
  }

  @Test
  void removeAndReRegister_replaceTheEntry() {
    registry.register(worker("w1", 2));
    registry.heartbeat("w1", 2);
    registry.register(worker("w1", 8));
    assertThat(registry.find("w1")).hasValueSatisfying(w -> assertThat(w.usedSlots()).isZero());

    registry.remove("w1");

    assertThat(registry.list()).isEmpty();
  }

  @Test
  void badRegistrationsAreRefused() {
    assertThatThrownBy(() -> registry.register(worker(" ", 2)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry.register(worker(null, 2)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry.register(worker("w", 0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void freeSlotsNeverGoNegative_andSpringConstructorWorks() {
    registry.register(worker("w1", 1));
    registry.heartbeat("w1", 5);
    assertThat(registry.find("w1")).hasValueSatisfying(w -> assertThat(w.freeSlots()).isZero());
    assertThat(new WorkerRegistry(Duration.ofSeconds(1), new SimpleMeterRegistry()).list())
        .isEmpty();
  }
}
