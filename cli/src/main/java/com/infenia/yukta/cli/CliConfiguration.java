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

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/** Spring configuration for CLI and daemon client beans. */
@Configuration
@EnableConfigurationProperties(DaemonProperties.class)
public class CliConfiguration {

  /**
   * Creates a SystemExitHandler that delegates to System.exit.
   *
   * @return the exit handler
   */
  @Bean
  public SystemExitHandler systemExitHandler() {
    return System::exit;
  }

  /**
   * Creates a WebClient configured for daemon communication.
   *
   * @param props daemon properties for configuration
   * @return the web client
   */
  @Bean
  public WebClient daemonWebClient(DaemonProperties props) {
    HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofSeconds(30));

    return WebClient.builder()
        .baseUrl("http://127.0.0.1:" + props.getPort())
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
  }
}
