// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Plain TCP is allowed only on this machine, unless the operator opts out. */
@SuppressWarnings({
  "PMD.AtLeastOneConstructor",
  "PMD.AvoidUsingHardCodedIP",
  "PMD.AvoidDuplicateLiterals"
})
class RequireTlsTest {

  @Test
  void testLoopbackMayBePlain() {
    assertThatCode(() -> WorkerTransport.requireTls("localhost", false, false))
        .doesNotThrowAnyException();
    assertThatCode(() -> WorkerTransport.requireTls("127.0.0.1", false, false))
        .doesNotThrowAnyException();
  }

  @Test
  void testRemoteHostNeedsTls() {
    assertThatThrownBy(() -> WorkerTransport.requireTls("10.1.2.3", false, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("10.1.2.3")
        .hasMessageContaining("allow-insecure");
  }

  @Test
  void testEveryInterfaceNeedsTls() {
    assertThatThrownBy(() -> WorkerTransport.requireTls("", false, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("every interface");
  }

  @Test
  void testUnresolvableHostNeedsTls() {
    assertThatThrownBy(() -> WorkerTransport.requireTls("no-such-host.invalid", false, false))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void testTlsOrOptOutPasses() {
    assertThatCode(() -> WorkerTransport.requireTls("10.1.2.3", true, false))
        .doesNotThrowAnyException();
    assertThatCode(() -> WorkerTransport.requireTls("10.1.2.3", false, true))
        .doesNotThrowAnyException();
  }
}
