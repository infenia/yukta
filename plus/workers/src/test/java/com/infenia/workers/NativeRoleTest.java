// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class NativeRoleTest {

  @Test
  void nativeImageWithWorkerSettings_butNoAgent_failsWithTheFlagToBuildIt() {
    assertThatThrownBy(() -> NativeRole.check(true, "cp:7000", "secret", false, true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("yukta.workers.control-plane is set")
        .hasMessageContaining("-Pyukta.workers.native=worker");
  }

  @Test
  void nativeImageWithControlPlaneSettings_butNoExecutor_failsWithTheFlagToBuildIt() {
    assertThatThrownBy(() -> NativeRole.check(true, "", "secret", true, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("yukta.workers.token is set")
        .hasMessageContaining("-Pyukta.workers.native=control-plane");
  }

  @Test
  void matchingRole_noWorkerSettings_orTheJvm_start() {
    assertThatCode(() -> NativeRole.check(true, "cp:7000", "secret", true, false))
        .doesNotThrowAnyException();
    assertThatCode(() -> NativeRole.check(true, "", "secret", false, true))
        .doesNotThrowAnyException();
    assertThatCode(() -> NativeRole.check(true, "", "", false, false)).doesNotThrowAnyException();
    assertThatCode(() -> NativeRole.check(false, "cp:7000", "secret", false, false))
        .doesNotThrowAnyException();
    assertThatCode(() -> NativeRole.check(false, "", "secret", false, false))
        .doesNotThrowAnyException();
  }
}
