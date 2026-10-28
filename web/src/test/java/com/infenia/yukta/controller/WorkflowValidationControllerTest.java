// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.mapper.AppConfigMapper;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.orchestrator.validator.WorkflowValidator;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Tests for the behaviour described by {@link WorkflowValidationController}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.UnitTestShouldIncludeAssert"})
class WorkflowValidationControllerTest {

  /** JSON path of the verdict. */
  private static final String VALID = "$.data.valid";

  /** A draft with one known and one unknown plugin type. */
  private static final String DRAFT =
      """
      {"workflowId":"wf","description":"d",
       "nodes":[{"nodeId":"n1","type":"BRANCH","config":{}},
                {"nodeId":"n2","type":"MISSING","config":{}}],
       "edges":[]}
      """;

  /** A draft that uses one node id twice. */
  private static final String DUPLICATE_IDS =
      """
      {"workflowId":"wf","description":"d",
       "nodes":[{"nodeId":"n1","type":"BRANCH","config":{}},
                {"nodeId":"n1","type":"BRANCH","config":{}}],
       "edges":[]}
      """;

  /** The plugin registry. */
  private PluginRegistry pluginRegistry;

  /** The workflow validator. */
  private WorkflowValidator workflowValidator;

  /** The client. */
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    pluginRegistry = mock(PluginRegistry.class);
    workflowValidator = mock(WorkflowValidator.class);
    client =
        WebTestClient.bindToController(
                new WorkflowValidationController(
                    Mappers.getMapper(AppConfigMapper.class), pluginRegistry, workflowValidator))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private WebTestClient.ResponseSpec post() {
    return post(DRAFT);
  }

  private WebTestClient.ResponseSpec post(final String body) {
    return client
        .post()
        .uri("/api/sessions/validate")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  @Test
  void testValidDraftListsPortsOfKnownPluginsOnly() {
    final Plugin plugin = mock(Plugin.class);
    when(plugin.getOutputPorts(any())).thenReturn(List.of("a", "b"));
    when(pluginRegistry.get("BRANCH")).thenReturn(plugin);
    when(workflowValidator.validate(any())).thenReturn(Mono.empty());

    post()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(VALID)
        .isEqualTo(true)
        .jsonPath("$.data.outputPorts.n1[1]")
        .isEqualTo("b")
        .jsonPath("$.data.outputPorts.n2")
        .doesNotExist();
  }

  @Test
  void testInvalidDraftReturnsTheProblemWithoutFailing() {
    when(workflowValidator.validate(any()))
        .thenReturn(Mono.error(new IllegalArgumentException("no trigger")));

    post()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(VALID)
        .isEqualTo(false)
        .jsonPath("$.data.message")
        .isEqualTo("no trigger")
        .jsonPath("$.data.outputPorts.length()")
        .isEqualTo(0);
  }

  @Test
  void testDuplicateNodeIdIsReportedByValidatorNotServerError() {
    final Plugin plugin = mock(Plugin.class);
    when(plugin.getOutputPorts(any())).thenReturn(List.of("a"));
    when(pluginRegistry.get("BRANCH")).thenReturn(plugin);
    when(workflowValidator.validate(any()))
        .thenReturn(Mono.error(new IllegalArgumentException("Duplicate node id 'n1'")));

    post(DUPLICATE_IDS)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(VALID)
        .isEqualTo(false)
        .jsonPath("$.data.message")
        .isEqualTo("Duplicate node id 'n1'");
  }

  @Test
  void testPluginThatCannotListPortsForDraftConfigIsNotServerError() {
    final Plugin plugin = mock(Plugin.class);
    when(plugin.getOutputPorts(any())).thenThrow(new IllegalStateException("config incomplete"));
    when(pluginRegistry.get("BRANCH")).thenReturn(plugin);
    when(workflowValidator.validate(any())).thenReturn(Mono.empty());

    post()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(VALID)
        .isEqualTo(true)
        .jsonPath("$.data.outputPorts.n1.length()")
        .isEqualTo(0);
  }

  @Test
  void testFailureWithoutMessageStillSaysWhatFailed() {
    when(workflowValidator.validate(any())).thenReturn(Mono.error(new IllegalStateException()));

    post()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(VALID)
        .isEqualTo(false)
        .jsonPath("$.data.message")
        .isEqualTo("java.lang.IllegalStateException");
  }
}
