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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.infenia.yukta.cli.command.ControlCommand;
import com.infenia.yukta.cli.command.DaemonCommand;
import com.infenia.yukta.cli.command.control.GetAllNodesCommand;
import com.infenia.yukta.cli.command.control.GetNodesCommand;
import com.infenia.yukta.cli.command.control.HeartbeatCommand;
import com.infenia.yukta.cli.command.control.HistoryCommand;
import com.infenia.yukta.cli.command.control.LogsStreamCommand;
import com.infenia.yukta.cli.command.control.NodesCommand;
import com.infenia.yukta.cli.command.control.ProgressCommand;
import com.infenia.yukta.cli.command.control.ProgressStreamCommand;
import com.infenia.yukta.cli.command.control.SendCommandCommand;
import com.infenia.yukta.cli.command.control.SessionApplyCommand;
import com.infenia.yukta.cli.command.control.WorkflowTriggerCommand;
import com.infenia.yukta.cli.command.daemon.DaemonStartCommand;
import com.infenia.yukta.cli.command.daemon.DaemonStatusCommand;
import com.infenia.yukta.cli.command.daemon.DaemonStopCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.ApplicationArguments;

@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class CliRunnerTest {

  @Mock private ControlCommand mockControlCommand;
  @Mock private NodesCommand mockNodesCommand;
  @Mock private GetNodesCommand mockGetNodesCommand;
  @Mock private GetAllNodesCommand mockGetAllNodesCommand;
  @Mock private HeartbeatCommand mockHeartbeatCommand;
  @Mock private SendCommandCommand mockSendCommandCommand;
  @Mock private ProgressCommand mockProgressCommand;
  @Mock private ProgressStreamCommand mockProgressStreamCommand;
  @Mock private LogsStreamCommand mockLogsStreamCommand;
  @Mock private HistoryCommand mockHistoryCommand;
  @Mock private SessionApplyCommand mockSessionApplyCommand;
  @Mock private WorkflowTriggerCommand mockWorkflowTriggerCommand;
  @Mock private DaemonCommand mockDaemonCommand;
  @Mock private DaemonStartCommand mockDaemonStartCommand;
  @Mock private DaemonStopCommand mockDaemonStopCommand;
  @Mock private DaemonStatusCommand mockDaemonStatusCommand;
  @Mock private DaemonManager mockDaemonManager;
  @Mock private DaemonProperties mockDaemonProperties;
  @Mock private SystemExitHandler mockExitHandler;
  private CliRunner cliRunner;

  @BeforeEach
  void setUp() {
    cliRunner =
        new CliRunner(
            mockControlCommand,
            mockNodesCommand,
            mockGetNodesCommand,
            mockGetAllNodesCommand,
            mockHeartbeatCommand,
            mockSendCommandCommand,
            mockProgressCommand,
            mockProgressStreamCommand,
            mockLogsStreamCommand,
            mockHistoryCommand,
            mockSessionApplyCommand,
            mockWorkflowTriggerCommand,
            mockDaemonCommand,
            mockDaemonStartCommand,
            mockDaemonStopCommand,
            mockDaemonStatusCommand,
            mockDaemonManager,
            mockDaemonProperties,
            mockExitHandler);
  }

  @Test
  void constructor_createsInstance() {
    assertThat(cliRunner).isNotNull();
  }

  @Test
  void run_withEmptyArgs_doesNotProcessCommands() {
    final var args = mock(ApplicationArguments.class);
    final var sourceArgs = new String[0];
    when(args.getSourceArgs()).thenReturn(sourceArgs);

    cliRunner.run(args);

    verifyNoInteractions(mockDaemonManager);
  }

  @Test
  void run_withDaemonArgs_processesCommand() {
    final var args = mock(ApplicationArguments.class);
    final var sourceArgs = new String[] {"daemon", "status"};
    when(args.getSourceArgs()).thenReturn(sourceArgs);

    cliRunner.run(args);

    verify(mockExitHandler).exit(0);
  }

  @Test
  void run_withControlArgs_daemonRunning_proceeds() {
    final var args = mock(ApplicationArguments.class);
    final var sourceArgs = new String[] {"control", "nodes"};
    when(args.getSourceArgs()).thenReturn(sourceArgs);
    when(mockDaemonManager.isRunning()).thenReturn(true);

    cliRunner.run(args);

    verify(mockDaemonManager).isRunning();
  }

  @Test
  void run_withControlArgs_daemonNotRunning_exitsWithError() {
    final var args = mock(ApplicationArguments.class);
    final var sourceArgs = new String[] {"control", "heartbeat"};
    when(args.getSourceArgs()).thenReturn(sourceArgs);
    when(mockDaemonManager.isRunning()).thenReturn(false);
    when(mockDaemonProperties.getNotRunningMessage()).thenReturn("Daemon not running");

    cliRunner.run(args);

    verify(mockDaemonManager).isRunning();
    verify(mockDaemonProperties).getNotRunningMessage();
    verify(mockExitHandler).exit(1);
  }

  @Test
  void run_withInvalidCommand_doesNotInteractWithDaemonManager() {
    final var args = mock(ApplicationArguments.class);
    final var sourceArgs = new String[] {"invalid"};
    when(args.getSourceArgs()).thenReturn(sourceArgs);

    cliRunner.run(args);

    verifyNoInteractions(mockDaemonManager);
    verifyNoInteractions(mockExitHandler);
  }

  @Test
  void run_withInvalidFirstArg_returnsEarlyWithoutDaemonInteraction() {
    final var args = mock(ApplicationArguments.class);
    final var sourceArgs = new String[] {"unknown"};
    when(args.getSourceArgs()).thenReturn(sourceArgs);

    cliRunner.run(args);

    verifyNoInteractions(mockDaemonManager);
    verifyNoInteractions(mockExitHandler);
  }

  @Test
  void run_withMultipleInvalidArgs_returnsEarlyWithoutDaemonInteraction() {
    final var args = mock(ApplicationArguments.class);
    final var sourceArgs = new String[] {"unknown", "command"};
    when(args.getSourceArgs()).thenReturn(sourceArgs);

    cliRunner.run(args);

    verifyNoInteractions(mockDaemonManager);
    verifyNoInteractions(mockExitHandler);
  }
}
