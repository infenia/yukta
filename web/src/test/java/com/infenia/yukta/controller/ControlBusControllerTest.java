// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Tests for ControlBusController. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.AvoidDuplicateLiterals"})
class ControlBusControllerTest {

  /** First test node identifier. */
  private static final String NODE1 = "node1";

  /** Second test node identifier. */
  private static final String NODE2 = "node2";

  /** JSONPath for response message field. */
  private static final String MESSAGE_PATH = "$.message";

  /** Web test client for testing controller endpoints. */
  private WebTestClient webClient;

  /** Mock gateway for control bus operations. */
  private ControlBusGateway controlBusGateway;

  @BeforeEach
  void setUp() {
    controlBusGateway = mock(ControlBusGateway.class);
    final ControlBusController controller = new ControlBusController(controlBusGateway);
    webClient =
        WebTestClient.bindToController(controller)
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void testSendCommand() {
    final Message<?> resp = DefaultMessage.create(null, "done");
    when(controlBusGateway.sendCommand(eq("s1"), eq("wf1"), isNull(), eq("n1"), any()))
        .thenReturn(Mono.just(resp));

    final var result =
        webClient
            .post()
            .uri("/api/control/workflows/wf1/nodes/n1/command?sessionId=s1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("cmd", "reset"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(MESSAGE_PATH)
            .isEqualTo("Command processed")
            .jsonPath("$.data.payload")
            .isEqualTo("done")
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void testEmptyCommandPayloadIsRejected() {
    webClient
        .post()
        .uri("/api/control/workflows/wf1/nodes/n1/command?sessionId=s1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void testNullCommandPayloadBecomesEmpty() {
    assertThat(new com.infenia.yukta.dto.request.NodeCommandRequest(null).payload()).isEmpty();
  }
}
