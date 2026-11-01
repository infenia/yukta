// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SupervisorFactoryTest {

  private final AtomicInteger created = new AtomicInteger();

  private final SupervisorFactory factory =
      new SupervisorFactory() {
        @Override
        public String type() {
          return "t";
        }

        @Override
        public ExecutionSupervisor create(final SupervisorProfile profile) {
          created.incrementAndGet();
          if (profile.settings().containsKey("bad")) {
            throw new IllegalArgumentException("bad settings");
          }
          return Mockito.mock(ExecutionSupervisor.class);
        }
      };

  @Test
  void validate_byDefaultMakesSupervisor_andSurfacesItsRefusal() {
    factory.validate(new SupervisorProfile("p", "t", Map.of(), false));
    org.assertj.core.api.Assertions.assertThat(created).hasValue(1);

    assertThatThrownBy(
            () -> factory.validate(new SupervisorProfile("p", "t", Map.of("bad", 1), false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("bad settings");
  }
}
