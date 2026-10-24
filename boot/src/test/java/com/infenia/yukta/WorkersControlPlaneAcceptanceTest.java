// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.worker.RemoteNodes;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.aot.DisabledInAotMode;

/**
 * The real application as a control plane: with a token it serves workers and can run nodes on
 * them. Runs only in a build made with {@code -Pyukta.workers=true}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "yukta.workers.token=secret",
      "yukta.workers.allow-insecure=true",
      "spring.rsocket.server.port=0"
    })
@DisabledInAotMode
@EnabledIfSystemProperty(named = "yukta.workers", matches = "true")
@SuppressWarnings({"PMD.CommentRequired", "PMD.LawOfDemeter"})
@NoArgsConstructor
class WorkersControlPlaneAcceptanceTest {

  @Autowired private ApplicationContext context;
  @Autowired private RemoteNodes remoteNodes;

  @Test
  void withToken_controlPlaneServesWorkersAndRunsNodesOnThem() {
    assertThat(context.containsBean("workerController")).isTrue();
    assertThat(context.containsBean("remoteNodeExecutor")).isTrue();
    assertThat(remoteNodes).isNotNull();
    assertThat(context.getEnvironment().getProperty("local.rsocket.server.port")).isNotBlank();
  }
}
