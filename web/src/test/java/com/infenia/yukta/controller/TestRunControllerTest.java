// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.mapper.AppConfigMapper;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.service.control.SupervisedControl;
import com.infenia.yukta.service.orchestrator.validator.ValidationProblem;
import com.infenia.yukta.service.orchestrator.validator.WorkflowValidator;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@NoArgsConstructor
@SuppressWarnings({"PMD.UnitTestShouldIncludeAssert", "PMD.TooManyMethods"})
class TestRunControllerTest {

  private static final String URI = "/api/sessions/s1/test-runs";

  private static final String DRAFT =
      """
      "workflow":{"workflowId":"wf","description":"d",
       "nodes":[{"nodeId":"t","type":"API_TRIGGER","config":{}},
                {"nodeId":"a","type":"P","config":{}},
                {"nodeId":"end","type":"T","config":{}}],
       "edges":[{"source":"t","target":"a"},{"source":"a","target":"end"}]}
      """;

  private PluginRegistry pluginRegistry;
  private WorkflowValidator validator;
  private SupervisedControl control;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    pluginRegistry = mock(PluginRegistry.class);
    validator = mock(WorkflowValidator.class);
    control = mock(SupervisedControl.class);
    when(pluginRegistry.get("T")).thenReturn(mock(TerminalPlugin.class));
    client =
        WebTestClient.bindToController(
                new TestRunController(
                    Mappers.getMapper(AppConfigMapper.class), pluginRegistry, validator, control))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private WebTestClient.ResponseSpec post(final String body) {
    return client
        .post()
        .uri(URI)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  @Test
  void aValidDraftIsRunUnderAReservedIdWithItsPayload() {
    when(validator.validateAll(any())).thenReturn(Mono.just(List.of()));
    when(control.startDraft(eq("s1"), any(), any())).thenReturn(Mono.just("e1"));

    post("{" + DRAFT + ",\"payload\":{\"k\":1}}")
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.data.executionId")
        .isEqualTo("e1")
        .jsonPath("$.data.workflowId")
        .value(id -> assertThat((String) id).startsWith("__test__."));

    final ArgumentCaptor<WorkflowDefinition> cut =
        ArgumentCaptor.forClass(WorkflowDefinition.class);
    verify(control).startDraft(eq("s1"), cut.capture(), eq(Map.of("k", 1)));
    assertThat(cut.getValue().nodes()).hasSize(3);
  }

  @Test
  void aNodeIsCutWithASinkWhenItIsNoTerminal() {
    when(validator.validateAll(any())).thenReturn(Mono.just(List.of()));
    when(control.startDraft(any(), any(), any())).thenReturn(Mono.just("e1"));

    post("{" + DRAFT + ",\"untilNodeId\":\"a\"}").expectStatus().isAccepted();

    final ArgumentCaptor<WorkflowDefinition> cut =
        ArgumentCaptor.forClass(WorkflowDefinition.class);
    verify(control).startDraft(any(), cut.capture(), any());
    assertThat(cut.getValue().nodes())
        .extracting(WorkflowDefinition.Node::nodeId)
        .containsExactly("t", "a", "__test__.sink");
  }

  @Test
  void aDraftThatDoesNotValidateIs400WithTheProblemsAndNeverRuns() {
    when(validator.validateAll(any()))
        .thenReturn(Mono.just(List.of(new ValidationProblem("a", null, "X", "a is broken"))));

    post("{" + DRAFT + "}")
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.message")
        .value(m -> assertThat((String) m).contains("a is broken"));

    verify(control, never()).startDraft(any(), any(), any());
  }

  @Test
  void aStepIsRunAloneBehindATrigger() {
    when(validator.validateAll(any())).thenReturn(Mono.just(List.of()));
    when(control.startDraft(any(), any(), any())).thenReturn(Mono.just("e1"));

    post("{" + DRAFT + ",\"untilNodeId\":\"end\",\"stepOnly\":true}").expectStatus().isAccepted();

    final ArgumentCaptor<WorkflowDefinition> cut =
        ArgumentCaptor.forClass(WorkflowDefinition.class);
    verify(control).startDraft(any(), cut.capture(), any());
    assertThat(cut.getValue().nodes())
        .extracting(WorkflowDefinition.Node::nodeId)
        .containsExactly("__test__.trigger", "end");
  }

  @Test
  void anUnknownNodeIsABadRequest() {
    post("{" + DRAFT + ",\"untilNodeId\":\"nope\"}").expectStatus().isBadRequest();
  }
}
