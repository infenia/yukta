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
package com.infenia.yukta.cli.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.InitializationException;

@DisplayName("DaemonCommand")
class DaemonCommandTest {

  @Test
  @DisplayName("constructor creates instance")
  void constructor_createsInstance() {
    final DaemonCommand command = new DaemonCommand();

    assertThat(command).isNotNull();
  }

  @Test
  @DisplayName("run throws exception when no subcommand provided")
  void run_throwsException() {
    final DaemonCommand command = new DaemonCommand();

    assertThatThrownBy(command::run).isInstanceOf(InitializationException.class);
  }

  @Test
  @DisplayName("implements Runnable interface")
  void isRunnable() {
    final DaemonCommand command = new DaemonCommand();

    assertThat(command).isInstanceOf(Runnable.class);
  }
}
