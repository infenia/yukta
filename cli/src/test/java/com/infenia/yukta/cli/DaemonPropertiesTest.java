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

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DaemonPropertiesTest {

  private DaemonProperties properties;

  @BeforeEach
  void setUp() {
    properties = new DaemonProperties();
  }

  @Test
  void constructor_createsInstanceWithDefaultValues() {
    assertThat(properties.getPort()).isEqualTo(8080);
    assertThat(properties.getStartupTimeoutSeconds()).isEqualTo(30);
    assertThat(properties.getHealthCheckIntervalMs()).isEqualTo(500);
    assertThat(properties.getPidFile()).isNull();
    assertThat(properties.getLogFile()).isNull();
    assertThat(properties.getJarPath()).isNull();
  }

  @Test
  void setDefaults_withNullPidFile_setsDefaultPath() {
    properties.setDefaults();

    assertThat(properties.getPidFile()).isEqualTo("/tmp/.yukta/daemon.pid");
  }

  @Test
  void setDefaults_withNullLogFile_setsDefaultPath() {
    properties.setDefaults();

    assertThat(properties.getLogFile()).isEqualTo("/tmp/.yukta/daemon.log");
  }

  @Test
  void setDefaults_withBothNullFields_setsDefaultsForBoth() {
    assertThat(properties.getPidFile()).isNull();
    assertThat(properties.getLogFile()).isNull();

    properties.setDefaults();

    assertThat(properties.getPidFile()).isEqualTo("/tmp/.yukta/daemon.pid");
    assertThat(properties.getLogFile()).isEqualTo("/tmp/.yukta/daemon.log");
  }

  @Test
  void setDefaults_withExistingPidFile_preservesValue() {
    properties.setPidFile("/custom/path/daemon.pid");
    properties.setDefaults();

    assertThat(properties.getPidFile()).isEqualTo("/custom/path/daemon.pid");
  }

  @Test
  void setDefaults_withExistingLogFile_preservesValue() {
    properties.setLogFile("/custom/path/daemon.log");
    properties.setDefaults();

    assertThat(properties.getLogFile()).isEqualTo("/custom/path/daemon.log");
  }

  @Test
  void setDefaults_calledMultipleTimes_preservesCustomValues() {
    properties.setPidFile("/custom/pid");
    properties.setLogFile("/custom/log");

    properties.setDefaults();
    properties.setDefaults();

    assertThat(properties.getPidFile()).isEqualTo("/custom/pid");
    assertThat(properties.getLogFile()).isEqualTo("/custom/log");
  }

  @Test
  void getPidFilePath_returnsPathObject() {
    properties.setDefaults();
    Path pidFilePath = properties.getPidFilePath();

    assertThat(pidFilePath).isEqualTo(Path.of("/tmp/.yukta/daemon.pid"));
  }

  @Test
  void getPidFilePath_withCustomPath_returnsCorrectPath() {
    properties.setPidFile("/custom/pid/path");
    Path pidFilePath = properties.getPidFilePath();

    assertThat(pidFilePath).isEqualTo(Path.of("/custom/pid/path"));
  }

  @Test
  void getLogFilePath_returnsPathObject() {
    properties.setDefaults();
    Path logFilePath = properties.getLogFilePath();

    assertThat(logFilePath).isEqualTo(Path.of("/tmp/.yukta/daemon.log"));
  }

  @Test
  void getLogFilePath_withCustomPath_returnsCorrectPath() {
    properties.setLogFile("/custom/log/path");
    Path logFilePath = properties.getLogFilePath();

    assertThat(logFilePath).isEqualTo(Path.of("/custom/log/path"));
  }

  @Test
  void propertySetters_modifyFieldValues() {
    properties.setPort(9090);
    properties.setPidFile("/new/pid");
    properties.setLogFile("/new/log");
    properties.setStartupTimeoutSeconds(60);
    properties.setHealthCheckIntervalMs(1000);
    properties.setJarPath("/path/to/jar");

    assertThat(properties.getPort()).isEqualTo(9090);
    assertThat(properties.getPidFile()).isEqualTo("/new/pid");
    assertThat(properties.getLogFile()).isEqualTo("/new/log");
    assertThat(properties.getStartupTimeoutSeconds()).isEqualTo(60);
    assertThat(properties.getHealthCheckIntervalMs()).isEqualTo(1000);
    assertThat(properties.getJarPath()).isEqualTo("/path/to/jar");
  }

  @Test
  void setPort_withVariousValues_updatesPort() {
    properties.setPort(3000);
    assertThat(properties.getPort()).isEqualTo(3000);

    properties.setPort(9999);
    assertThat(properties.getPort()).isEqualTo(9999);

    properties.setPort(1);
    assertThat(properties.getPort()).isEqualTo(1);
  }

  @Test
  void setStartupTimeoutSeconds_withVariousValues_updatesTimeout() {
    properties.setStartupTimeoutSeconds(15);
    assertThat(properties.getStartupTimeoutSeconds()).isEqualTo(15);

    properties.setStartupTimeoutSeconds(120);
    assertThat(properties.getStartupTimeoutSeconds()).isEqualTo(120);
  }

  @Test
  void setHealthCheckIntervalMs_withVariousValues_updatesInterval() {
    properties.setHealthCheckIntervalMs(100);
    assertThat(properties.getHealthCheckIntervalMs()).isEqualTo(100);

    properties.setHealthCheckIntervalMs(5000);
    assertThat(properties.getHealthCheckIntervalMs()).isEqualTo(5000);
  }

  @Test
  void getters_returnSetValues() {
    properties.setPort(8081);
    properties.setPidFile("/tmp/test.pid");
    properties.setLogFile("/tmp/test.log");
    properties.setJarPath("/opt/app.jar");

    assertThat(properties.getPort()).isEqualTo(8081);
    assertThat(properties.getPidFile()).isEqualTo("/tmp/test.pid");
    assertThat(properties.getLogFile()).isEqualTo("/tmp/test.log");
    assertThat(properties.getJarPath()).isEqualTo("/opt/app.jar");
  }

  @Test
  void pathConversion_handlesComplexPaths() {
    String complexPidPath = "/very/long/path/with/multiple/segments/daemon.pid";
    properties.setPidFile(complexPidPath);

    Path pidPath = properties.getPidFilePath();

    assertThat(pidPath).isEqualTo(Path.of(complexPidPath));
    assertThat(pidPath.toString()).isEqualTo(complexPidPath);
  }
}
