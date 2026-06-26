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
package com.infenia.yukta.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class DaemonStartupExceptionTest {

  @Test
  void constructor_withMessage_createsException() {
    String message = "Daemon failed to start";
    DaemonStartupException exception = new DaemonStartupException(message);

    assertThat(exception.getMessage()).isEqualTo(message);
    assertThat(exception.getCause()).isNull();
  }

  @Test
  void constructor_withMessageAndCause_createsException() {
    String message = "Daemon failed to start";
    Throwable cause = new RuntimeException("Connection refused");
    DaemonStartupException exception = new DaemonStartupException(message, cause);

    assertThat(exception.getMessage()).isEqualTo(message);
    assertThat(exception.getCause()).isEqualTo(cause);
  }

  @Test
  void isRuntimeException_extendsRuntimeException() {
    DaemonStartupException exception = new DaemonStartupException("Test");

    assertThat(exception).isInstanceOf(RuntimeException.class);
  }

  @Test
  void throwException_withMessage_canBeCaught() {
    assertThatThrownBy(
            () -> {
              throw new DaemonStartupException("Daemon startup failed");
            })
        .isInstanceOf(DaemonStartupException.class)
        .hasMessage("Daemon startup failed");
  }

  @Test
  void throwException_withCause_canBeCaughtWithCause() {
    Throwable cause = new IOException("Port already in use");
    assertThatThrownBy(
            () -> {
              throw new DaemonStartupException("Failed to start daemon", cause);
            })
        .isInstanceOf(DaemonStartupException.class)
        .hasMessage("Failed to start daemon")
        .hasCause(cause);
  }

  @Test
  void exception_withNullMessage_createsException() {
    DaemonStartupException exception = new DaemonStartupException(null);

    assertThat(exception.getMessage()).isNull();
  }

  @Test
  void exception_withEmptyMessage_createsException() {
    DaemonStartupException exception = new DaemonStartupException("");

    assertThat(exception.getMessage()).isEmpty();
  }
}
