// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.exception;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
class RunAbortedExceptionTest {

  @Test
  void isAbort_findsTheExceptionDirectlyOrAmongTheCauses() {
    final RunAbortedException abort = new RunAbortedException("budget");

    assertThat(abort).hasMessage("budget");
    assertThat(RunAbortedException.isAbort(abort)).isTrue();
    assertThat(RunAbortedException.isAbort(new IllegalStateException("wrapped", abort))).isTrue();
    assertThat(RunAbortedException.isAbort(new IllegalStateException("plain"))).isFalse();
  }

  @Test
  void theCauseIsKept() {
    final IllegalStateException cause = new IllegalStateException("why");

    final RunAbortedException abort = new RunAbortedException("stopped", cause);

    assertThat(abort).hasMessage("stopped").hasCause(cause);
    assertThat(RunAbortedException.isAbort(abort)).isTrue();
  }

  @Test
  void isAbort_isBounded_andToleratesNull() {
    Throwable chain = new RunAbortedException("too deep to be found");
    for (int i = 0; i < 10; i++) {
      chain = new IllegalStateException("wrapper " + i, chain);
    }

    assertThat(RunAbortedException.isAbort(chain)).isFalse();
    assertThat(RunAbortedException.isAbort(null)).isFalse();
  }
}
