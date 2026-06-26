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

import com.infenia.yukta.cli.infrastructure.FileSystemAdapter;
import com.infenia.yukta.cli.infrastructure.HttpClientAdapter;
import com.infenia.yukta.cli.infrastructure.ProcessProvider;
import com.infenia.yukta.cli.infrastructure.SystemEnvironmentProvider;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Manages the Yukta daemon process lifecycle. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DaemonManager {

  /** Daemon configuration properties. */
  private final DaemonProperties props;

  /** HTTP client adapter for health checks. */
  private final HttpClientAdapter httpClientAdapter;

  /** Provider for system environment variables. */
  private final SystemEnvironmentProvider envProvider;

  /** Provider for process operations. */
  private final ProcessProvider processProvider;

  /** Adapter for file system operations. */
  private final FileSystemAdapter fileSystemAdapter;

  /**
   * Checks if the daemon is currently running.
   *
   * @return true if daemon is running, false otherwise
   */
  public boolean isRunning() {
    try {
      return httpHealthCheck();
    } catch (Exception e) {
      log.debug("Health check failed", e);
      return false;
    }
  }

  /**
   * Starts the daemon process.
   *
   * @return the daemon Process, or null if already running
   * @throws IOException if process creation fails
   * @throws DaemonStartupException if startup validation fails
   */
  public Process startDaemon() throws IOException, DaemonStartupException {
    Path pidFilePath = props.getPidFilePath();
    Path logFilePath = props.getLogFilePath();

    try {
      fileSystemAdapter.createDirectories(pidFilePath.getParent());
      fileSystemAdapter.createDirectories(logFilePath.getParent());
    } catch (Exception e) {
      throw new IOException("Failed to create directories", e);
    }

    try (var randomAccessFile = new java.io.RandomAccessFile(pidFilePath.toFile(), "rw");
        FileLock lock = randomAccessFile.getChannel().tryLock()) {

      if (lock == null) {
        throw new DaemonStartupException(
            "Another process is starting the daemon. Please wait and retry.");
      }

      if (isDaemonProcess()) {
        try {
          log.info("Daemon already running with PID {}", readPidFile());
        } catch (Exception e) {
          log.info("Daemon already running (could not read PID: {})", e.getMessage());
        }
        return null;
      }

      Process process;
      try {
        process =
            processProvider.startProcess(
                buildDaemonCommand(), new File("."), logFilePath.toFile(), logFilePath.toFile());
      } catch (Exception e) {
        throw new IOException("Failed to start daemon process", e);
      }
      long pid = process.pid();

      try {
        fileSystemAdapter.writeString(
            pidFilePath,
            String.valueOf(pid),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
      } catch (Exception e) {
        throw new IOException("Failed to write PID file", e);
      }

      log.info("Started daemon process with PID {}", pid);
      return process;

    } catch (OverlappingFileLockException e) {
      throw new DaemonStartupException(
          "Another CLI process is starting the daemon. Please wait and retry.", e);
    }
  }

  /**
   * Waits for the daemon to be ready (health check passes).
   *
   * @throws DaemonStartupException if timeout is exceeded
   * @throws InterruptedException if the wait is interrupted
   */
  public void waitForReady() throws DaemonStartupException, InterruptedException {
    Instant deadline = Instant.now().plusSeconds(props.getStartupTimeoutSeconds());

    while (Instant.now().isBefore(deadline)) {
      try {
        if (httpHealthCheck()) {
          log.info("Daemon is ready");
          return;
        }
      } catch (ConnectException e) {
        log.debug("Daemon not yet responding: {}", e.getMessage());
      } catch (Exception e) {
        log.debug("Health check failed", e);
      }

      Thread.sleep(props.getHealthCheckIntervalMs());
    }

    throw new DaemonStartupException(
        "Daemon failed to start within "
            + props.getStartupTimeoutSeconds()
            + " seconds. Check "
            + props.getLogFile()
            + " for details.");
  }

  /**
   * Ensures the daemon is running, starting it if necessary.
   *
   * @return the daemon URL
   * @throws DaemonStartupException if startup fails
   * @throws InterruptedException if the operation is interrupted
   * @throws IOException if process creation fails
   */
  public String ensureRunning() throws DaemonStartupException, InterruptedException, IOException {
    if (isRunning()) {
      log.info("Using existing daemon at http://127.0.0.1:{}", props.getPort());
      return "http://127.0.0.1:" + props.getPort();
    }

    log.info("Starting daemon...");
    startDaemon();
    waitForReady();
    return "http://127.0.0.1:" + props.getPort();
  }

  /**
   * Stops the daemon process.
   *
   * @return true if daemon was stopped, false if it wasn't running
   */
  public boolean stopDaemon() {
    Path pidFilePath = props.getPidFilePath();

    if (!fileSystemAdapter.exists(pidFilePath)) {
      log.info("No daemon PID file found");
      return false;
    }

    try {
      long pid = readPidFile();
      processProvider
          .findProcess(pid)
          .ifPresent(
              handle -> {
                handle.destroy();
                log.info("Terminated daemon process with PID {}", pid);
              });
      fileSystemAdapter.delete(pidFilePath);
      return true;
    } catch (Exception e) {
      log.warn("Error stopping daemon: {}", e.getMessage());
      try {
        fileSystemAdapter.deleteIfExists(pidFilePath);
      } catch (Exception ex) {
        log.warn("Failed to delete PID file: {}", ex.getMessage());
      }
      return false;
    }
  }

  /**
   * Gets the current status of the daemon.
   *
   * @return the daemon status
   */
  public DaemonStatus status() {
    boolean running = isRunning();
    long pid = -1;
    try {
      pid = readPidFile();
    } catch (Exception ignored) {
      // PID file not readable, return default
    }
    String url = running ? "http://127.0.0.1:" + props.getPort() : null;
    return new DaemonStatus(running, pid, url);
  }

  private boolean isDaemonProcess() {
    try {
      long pid = readPidFile();
      if (processProvider.findProcess(pid).isEmpty()) {
        log.debug("PID {} is stale, cleaning up", pid);
        fileSystemAdapter.deleteIfExists(props.getPidFilePath());
        return false;
      }
      return httpHealthCheck();
    } catch (Exception e) {
      log.debug("Stale PID file detected", e);
      try {
        fileSystemAdapter.deleteIfExists(props.getPidFilePath());
      } catch (Exception ex) {
        log.debug("Failed to delete stale PID file", ex);
      }
      return false;
    }
  }

  private long readPidFile() throws Exception {
    String content = fileSystemAdapter.readString(props.getPidFilePath());
    return Long.parseLong(content.trim());
  }

  private List<String> buildDaemonCommand() throws DaemonStartupException {
    String javaHome =
        envProvider
            .getProperty("java.home")
            .or(() -> envProvider.getProperty("JAVA_HOME"))
            .orElse("/usr/lib/jvm/java");
    String javaExe = javaHome + File.separator + "bin" + File.separator + "java";

    boolean isNative =
        "runtime".equals(envProvider.getProperty("org.graalvm.nativeimage.imagecode").orElse(null));

    if (isNative) {
      String processCmd = processProvider.currentProcessCommand().orElse(null);
      if (processCmd == null) {
        throw new DaemonStartupException("Cannot determine native image command path");
      }
      return List.of(processCmd, "--spring.profiles.active=" + getActiveProfile());
    }

    String jarPath = resolveJarPath();
    return List.of(
        javaExe,
        "-jar",
        jarPath,
        "--spring.profiles.active=" + getActiveProfile(),
        "--server.port=" + props.getPort());
  }

  private String resolveJarPath() throws DaemonStartupException {
    if (props.getJarPath() != null && !props.getJarPath().isEmpty()) {
      return props.getJarPath();
    }

    String classPath = envProvider.getProperty("java.class.path").orElse("");
    String[] entries = classPath.split(File.pathSeparator);
    for (String entry : entries) {
      if (entry.endsWith(".jar")) {
        return entry;
      }
    }

    throw new DaemonStartupException(
        "Cannot determine JAR path. Set yukta.daemon.jar-path in configuration or ensure "
            + "the application runs from the fat JAR.");
  }

  private String getActiveProfile() {
    return envProvider
        .getProperty("spring.profiles.active")
        .or(() -> envProvider.getEnvironment("SPRING_PROFILES_ACTIVE"))
        .orElse("dev");
  }

  private boolean httpHealthCheck() throws Exception {
    return httpClientAdapter.healthCheck(props.getPort());
  }
}
