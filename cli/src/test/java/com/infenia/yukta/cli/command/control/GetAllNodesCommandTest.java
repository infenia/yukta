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
package com.infenia.yukta.cli.command.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.cli.CliFormatter;
import com.infenia.yukta.cli.YuktaDaemonClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class GetAllNodesCommandTest {
  @Mock private YuktaDaemonClient mockClient;
  @Mock private CliFormatter mockFormatter;
  private GetAllNodesCommand command;

  @BeforeEach
  void setUp() {
    command = new GetAllNodesCommand(mockClient, mockFormatter);
  }

  @Test
  void constructor_createsInstance() {
    assertThat(command).isNotNull();
  }

  @Test
  void isRunnable() {
    assertThat(command).isInstanceOf(Runnable.class);
  }

  @Test
  void run_printsTableByDefault() {
    final var nodes = List.of("node1", "node2");
    when(mockClient.getAllActiveNodes()).thenReturn(nodes);

    command.run();

    verify(mockFormatter).printTable(nodes);
  }

  @Test
  void run_printsJsonWhenFormatIsJson() throws Exception {
    final var nodes = List.of("node1", "node2");
    when(mockClient.getAllActiveNodes()).thenReturn(nodes);
    try {
      final var field = GetAllNodesCommand.class.getDeclaredField("outputFormat");
      field.setAccessible(true);
      field.set(command, "json");
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }

    command.run();

    verify(mockFormatter).printJson(nodes);
  }

  @Test
  void run_throwsRuntimeExceptionOnFormatterError() {
    final var nodes = List.of("node1");
    when(mockClient.getAllActiveNodes()).thenReturn(nodes);
    doThrow(new IllegalArgumentException("Invalid format")).when(mockFormatter).printTable(nodes);

    assertThatThrownBy(command::run).isInstanceOf(RuntimeException.class);
  }
}
