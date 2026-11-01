// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class WorkflowValidationControllerTest {

  /** JSON path of the verdict. */
  private static final String VALID = "$.data.valid";

  private static final String BRANCH = "BRANCH";

  /** A problem message reported for a workflow without a trigger. */
  private static final String NO_TRIGGER = "no trigger";

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
    when(pluginRegistry.get(BRANCH)).thenReturn(plugin);
    when(workflowValidator.validateAll(any())).thenReturn(Mono.just(List.of()));

    post(DRAFT)
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
    when(workflowValidator.validateAll(any())).thenReturn(Mono.just(List.of(NO_TRIGGER)));

    post(DRAFT)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(VALID)
        .isEqualTo(false)
        .jsonPath("$.data.message")
        .isEqualTo(NO_TRIGGER)
        .jsonPath("$.data.outputPorts.length()")
        .isEqualTo(0);
  }

  @Test
  void testEveryProblemIsListed() {
    when(workflowValidator.validateAll(any()))
        .thenReturn(Mono.just(List.of(NO_TRIGGER, "no terminal")));

    post(DRAFT)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.message")
        .isEqualTo(NO_TRIGGER)
        .jsonPath("$.data.problems.length()")
        .isEqualTo(2);
  }

  @Test
  void testDuplicateNodeIdIsReportedByValidatorNotServerError() {
    final Plugin plugin = mock(Plugin.class);
    when(plugin.getOutputPorts(any())).thenReturn(List.of("a"));
    when(pluginRegistry.get(BRANCH)).thenReturn(plugin);
    when(workflowValidator.validateAll(any()))
        .thenReturn(Mono.just(List.of("Duplicate node id 'n1'")));

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
    when(pluginRegistry.get(BRANCH)).thenReturn(plugin);
    when(workflowValidator.validateAll(any())).thenReturn(Mono.just(List.of()));

    post(DRAFT)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(VALID)
        .isEqualTo(true)
        .jsonPath("$.data.outputPorts.n1.length()")
        .isEqualTo(0);
  }

  @Test
  void testUnexpectedFailureReachesTheCentralHandlerWithoutItsText() {
    when(workflowValidator.validateAll(any()))
        .thenReturn(Mono.error(new IllegalStateException("secret detail")));

    post(DRAFT)
        .expectStatus()
        .is5xxServerError()
        .expectBody(String.class)
        .value(body -> assertThat(body).doesNotContain("secret detail"));
  }

  @Test
  void testAnAllPortsEdgeReadsEveryPortButError() {
    final Plugin routed = mock(Plugin.class);
    when(routed.getOutputPorts(any())).thenReturn(List.of("success", "failure", "error"));
    when(pluginRegistry.get(BRANCH)).thenReturn(routed);
    when(workflowValidator.validateAll(any())).thenReturn(Mono.just(List.of()));

    final String body =
        """
        {"workflowId":"wf","description":"d",
        "nodes":[{"nodeId":"gate","type":"BRANCH","config":{}},
        {"nodeId":"ok","type":"BRANCH","config":{}}],
        "edges":[{"source":"gate","target":"ok","sourcePort":"*"}]}
        """;
    post(body)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.warnings[?(@.nodeId == 'gate')].field")
        .isEqualTo(List.of("error"));
  }

  @Test
  void testWarnsAboutPortsNoEdgeReadsFrom() {
    final Plugin branch = mock(Plugin.class);
    when(branch.getOutputPorts(any())).thenReturn(List.of("success", "failure"));
    final Plugin single = mock(Plugin.class);
    when(single.getOutputPorts(any())).thenReturn(List.of("default"));
    when(pluginRegistry.get(BRANCH)).thenReturn(branch);
    when(pluginRegistry.get("ECHO")).thenReturn(single);
    when(workflowValidator.validateAll(any())).thenReturn(Mono.just(List.of("still invalid")));

    final String body =
        """
        {"workflowId":"wf","description":"d",
        "nodes":[{"nodeId":"gate","type":"BRANCH","config":{}},
        {"nodeId":"all","type":"BRANCH","config":{}},
        {"nodeId":"one","type":"ECHO","config":{}},
        {"nodeId":"ok","type":"ECHO","config":{}}],
        "edges":[{"source":"gate","target":"ok","sourcePort":"success"},
        {"source":"all","target":"ok","sourcePort":"success"},
        {"source":"all","target":"one","sourcePort":"failure"}]}
        """;
    post(body)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(VALID)
        .isEqualTo(false)
        .jsonPath("$.data.warnings.length()")
        .isEqualTo(1)
        .jsonPath("$.data.warnings[0].nodeId")
        .isEqualTo("gate")
        .jsonPath("$.data.warnings[0].field")
        .isEqualTo("failure")
        .jsonPath("$.data.warnings[0].message")
        .isEqualTo("gate has no edge from its 'failure' port, so what it sends there is dropped");
  }
}
