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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class CliConfigurationTest {

  private CliConfiguration configuration;

  @BeforeEach
  void setUp() {
    configuration = new CliConfiguration();
  }

  @Test
  void constructor_createsInstance() {
    assertThat(configuration).isNotNull();
  }

  @Test
  void systemExitHandler_returnsFunctionalInterface() {
    SystemExitHandler handler = configuration.systemExitHandler();

    assertThat(handler).isNotNull();
  }

  @Test
  void systemExitHandler_returnsSystemExitMethod() {
    SystemExitHandler handler = configuration.systemExitHandler();

    assertThat(handler).isInstanceOf(SystemExitHandler.class);
  }

  @Test
  void daemonWebClient_withProperties_returnsWebClient() {
    DaemonProperties props = new DaemonProperties();
    props.setPort(8080);
    WebClient client = configuration.daemonWebClient(props);

    assertThat(client).isNotNull();
  }

  @Test
  void daemonWebClient_withCustomPort_usesCustomPort() {
    DaemonProperties props = new DaemonProperties();
    props.setPort(9090);
    WebClient client = configuration.daemonWebClient(props);

    assertThat(client).isNotNull();
  }

  @Test
  void daemonWebClient_withDefaultPort_uses8080() {
    DaemonProperties props = new DaemonProperties();
    WebClient client = configuration.daemonWebClient(props);

    assertThat(client).isNotNull();
  }

  @Test
  void daemonWebClient_multipleInvocations_createsDifferentInstances() {
    DaemonProperties props1 = new DaemonProperties();
    props1.setPort(8080);
    DaemonProperties props2 = new DaemonProperties();
    props2.setPort(9090);

    WebClient client1 = configuration.daemonWebClient(props1);
    WebClient client2 = configuration.daemonWebClient(props2);

    assertThat(client1).isNotEqualTo(client2);
  }
}
