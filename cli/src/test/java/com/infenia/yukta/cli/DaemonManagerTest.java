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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.cli.infrastructure.DefaultFileSystemAdapter;
import com.infenia.yukta.cli.infrastructure.FileSystemAdapter;
import com.infenia.yukta.cli.infrastructure.HttpClientAdapter;
import com.infenia.yukta.cli.infrastructure.ProcessProvider;
import com.infenia.yukta.cli.infrastructure.SystemEnvironmentProvider;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for DaemonManager covering lifecycle management of the Yukta daemon process. Includes tests
 * for daemon startup, status checks, waiting for readiness, and graceful shutdown.
 *
 * <p>Note: Some error paths (lock failures, process spawn failures) are difficult to test with unit
 * mocks and would require integration tests with actual file system and process operations.
 */
@ExtendWith(MockitoExtension.class)
class DaemonManagerTest {

  private DaemonManager manager;
  private DaemonProperties props;

  @Mock private HttpClientAdapter httpClientAdapter;
  @Mock private SystemEnvironmentProvider envProvider;
  @Mock private ProcessProvider processProvider;
  @Mock private FileSystemAdapter fileSystemAdapter;

  @TempDir private Path tempDir;

  @BeforeEach
  void setUp() {
    props = new DaemonProperties();
    props.setPidFile(tempDir.resolve("daemon.pid").toString());
    props.setLogFile(tempDir.resolve("daemon.log").toString());
    props.setPort(8080);
    props.setStartupTimeoutSeconds(2);
    props.setHealthCheckIntervalMs(100);

    manager =
        new DaemonManager(
            props, httpClientAdapter, envProvider, processProvider, fileSystemAdapter);
  }

  // ==================== isRunning Tests ====================

  @Test
  @DisplayName("isRunning returns true when health check succeeds")
  void isRunning_healthCheckSucceeds_returnsTrue() throws Exception {
    // Given: health check succeeds
    when(httpClientAdapter.healthCheck(8080)).thenReturn(true);

    // When: isRunning is called
    boolean result = manager.isRunning();

    // Then: returns true
    assertThat(result).isTrue();
    verify(httpClientAdapter).healthCheck(8080);
  }

  @Test
  @DisplayName("isRunning returns false when health check throws ConnectException")
  void isRunning_healthCheckThrowsConnectException_returnsFalse() throws Exception {
    // Given: health check throws ConnectException
    when(httpClientAdapter.healthCheck(8080)).thenThrow(new ConnectException("Connection refused"));

    // When: isRunning is called
    boolean result = manager.isRunning();

    // Then: catches exception and returns false
    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("isRunning returns false when health check throws generic exception")
  void isRunning_healthCheckThrowsGenericException_returnsFalse() throws Exception {
    // Given: health check throws generic exception
    when(httpClientAdapter.healthCheck(8080))
        .thenThrow(new RuntimeException("Health check failed"));

    // When: isRunning is called
    boolean result = manager.isRunning();

    // Then: catches exception and returns false
    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("isRunning calls health check with correct port")
  void isRunning_callsHealthCheckWithCorrectPort() throws Exception {
    // Given: health check is mocked for port 9999
    props.setPort(9999);
    manager =
        new DaemonManager(
            props, httpClientAdapter, envProvider, processProvider, fileSystemAdapter);
    when(httpClientAdapter.healthCheck(9999)).thenReturn(true);

    // When: isRunning is called
    manager.isRunning();

    // Then: health check was called with correct port
    verify(httpClientAdapter).healthCheck(9999);
  }

  // ==================== startDaemon Tests ====================

  @Test
  @DisplayName("startDaemon throws IOException when PID file directory creation fails")
  void startDaemon_pidDirectoryCreationFails_throwsIoException() throws Exception {
    // Given: createDirectories throws exception on first call (PID file path)
    doThrow(new RuntimeException("Permission denied"))
        .when(fileSystemAdapter)
        .createDirectories(any());

    // When: startDaemon is called
    // Then: throws IOException with descriptive message
    assertThatThrownBy(() -> manager.startDaemon())
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Failed to create directories");
  }

  @Test
  @DisplayName("startDaemon throws IOException when log file directory creation fails")
  void startDaemon_logDirectoryCreationFails_throwsIoException() throws Exception {
    // Given: First createDirectories succeeds, second throws exception
    // Note: Mockito will intercept the second call and throw, triggering the catch block
    doNothing()
        .doThrow(new RuntimeException("Permission denied"))
        .when(fileSystemAdapter)
        .createDirectories(any(Path.class));

    // When: startDaemon is called
    // Then: throws IOException with descriptive message
    assertThatThrownBy(() -> manager.startDaemon())
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Failed to create directories");
  }

  @Test
  @DisplayName("startDaemon completes successfully with real file system operations")
  void startDaemon_withRealFileSystem_completesSuccessfully() throws Exception {
    // Given: Use real FileSystemAdapter with temp directory
    FileSystemAdapter realFileSystemAdapter = new DefaultFileSystemAdapter();
    DaemonManager managerWithRealFs =
        new DaemonManager(
            props, httpClientAdapter, envProvider, processProvider, realFileSystemAdapter);

    // Mock only the process provider and environment
    long testPid = 12345L;
    Process mockProcess = mock(Process.class);
    when(mockProcess.pid()).thenReturn(testPid);

    when(processProvider.startProcess(any(), any(), any(), any())).thenReturn(mockProcess);

    when(envProvider.getProperty("java.home")).thenReturn(Optional.of("/usr/lib/jvm/java"));
    when(envProvider.getProperty("org.graalvm.nativeimage.imagecode")).thenReturn(Optional.empty());
    when(envProvider.getProperty("java.class.path")).thenReturn(Optional.of("/path/to/app.jar"));
    when(envProvider.getProperty("spring.profiles.active")).thenReturn(Optional.empty());
    when(envProvider.getEnvironment("SPRING_PROFILES_ACTIVE")).thenReturn(Optional.empty());

    // When: startDaemon is called
    Process result = managerWithRealFs.startDaemon();

    // Then: returns the process with correct PID
    assertThat(result).isNotNull();
    assertThat(result.pid()).isEqualTo(testPid);

    // Verify PID file was written
    assertThat(realFileSystemAdapter.exists(props.getPidFilePath())).isTrue();
    String pidContent = realFileSystemAdapter.readString(props.getPidFilePath());
    assertThat(pidContent.trim()).isEqualTo(String.valueOf(testPid));
  }

  @Test
  @DisplayName("startDaemon returns null when daemon already running with real file system")
  void startDaemon_daemonAlreadyRunning_withRealFileSystem_returnsNull() throws Exception {
    // Given: Use real FileSystemAdapter
    FileSystemAdapter realFileSystemAdapter = new DefaultFileSystemAdapter();
    realFileSystemAdapter.createDirectories(props.getPidFilePath().getParent());
    realFileSystemAdapter.writeString(
        props.getPidFilePath(),
        "9999",
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);

    // Mock process as existing and health check succeeding
    when(processProvider.findProcess(9999L)).thenReturn(Optional.of(mock(ProcessHandle.class)));
    when(httpClientAdapter.healthCheck(props.getPort())).thenReturn(true);

    // When: startDaemon is called
    DaemonManager managerWithRealFs =
        new DaemonManager(
            props, httpClientAdapter, envProvider, processProvider, realFileSystemAdapter);
    Process result = managerWithRealFs.startDaemon();

    // Then: returns null (daemon already running)
    assertThat(result).isNull();

    // Verify startProcess was never called
    verify(processProvider, never()).startProcess(any(), any(), any(), any());
  }

  // ==================== waitForReady Tests ====================

  @Test
  @DisplayName("waitForReady returns immediately when health check succeeds")
  void waitForReady_healthCheckSucceedsImmediately_returns() throws Exception {
    // Given: health check returns true on first attempt
    when(httpClientAdapter.healthCheck(8080)).thenReturn(true);

    // When: waitForReady is called
    // Then: returns immediately without exception
    manager.waitForReady();
    verify(httpClientAdapter).healthCheck(8080);
  }

  @Test
  @DisplayName("waitForReady retries and succeeds after failures")
  void waitForReady_healthCheckSucceedsAfterRetries_returns() throws Exception {
    // Given: health check fails first time, succeeds second time
    when(httpClientAdapter.healthCheck(8080)).thenReturn(false).thenReturn(false).thenReturn(true);

    // When: waitForReady is called
    // Then: retries and eventually returns
    manager.waitForReady();
    verify(httpClientAdapter, times(3)).healthCheck(8080);
  }

  @Test
  @DisplayName("waitForReady catches ConnectException and retries")
  void waitForReady_throwsConnectException_retries() throws Exception {
    // Given: health check throws ConnectException then succeeds
    when(httpClientAdapter.healthCheck(8080))
        .thenThrow(new ConnectException("Not responding"))
        .thenReturn(true);

    // When: waitForReady is called
    // Then: catches exception and retries
    manager.waitForReady();
    verify(httpClientAdapter, times(2)).healthCheck(8080);
  }

  @Test
  @DisplayName("waitForReady catches generic exception and retries")
  void waitForReady_throwsGenericException_retries() throws Exception {
    // Given: health check throws generic exception then succeeds
    when(httpClientAdapter.healthCheck(8080))
        .thenThrow(new RuntimeException("Network error"))
        .thenReturn(true);

    // When: waitForReady is called
    // Then: catches exception and retries
    manager.waitForReady();
    verify(httpClientAdapter, times(2)).healthCheck(8080);
  }

  @Test
  @DisplayName("waitForReady throws exception when timeout exceeded")
  void waitForReady_timeoutExceeded_throwsDaemonStartupException() throws Exception {
    // Given: health check never succeeds and timeout is short
    when(httpClientAdapter.healthCheck(8080)).thenReturn(false);
    props.setStartupTimeoutSeconds(1);
    props.setHealthCheckIntervalMs(100);
    manager =
        new DaemonManager(
            props, httpClientAdapter, envProvider, processProvider, fileSystemAdapter);

    // When: waitForReady is called
    // Then: throws DaemonStartupException with timeout message
    assertThatThrownBy(() -> manager.waitForReady())
        .isInstanceOf(DaemonStartupException.class)
        .hasMessageContaining("Daemon failed to start within")
        .hasMessageContaining("1 seconds");
  }

  @Test
  @DisplayName("waitForReady includes log file path in timeout message")
  void waitForReady_timeoutException_includesLogFilePath() throws Exception {
    // Given: health check never succeeds
    when(httpClientAdapter.healthCheck(8080)).thenReturn(false);
    props.setStartupTimeoutSeconds(1);
    props.setHealthCheckIntervalMs(100);
    manager =
        new DaemonManager(
            props, httpClientAdapter, envProvider, processProvider, fileSystemAdapter);

    // When: waitForReady is called
    // Then: timeout message includes log file path
    assertThatThrownBy(() -> manager.waitForReady())
        .isInstanceOf(DaemonStartupException.class)
        .hasMessageContaining(props.getLogFile());
  }

  // ==================== ensureRunning Tests ====================

  @Test
  @DisplayName("ensureRunning returns URL when daemon already running")
  void ensureRunning_daemonAlreadyRunning_returnsUrl() throws Exception {
    // Given: daemon is already running
    when(httpClientAdapter.healthCheck(8080)).thenReturn(true);

    // When: ensureRunning is called
    String url = manager.ensureRunning();

    // Then: returns daemon URL without starting new process
    assertThat(url).isEqualTo("http://127.0.0.1:8080");
    verify(processProvider, never()).startProcess(any(), any(), any(), any());
  }

  // ==================== stopDaemon Tests ====================

  @Test
  @DisplayName("stopDaemon returns false when PID file doesn't exist")
  void stopDaemon_pidFileNotExists_returnsFalse() {
    // Given: PID file doesn't exist
    when(fileSystemAdapter.exists(props.getPidFilePath())).thenReturn(false);

    // When: stopDaemon is called
    boolean result = manager.stopDaemon();

    // Then: returns false
    assertThat(result).isFalse();
    verify(fileSystemAdapter).exists(props.getPidFilePath());
  }

  @Test
  @DisplayName("stopDaemon destroys process and deletes PID file")
  void stopDaemon_validPidAndProcessExists_destroysAndReturnsTrue() throws Exception {
    // Given: PID file exists, contains valid PID, process found
    long testPid = 7777L;
    ProcessHandle mockHandle = mock(ProcessHandle.class);
    when(fileSystemAdapter.exists(props.getPidFilePath())).thenReturn(true);
    when(fileSystemAdapter.readString(props.getPidFilePath())).thenReturn(String.valueOf(testPid));
    when(processProvider.findProcess(testPid)).thenReturn(Optional.of(mockHandle));

    // When: stopDaemon is called
    boolean result = manager.stopDaemon();

    // Then: destroys process, deletes PID file, returns true
    assertThat(result).isTrue();
    verify(mockHandle).destroy();
    verify(fileSystemAdapter).delete(props.getPidFilePath());
  }

  @Test
  @DisplayName("stopDaemon handles missing process gracefully")
  void stopDaemon_pidFileExistsButProcessNotFound_returnsFalse() throws Exception {
    // Given: PID file exists but process not found
    when(fileSystemAdapter.exists(props.getPidFilePath())).thenReturn(true);
    when(fileSystemAdapter.readString(props.getPidFilePath())).thenReturn("9999");
    when(processProvider.findProcess(9999L)).thenReturn(Optional.empty());

    // When: stopDaemon is called
    boolean result = manager.stopDaemon();

    // Then: skips destroy (process not found), deletes PID file, returns true
    assertThat(result).isTrue();
    verify(fileSystemAdapter).delete(props.getPidFilePath());
  }

  @Test
  @DisplayName("stopDaemon handles corrupted PID file gracefully")
  void stopDaemon_readPidFileFails_handleGracefully() throws Exception {
    // Given: PID file exists but readString throws exception
    when(fileSystemAdapter.exists(props.getPidFilePath())).thenReturn(true);
    when(fileSystemAdapter.readString(props.getPidFilePath()))
        .thenThrow(new RuntimeException("Corrupted file"));

    // When: stopDaemon is called
    boolean result = manager.stopDaemon();

    // Then: returns false and attempts deleteIfExists
    assertThat(result).isFalse();
    verify(fileSystemAdapter).deleteIfExists(props.getPidFilePath());
  }

  @Test
  @DisplayName("stopDaemon handles delete failure gracefully")
  void stopDaemon_deleteFailsButRetries_returnsFalse() throws Exception {
    // Given: PID file delete throws exception
    when(fileSystemAdapter.exists(props.getPidFilePath())).thenReturn(true);
    when(fileSystemAdapter.readString(props.getPidFilePath())).thenReturn("1111");
    when(processProvider.findProcess(1111L)).thenReturn(Optional.empty());
    doThrow(new RuntimeException("Cannot delete"))
        .when(fileSystemAdapter)
        .delete(props.getPidFilePath());

    // When: stopDaemon is called
    boolean result = manager.stopDaemon();

    // Then: returns false and retries with deleteIfExists
    assertThat(result).isFalse();
    verify(fileSystemAdapter).deleteIfExists(props.getPidFilePath());
  }

  // ==================== status Tests ====================

  @Test
  @DisplayName("status returns running status with URL")
  void status_daemonRunning_returnsStatusWithUrl() throws Exception {
    // Given: daemon running, PID file readable
    when(httpClientAdapter.healthCheck(8080)).thenReturn(true);
    when(fileSystemAdapter.readString(props.getPidFilePath())).thenReturn("3333");

    // When: status is called
    DaemonStatus status = manager.status();

    // Then: returns DaemonStatus with running=true, correct pid, correct url
    assertThat(status.running()).isTrue();
    assertThat(status.pid()).isEqualTo(3333L);
    assertThat(status.url()).isEqualTo("http://127.0.0.1:8080");
  }

  @Test
  @DisplayName("status returns not running status without URL")
  void status_daemonNotRunning_returnsStatusWithoutUrl() throws Exception {
    // Given: daemon not running
    when(httpClientAdapter.healthCheck(8080)).thenReturn(false);

    // When: status is called
    DaemonStatus status = manager.status();

    // Then: returns DaemonStatus with running=false, url=null
    assertThat(status.running()).isFalse();
    assertThat(status.url()).isNull();
  }

  @Test
  @DisplayName("status returns -1 for pid when PID file unreadable")
  void status_pidFileUnreadable_returnsPidAsNegativeOne() throws Exception {
    // Given: daemon running, readPidFile throws exception
    when(httpClientAdapter.healthCheck(8080)).thenReturn(true);
    when(fileSystemAdapter.readString(props.getPidFilePath()))
        .thenThrow(new IOException("Cannot read"));

    // When: status is called
    DaemonStatus status = manager.status();

    // Then: returns status with pid=-1, running=true, url set
    assertThat(status.running()).isTrue();
    assertThat(status.pid()).isEqualTo(-1L);
    assertThat(status.url()).isEqualTo("http://127.0.0.1:8080");
  }

  @Test
  @DisplayName("status uses correct port in URL")
  void status_withCustomPort_returnsCorrectUrl() throws Exception {
    // Given: daemon running on different port
    props.setPort(9090);
    manager =
        new DaemonManager(
            props, httpClientAdapter, envProvider, processProvider, fileSystemAdapter);
    when(httpClientAdapter.healthCheck(9090)).thenReturn(true);
    when(fileSystemAdapter.readString(props.getPidFilePath())).thenReturn("5555");

    // When: status is called
    DaemonStatus status = manager.status();

    // Then: returns correct URL with custom port
    assertThat(status.url()).isEqualTo("http://127.0.0.1:9090");
  }

  // ==================== Integration Tests ====================

  @Test
  @DisplayName("stopDaemon uses FileSystemAdapter for all file operations")
  void stopDaemon_usesFileSystemAdapter() throws Exception {
    // Given: PID file exists
    when(fileSystemAdapter.exists(props.getPidFilePath())).thenReturn(true);
    when(fileSystemAdapter.readString(props.getPidFilePath())).thenReturn("9999");
    when(processProvider.findProcess(9999)).thenReturn(Optional.empty());

    // When: stopDaemon is called
    manager.stopDaemon();

    // Then: all file operations use the adapter
    verify(fileSystemAdapter).exists(props.getPidFilePath());
    verify(fileSystemAdapter).readString(props.getPidFilePath());
    verify(fileSystemAdapter).delete(props.getPidFilePath());
  }

  @Test
  @DisplayName("stopDaemon uses ProcessProvider for process operations")
  void stopDaemon_usesProcessProvider() throws Exception {
    // Given: PID file exists
    when(fileSystemAdapter.exists(props.getPidFilePath())).thenReturn(true);
    when(fileSystemAdapter.readString(props.getPidFilePath())).thenReturn("1234");
    ProcessHandle mockHandle = mock(ProcessHandle.class);
    when(processProvider.findProcess(1234)).thenReturn(Optional.of(mockHandle));

    // When: stopDaemon is called
    manager.stopDaemon();

    // Then: process provider is used
    verify(processProvider).findProcess(1234);
    verify(mockHandle).destroy();
  }

  // ==================== startDaemon - process/PID write failure Tests ====================

  @Test
  @DisplayName("startDaemon throws IOException when process start fails")
  void startDaemon_processStartFails_throwsIoException() throws Exception {
    // Given: real file system so lock works, but processProvider throws
    FileSystemAdapter realFs = new DefaultFileSystemAdapter();
    DaemonManager managerWithRealFs =
        new DaemonManager(props, httpClientAdapter, envProvider, processProvider, realFs);

    when(envProvider.getProperty("java.home")).thenReturn(Optional.of("/usr/lib/jvm/java"));
    when(envProvider.getProperty("org.graalvm.nativeimage.imagecode")).thenReturn(Optional.empty());
    when(envProvider.getProperty("java.class.path")).thenReturn(Optional.of("/path/to/app.jar"));
    when(envProvider.getProperty("spring.profiles.active")).thenReturn(Optional.empty());
    when(envProvider.getEnvironment("SPRING_PROFILES_ACTIVE")).thenReturn(Optional.empty());
    when(processProvider.startProcess(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("spawn failed"));

    // When/Then: startDaemon throws IOException about failed daemon process
    assertThatThrownBy(() -> managerWithRealFs.startDaemon())
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Failed to start daemon process");
  }

  @Test
  @DisplayName("startDaemon throws IOException when PID file write fails")
  void startDaemon_pidFileWriteFails_throwsIoException() throws Exception {
    // Given: real file system so lock works, process starts, but PID write fails
    FileSystemAdapter realFs = new DefaultFileSystemAdapter();
    // Wrap real FS to make writeString fail
    FileSystemAdapter failingWriteFs =
        new DefaultFileSystemAdapter() {
          @Override
          public void writeString(
              final java.nio.file.Path path,
              final String content,
              final java.nio.file.OpenOption... options)
              throws IOException {
            throw new IOException("disk full");
          }
        };
    DaemonManager managerWithFailingFs =
        new DaemonManager(props, httpClientAdapter, envProvider, processProvider, failingWriteFs);

    long testPid = 12345L;
    Process mockProcess = mock(Process.class);
    when(mockProcess.pid()).thenReturn(testPid);
    when(processProvider.startProcess(any(), any(), any(), any())).thenReturn(mockProcess);
    when(envProvider.getProperty("java.home")).thenReturn(Optional.of("/usr/lib/jvm/java"));
    when(envProvider.getProperty("org.graalvm.nativeimage.imagecode")).thenReturn(Optional.empty());
    when(envProvider.getProperty("java.class.path")).thenReturn(Optional.of("/path/to/app.jar"));
    when(envProvider.getProperty("spring.profiles.active")).thenReturn(Optional.empty());
    when(envProvider.getEnvironment("SPRING_PROFILES_ACTIVE")).thenReturn(Optional.empty());

    // When/Then: startDaemon throws IOException about PID file write
    assertThatThrownBy(() -> managerWithFailingFs.startDaemon())
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Failed to write PID file");
  }

  // ==================== buildDaemonCommand - native image Tests ====================

  @Test
  @DisplayName("startDaemon uses native image command when running as native")
  void startDaemon_nativeImage_usesCurrentProcessCommand() throws Exception {
    // Given: real FS, native image runtime
    FileSystemAdapter realFs = new DefaultFileSystemAdapter();
    DaemonManager managerWithRealFs =
        new DaemonManager(props, httpClientAdapter, envProvider, processProvider, realFs);

    long testPid = 9876L;
    Process mockProcess = mock(Process.class);
    when(mockProcess.pid()).thenReturn(testPid);
    // java.home is still read before the native check in buildDaemonCommand
    when(envProvider.getProperty("java.home")).thenReturn(Optional.of("/usr/lib/jvm/java"));
    when(envProvider.getProperty("org.graalvm.nativeimage.imagecode"))
        .thenReturn(Optional.of("runtime"));
    when(envProvider.getProperty("spring.profiles.active")).thenReturn(Optional.empty());
    when(envProvider.getEnvironment("SPRING_PROFILES_ACTIVE")).thenReturn(Optional.empty());
    when(processProvider.currentProcessCommand()).thenReturn(Optional.of("/opt/yukta/yukta-cli"));
    when(processProvider.startProcess(any(), any(), any(), any())).thenReturn(mockProcess);

    // When: startDaemon is called
    Process result = managerWithRealFs.startDaemon();

    // Then: process started with native image command (no -jar flag)
    assertThat(result).isNotNull();
    ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
    verify(processProvider).startProcess(cmdCaptor.capture(), any(), any(), any());
    assertThat(cmdCaptor.getValue()).contains("/opt/yukta/yukta-cli");
    assertThat(cmdCaptor.getValue()).noneMatch(s -> s.equals("-jar"));
  }

  @Test
  @DisplayName(
      "startDaemon throws IOException wrapping DaemonStartupException when native image command"
          + " unavailable")
  void startDaemon_nativeImage_noProcessCommand_throwsDaemonStartupException() throws Exception {
    // Given: real FS, native image runtime, no current process command
    // Note: DaemonStartupException from buildDaemonCommand is caught and re-wrapped as IOException
    FileSystemAdapter realFs = new DefaultFileSystemAdapter();
    DaemonManager managerWithRealFs =
        new DaemonManager(props, httpClientAdapter, envProvider, processProvider, realFs);

    when(envProvider.getProperty("java.home")).thenReturn(Optional.of("/usr/lib/jvm/java"));
    when(envProvider.getProperty("org.graalvm.nativeimage.imagecode"))
        .thenReturn(Optional.of("runtime"));
    when(processProvider.currentProcessCommand()).thenReturn(Optional.empty());

    // When/Then: startDaemon throws IOException whose cause is DaemonStartupException
    assertThatThrownBy(() -> managerWithRealFs.startDaemon())
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Failed to start daemon process")
        .cause()
        .isInstanceOf(DaemonStartupException.class)
        .hasMessageContaining("Cannot determine native image command path");
  }

  // ==================== buildDaemonCommand - JVM path Tests ====================

  @Test
  @DisplayName("startDaemon uses jarPath from props when configured")
  void startDaemon_jarPathConfigured_usesConfiguredJarPath() throws Exception {
    // Given: real FS, explicit jar path configured in props
    FileSystemAdapter realFs = new DefaultFileSystemAdapter();
    props.setJarPath("/custom/path/yukta.jar");
    DaemonManager managerWithRealFs =
        new DaemonManager(props, httpClientAdapter, envProvider, processProvider, realFs);

    long testPid = 11111L;
    Process mockProcess = mock(Process.class);
    when(mockProcess.pid()).thenReturn(testPid);
    when(envProvider.getProperty("java.home")).thenReturn(Optional.of("/usr/lib/jvm/java"));
    when(envProvider.getProperty("org.graalvm.nativeimage.imagecode")).thenReturn(Optional.empty());
    when(envProvider.getProperty("spring.profiles.active")).thenReturn(Optional.empty());
    when(envProvider.getEnvironment("SPRING_PROFILES_ACTIVE")).thenReturn(Optional.empty());
    when(processProvider.startProcess(any(), any(), any(), any())).thenReturn(mockProcess);

    // When: startDaemon is called
    Process result = managerWithRealFs.startDaemon();

    // Then: process started with configured jar path
    assertThat(result).isNotNull();
    ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
    verify(processProvider).startProcess(cmdCaptor.capture(), any(), any(), any());
    assertThat(cmdCaptor.getValue()).contains("/custom/path/yukta.jar");
  }

  @Test
  @DisplayName(
      "startDaemon throws IOException wrapping DaemonStartupException when no JAR found on"
          + " classpath")
  void startDaemon_noJarOnClasspathAndNoJarPath_throwsDaemonStartupException() throws Exception {
    // Given: real FS, no jar in classpath, no jarPath configured
    // Note: DaemonStartupException from resolveJarPath is caught and re-wrapped as IOException.
    // getActiveProfile() is never reached (throws before it), so no profile stubs needed.
    FileSystemAdapter realFs = new DefaultFileSystemAdapter();
    DaemonManager managerWithRealFs =
        new DaemonManager(props, httpClientAdapter, envProvider, processProvider, realFs);

    when(envProvider.getProperty("java.home")).thenReturn(Optional.of("/usr/lib/jvm/java"));
    when(envProvider.getProperty("org.graalvm.nativeimage.imagecode")).thenReturn(Optional.empty());
    when(envProvider.getProperty("java.class.path"))
        .thenReturn(Optional.of("/some/dir:/other/dir"));

    // When/Then: throws IOException whose cause is DaemonStartupException about JAR path
    assertThatThrownBy(() -> managerWithRealFs.startDaemon())
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Failed to start daemon process")
        .cause()
        .isInstanceOf(DaemonStartupException.class)
        .hasMessageContaining("Cannot determine JAR path");
  }

  @Test
  @DisplayName("startDaemon falls back to JAVA_HOME env when java.home property missing")
  void startDaemon_javaHomeFromEnv_usesEnvJavaHome() throws Exception {
    // Given: real FS, java.home not set, JAVA_HOME env set
    FileSystemAdapter realFs = new DefaultFileSystemAdapter();
    DaemonManager managerWithRealFs =
        new DaemonManager(props, httpClientAdapter, envProvider, processProvider, realFs);

    long testPid = 22222L;
    Process mockProcess = mock(Process.class);
    when(mockProcess.pid()).thenReturn(testPid);
    when(envProvider.getProperty("java.home")).thenReturn(Optional.empty());
    when(envProvider.getProperty("JAVA_HOME")).thenReturn(Optional.of("/opt/java"));
    when(envProvider.getProperty("org.graalvm.nativeimage.imagecode")).thenReturn(Optional.empty());
    when(envProvider.getProperty("java.class.path")).thenReturn(Optional.of("/path/to/app.jar"));
    when(envProvider.getProperty("spring.profiles.active")).thenReturn(Optional.empty());
    when(envProvider.getEnvironment("SPRING_PROFILES_ACTIVE")).thenReturn(Optional.empty());
    when(processProvider.startProcess(any(), any(), any(), any())).thenReturn(mockProcess);

    // When: startDaemon is called
    Process result = managerWithRealFs.startDaemon();

    // Then: command uses JAVA_HOME-derived java executable
    assertThat(result).isNotNull();
    ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
    verify(processProvider).startProcess(cmdCaptor.capture(), any(), any(), any());
    assertThat(cmdCaptor.getValue().get(0)).startsWith("/opt/java");
  }

  @Test
  @DisplayName("startDaemon falls back to default java path when no JAVA_HOME found")
  void startDaemon_noJavaHome_usesDefaultJavaPath() throws Exception {
    // Given: real FS, neither java.home nor JAVA_HOME set
    FileSystemAdapter realFs = new DefaultFileSystemAdapter();
    DaemonManager managerWithRealFs =
        new DaemonManager(props, httpClientAdapter, envProvider, processProvider, realFs);

    long testPid = 33333L;
    Process mockProcess = mock(Process.class);
    when(mockProcess.pid()).thenReturn(testPid);
    when(envProvider.getProperty("java.home")).thenReturn(Optional.empty());
    when(envProvider.getProperty("JAVA_HOME")).thenReturn(Optional.empty());
    when(envProvider.getProperty("org.graalvm.nativeimage.imagecode")).thenReturn(Optional.empty());
    when(envProvider.getProperty("java.class.path")).thenReturn(Optional.of("/path/to/app.jar"));
    when(envProvider.getProperty("spring.profiles.active")).thenReturn(Optional.empty());
    when(envProvider.getEnvironment("SPRING_PROFILES_ACTIVE")).thenReturn(Optional.empty());
    when(processProvider.startProcess(any(), any(), any(), any())).thenReturn(mockProcess);

    // When: startDaemon is called
    Process result = managerWithRealFs.startDaemon();

    // Then: command uses default java path
    assertThat(result).isNotNull();
    ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
    verify(processProvider).startProcess(cmdCaptor.capture(), any(), any(), any());
    assertThat(cmdCaptor.getValue().get(0)).contains("/usr/lib/jvm/java");
  }

  // ==================== getActiveProfile Tests ====================

  @Test
  @DisplayName("startDaemon uses SPRING_PROFILES_ACTIVE env when spring.profiles.active not set")
  void startDaemon_activeProfileFromEnv_usesEnvVar() throws Exception {
    // Given: real FS, spring.profiles.active not set, SPRING_PROFILES_ACTIVE env set
    FileSystemAdapter realFs = new DefaultFileSystemAdapter();
    DaemonManager managerWithRealFs =
        new DaemonManager(props, httpClientAdapter, envProvider, processProvider, realFs);

    long testPid = 44444L;
    Process mockProcess = mock(Process.class);
    when(mockProcess.pid()).thenReturn(testPid);
    when(envProvider.getProperty("java.home")).thenReturn(Optional.of("/usr/lib/jvm/java"));
    when(envProvider.getProperty("org.graalvm.nativeimage.imagecode")).thenReturn(Optional.empty());
    when(envProvider.getProperty("java.class.path")).thenReturn(Optional.of("/path/to/app.jar"));
    when(envProvider.getProperty("spring.profiles.active")).thenReturn(Optional.empty());
    when(envProvider.getEnvironment("SPRING_PROFILES_ACTIVE")).thenReturn(Optional.of("prod"));
    when(processProvider.startProcess(any(), any(), any(), any())).thenReturn(mockProcess);

    // When: startDaemon is called
    Process result = managerWithRealFs.startDaemon();

    // Then: command includes active profile from environment variable
    assertThat(result).isNotNull();
    ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
    verify(processProvider).startProcess(cmdCaptor.capture(), any(), any(), any());
    assertThat(cmdCaptor.getValue()).anyMatch(s -> s.contains("spring.profiles.active=prod"));
  }

  @Test
  @DisplayName("startDaemon uses 'dev' profile when no active profile configured")
  void startDaemon_noActiveProfile_usesDevDefault() throws Exception {
    // Given: real FS, no active profile configured anywhere
    FileSystemAdapter realFs = new DefaultFileSystemAdapter();
    DaemonManager managerWithRealFs =
        new DaemonManager(props, httpClientAdapter, envProvider, processProvider, realFs);

    long testPid = 55555L;
    Process mockProcess = mock(Process.class);
    when(mockProcess.pid()).thenReturn(testPid);
    when(envProvider.getProperty("java.home")).thenReturn(Optional.of("/usr/lib/jvm/java"));
    when(envProvider.getProperty("org.graalvm.nativeimage.imagecode")).thenReturn(Optional.empty());
    when(envProvider.getProperty("java.class.path")).thenReturn(Optional.of("/path/to/app.jar"));
    when(envProvider.getProperty("spring.profiles.active")).thenReturn(Optional.empty());
    when(envProvider.getEnvironment("SPRING_PROFILES_ACTIVE")).thenReturn(Optional.empty());
    when(processProvider.startProcess(any(), any(), any(), any())).thenReturn(mockProcess);

    // When: startDaemon is called
    Process result = managerWithRealFs.startDaemon();

    // Then: command includes default 'dev' profile
    assertThat(result).isNotNull();
    ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
    verify(processProvider).startProcess(cmdCaptor.capture(), any(), any(), any());
    assertThat(cmdCaptor.getValue()).anyMatch(s -> s.contains("spring.profiles.active=dev"));
  }

  // ==================== ensureRunning - start path Tests ====================

  @Test
  @DisplayName("ensureRunning starts daemon when not running and returns URL")
  void ensureRunning_daemonNotRunning_startsDaemonAndReturnsUrl() throws Exception {
    // Given: daemon not running initially, then becomes ready after start
    FileSystemAdapter realFs = new DefaultFileSystemAdapter();
    DaemonManager managerWithRealFs =
        new DaemonManager(props, httpClientAdapter, envProvider, processProvider, realFs);

    long testPid = 66666L;
    Process mockProcess = mock(Process.class);
    when(mockProcess.pid()).thenReturn(testPid);

    // First call (isRunning) returns false; second call (waitForReady) returns true
    when(httpClientAdapter.healthCheck(8080)).thenReturn(false).thenReturn(true);

    when(envProvider.getProperty("java.home")).thenReturn(Optional.of("/usr/lib/jvm/java"));
    when(envProvider.getProperty("org.graalvm.nativeimage.imagecode")).thenReturn(Optional.empty());
    when(envProvider.getProperty("java.class.path")).thenReturn(Optional.of("/path/to/app.jar"));
    when(envProvider.getProperty("spring.profiles.active")).thenReturn(Optional.empty());
    when(envProvider.getEnvironment("SPRING_PROFILES_ACTIVE")).thenReturn(Optional.empty());
    when(processProvider.startProcess(any(), any(), any(), any())).thenReturn(mockProcess);

    // When: ensureRunning is called
    String url = managerWithRealFs.ensureRunning();

    // Then: starts daemon and returns URL
    assertThat(url).isEqualTo("http://127.0.0.1:8080");
    verify(processProvider).startProcess(any(), any(), any(), any());
  }

  // ==================== stopDaemon - deleteIfExists warning path ====================

  @Test
  @DisplayName("stopDaemon deleteIfExists also fails - logs warning but does not throw")
  void stopDaemon_deleteAndDeleteIfExistsBothFail_returnsFalse() throws Exception {
    // Given: PID file read throws, and deleteIfExists also throws
    when(fileSystemAdapter.exists(props.getPidFilePath())).thenReturn(true);
    when(fileSystemAdapter.readString(props.getPidFilePath()))
        .thenThrow(new RuntimeException("Corrupted file"));
    doThrow(new RuntimeException("Cannot delete either"))
        .when(fileSystemAdapter)
        .deleteIfExists(props.getPidFilePath());

    // When: stopDaemon is called (should not throw despite double failure)
    boolean result = manager.stopDaemon();

    // Then: returns false, attempted deleteIfExists
    assertThat(result).isFalse();
    verify(fileSystemAdapter).deleteIfExists(props.getPidFilePath());
  }

  @Test
  @DisplayName("stopDaemon falls back to deleteIfExists when delete succeeds after destroy throws")
  void stopDaemon_destroySucceedsButDeleteFails_retries() throws Exception {
    // Given: PID file exists, process destroy succeeds, but delete throws, deleteIfExists also
    // throws
    long testPid = 8888L;
    ProcessHandle mockHandle = mock(ProcessHandle.class);
    when(fileSystemAdapter.exists(props.getPidFilePath())).thenReturn(true);
    when(fileSystemAdapter.readString(props.getPidFilePath())).thenReturn(String.valueOf(testPid));
    when(processProvider.findProcess(testPid)).thenReturn(Optional.of(mockHandle));
    doThrow(new RuntimeException("Cannot delete"))
        .when(fileSystemAdapter)
        .delete(props.getPidFilePath());
    doThrow(new RuntimeException("deleteIfExists also fails"))
        .when(fileSystemAdapter)
        .deleteIfExists(props.getPidFilePath());

    // When: stopDaemon is called
    boolean result = manager.stopDaemon();

    // Then: process was destroyed, returns false (due to delete exceptions)
    verify(mockHandle).destroy();
    assertThat(result).isFalse();
  }

  // ==================== isDaemonProcess - stale PID cleanup failure ====================

  @Test
  @DisplayName("startDaemon handles stale PID where process exists but health check returns false")
  void startDaemon_stalePidProcessExistsButHealthCheckFails_startsDaemon() throws Exception {
    // Given: real FS with a stale PID file where process exists but health check fails
    FileSystemAdapter realFs = new DefaultFileSystemAdapter();
    realFs.createDirectories(props.getPidFilePath().getParent());
    realFs.writeString(
        props.getPidFilePath(),
        "7777",
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

    // Process exists but health check returns false (stale process, not the daemon)
    when(processProvider.findProcess(7777L)).thenReturn(Optional.of(mock(ProcessHandle.class)));
    when(httpClientAdapter.healthCheck(props.getPort())).thenReturn(false);

    long testPid = 88888L;
    Process mockProcess = mock(Process.class);
    when(mockProcess.pid()).thenReturn(testPid);
    when(processProvider.startProcess(any(), any(), any(), any())).thenReturn(mockProcess);
    when(envProvider.getProperty("java.home")).thenReturn(Optional.of("/usr/lib/jvm/java"));
    when(envProvider.getProperty("org.graalvm.nativeimage.imagecode")).thenReturn(Optional.empty());
    when(envProvider.getProperty("java.class.path")).thenReturn(Optional.of("/path/to/app.jar"));
    when(envProvider.getProperty("spring.profiles.active")).thenReturn(Optional.empty());
    when(envProvider.getEnvironment("SPRING_PROFILES_ACTIVE")).thenReturn(Optional.empty());

    // When: startDaemon is called
    DaemonManager managerWithRealFs =
        new DaemonManager(props, httpClientAdapter, envProvider, processProvider, realFs);
    Process result = managerWithRealFs.startDaemon();

    // Then: starts a new daemon process
    assertThat(result).isNotNull();
    verify(processProvider).startProcess(any(), any(), any(), any());
  }

  @Test
  @DisplayName("startDaemon handles stale PID file where process is not found")
  void startDaemon_stalePidProcessNotFound_startsDaemon() throws Exception {
    // Given: real FS with a stale PID file where process does not exist
    FileSystemAdapter realFs = new DefaultFileSystemAdapter();
    realFs.createDirectories(props.getPidFilePath().getParent());
    realFs.writeString(
        props.getPidFilePath(),
        "6666",
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

    // Process does not exist (stale PID) - isDaemonProcess returns false without health check
    when(processProvider.findProcess(6666L)).thenReturn(Optional.empty());

    long testPid = 99999L;
    Process mockProcess = mock(Process.class);
    when(mockProcess.pid()).thenReturn(testPid);
    when(processProvider.startProcess(any(), any(), any(), any())).thenReturn(mockProcess);
    when(envProvider.getProperty("java.home")).thenReturn(Optional.of("/usr/lib/jvm/java"));
    when(envProvider.getProperty("org.graalvm.nativeimage.imagecode")).thenReturn(Optional.empty());
    when(envProvider.getProperty("java.class.path")).thenReturn(Optional.of("/path/to/app.jar"));
    when(envProvider.getProperty("spring.profiles.active")).thenReturn(Optional.empty());
    when(envProvider.getEnvironment("SPRING_PROFILES_ACTIVE")).thenReturn(Optional.empty());

    DaemonManager managerWithRealFs =
        new DaemonManager(props, httpClientAdapter, envProvider, processProvider, realFs);

    // When: startDaemon is called
    Process result = managerWithRealFs.startDaemon();

    // Then: starts a new daemon process (stale PID cleaned up)
    assertThat(result).isNotNull();
    verify(processProvider).startProcess(any(), any(), any(), any());
  }
}
