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

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for daemon management. */
@ConfigurationProperties(prefix = "yukta.daemon")
@Data
@NoArgsConstructor
public class DaemonProperties {

  /** Port on which the daemon listens. */
  private int port = 8080;

  /** Path to the daemon PID file. */
  private String pidFile;

  /** Path to the daemon log file. */
  private String logFile;

  /** Timeout in seconds for daemon startup. */
  private int startupTimeoutSeconds = 30;

  /** Interval in milliseconds for health checks. */
  private int healthCheckIntervalMs = 500;

  /** Path to the JAR file for daemon execution. */
  private String jarPath;

  /** Message to display when daemon is not running. */
  private String notRunningMessage = "Daemon is not running. Start it with: yukta daemon start";

  /** Initializes default property values if not set. */
  @PostConstruct
  public void setDefaults() {
    if (pidFile == null) {
      pidFile = "/tmp/.yukta/daemon.pid";
    }
    if (logFile == null) {
      logFile = "/tmp/.yukta/daemon.log";
    }
  }

  /**
   * Gets the path to the PID file.
   *
   * @return the PID file path
   */
  public Path getPidFilePath() {
    return Path.of(pidFile);
  }

  /**
   * Gets the path to the log file.
   *
   * @return the log file path
   */
  public Path getLogFilePath() {
    return Path.of(logFile);
  }
}
