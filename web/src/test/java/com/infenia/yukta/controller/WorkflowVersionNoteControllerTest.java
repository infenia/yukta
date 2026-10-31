// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@NoArgsConstructor
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class WorkflowVersionNoteControllerTest {

  private static final String VERSION_B = "0002-b";
  private static final String BASE = "/api/sessions/s1/workflows/w1";
  private static final String NOTE_URI = BASE + "/versions/note";
  private static final String NOTE_KEY = "note";
  private static final String VERSION_KEY = "version";
  private static final String NOTE_WITH_SPACE = "why not";

  private final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);
  private final ControlBusGateway controlBus = mock(ControlBusGateway.class);
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    client =
        WebTestClient.bindToController(new WorkflowVersionController(definitions, controlBus))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void noteIsStoredOnTheVersion() {
    when(definitions.saveNote("s1", "w1", VERSION_B, "why")).thenReturn(Mono.empty());

    client
        .post()
        .uri(NOTE_URI + "?version=" + VERSION_B + "&note=why")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.note")
        .isEqualTo("why");
    verify(definitions).saveNote("s1", "w1", VERSION_B, "why");
  }

  @Test
  void noteInTheBodyKeepsTheNoteOutOfTheUrl() {
    when(definitions.saveNote("s1", "w1", VERSION_B, NOTE_WITH_SPACE)).thenReturn(Mono.empty());

    client
        .post()
        .uri(NOTE_URI)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(VERSION_KEY, VERSION_B, NOTE_KEY, NOTE_WITH_SPACE))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.note")
        .isEqualTo(NOTE_WITH_SPACE);
    verify(definitions).saveNote("s1", "w1", VERSION_B, NOTE_WITH_SPACE);
  }

  @Test
  void noteNeedsVersion() {
    client
        .post()
        .uri(NOTE_URI)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(NOTE_KEY, "x"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void noteRefusesBlankVersion() {
    client
        .post()
        .uri(NOTE_URI)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(VERSION_KEY, "  ", NOTE_KEY, "x"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void tooLongNoteIsRefused() {
    final String tooLong = "x".repeat(WorkflowVersionController.MAX_NOTE + 1);
    client
        .post()
        .uri(NOTE_URI)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(VERSION_KEY, VERSION_B, NOTE_KEY, tooLong))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }
}
