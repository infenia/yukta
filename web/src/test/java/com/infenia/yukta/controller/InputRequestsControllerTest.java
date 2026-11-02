// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.filter.ApiKeyFilter;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputForbiddenException;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputLimitException;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRejectedException;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.security.ApiKeyAuthenticator;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.input.InMemoryInputRequestStore;
import com.infenia.yukta.service.input.InputRequestQueries;
import com.infenia.yukta.service.leader.NotLeaderException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.reactive.server.StatusAssertions;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Tests for {@link InputRequestsController}, behind the API key filter. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.UnitTestShouldIncludeAssert",
  "PMD.UnitTestContainsTooManyAsserts"
})
class InputRequestsControllerTest {

  private static final Instant T0 = Instant.parse("2026-11-02T10:00:00Z");

  /** Path of the answer of the open request of run e1. */
  private static final String ANSWER = "/api/executions/e1/inputs/r1/answer";

  /** Path of the escalation of the open request of run e1. */
  private static final String ESCALATE = "/api/executions/e1/inputs/r1/escalate";

  /** A valid answer. */
  private static final String OK = "{\"decision\":\"ok\",\"text\":\"fine\"}";

  /** The header that carries the key. */
  private static final String KEY = "X-API-Key";

  /** The JSON path of the message. */
  private static final String MESSAGE = "$.message";

  /** Where the requests are kept. */
  private final InMemoryInputRequestStore store = new InMemoryInputRequestStore(10);

  /** The gateway the answers go through. */
  private final ControlBusGateway controlBus = mock(ControlBusGateway.class);

  /** The client with no key configured. */
  private WebTestClient open;

  /** The client with named keys. */
  private WebTestClient keyed;

  private static InputRequest request(
      final String id,
      final String session,
      final String execution,
      final InputState state,
      final long createdSecond) {
    final InputRequestSpec spec =
        new InputRequestSpec(
            "key-" + id,
            InputKind.CHOICE,
            "Which way?",
            Map.of("branch", "main"),
            List.of(new InputOption("ok", "Go"), new InputOption("no")),
            null,
            InputRisk.MEDIUM,
            Audience.CURRENT_TIER,
            Duration.ofMinutes(10),
            TimeoutAction.FAIL,
            null,
            null,
            "TEST");
    return new InputRequest(
        id,
        "key-" + id,
        session,
        "wf",
        execution,
        "gate",
        spec,
        state,
        state == InputState.OPEN ? 0 : 1,
        "default",
        List.of(),
        state == InputState.OPEN ? null : "check the budget",
        T0.plusSeconds(600),
        T0.plusSeconds(createdSecond),
        T0.plusSeconds(createdSecond));
  }

  private void put(final InputRequest request) {
    final InputRequest open =
        new InputRequest(
            request.id(),
            request.key(),
            request.sessionId(),
            request.workflowId(),
            request.executionId(),
            request.nodeId(),
            request.spec(),
            InputState.OPEN,
            0,
            request.tier(),
            request.targets(),
            null,
            request.deadline(),
            request.createdAt(),
            request.updatedAt());
    store.createIfAbsent(open).block();
    if (request.state() != InputState.OPEN) {
      store.compareAndSet(request.id(), 0, request).block();
    }
  }

  private static WorkflowProgress progress(final String session) {
    final WorkflowProgress progress = mock(WorkflowProgress.class);
    when(progress.sessionId()).thenReturn(session);
    return progress;
  }

  @BeforeEach
  void setUp() {
    put(request("r1", "s1", "e1", InputState.OPEN, 1));
    put(request("r2", "s1", "e1", InputState.ESCALATED, 2));
    put(request("r3", "s1", "e1", InputState.ANSWERED, 3));
    put(request("r4", "s1", "e1", InputState.EXPIRED, 4));
    put(request("r9", "s2", "e2", InputState.OPEN, 5));
    store
        .saveResponse(
            new InputResponse(
                "r3",
                "ok",
                "fine",
                Map.of("n", 1),
                new Responder(Responder.Type.HUMAN, "key:ann"),
                "looks right",
                null,
                T0.plusSeconds(30)))
        .block();
    final WorkflowProgress first = progress("s1");
    final WorkflowProgress second = progress("s2");
    when(controlBus.getCurrentProgress("e1")).thenReturn(first);
    when(controlBus.getCurrentProgress("e2")).thenReturn(second);
    when(controlBus.answerInput(
            anyString(), anyString(), any(), anyString(), any(), any(), any(), any()))
        .thenReturn(Mono.empty());
    when(controlBus.escalateInput(anyString(), anyString(), anyString(), any()))
        .thenReturn(Mono.empty());

    final InputRequestsController controller =
        new InputRequestsController(new InputRequestQueries(store), controlBus);
    open =
        WebTestClient.bindToController(controller)
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
    final MockEnvironment keys =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "viewer")
            .withProperty("yukta.api-keys[0].key", "v-key")
            .withProperty("yukta.api-keys[0].role", "VIEWER")
            .withProperty("yukta.api-keys[0].sessions", "*")
            .withProperty("yukta.api-keys[1].name", "ops")
            .withProperty("yukta.api-keys[1].key", "o-key")
            .withProperty("yukta.api-keys[1].role", "OPERATOR")
            .withProperty("yukta.api-keys[1].sessions", "*")
            .withProperty("yukta.api-keys[2].name", "root")
            .withProperty("yukta.api-keys[2].key", "a-key")
            .withProperty("yukta.api-keys[2].role", "ADMIN")
            .withProperty("yukta.api-keys[3].name", "team")
            .withProperty("yukta.api-keys[3].key", "t-key")
            .withProperty("yukta.api-keys[3].role", "OPERATOR")
            .withProperty("yukta.api-keys[3].sessions", "s1");
    keyed =
        WebTestClient.bindToController(controller)
            .webFilter(new ApiKeyFilter(ApiKeyAuthenticator.read(keys), controlBus, null))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private WebTestClient.ResponseSpec post(
      final WebTestClient client, final String key, final String path, final String body) {
    final var spec = client.post().uri(path).contentType(MediaType.APPLICATION_JSON);
    if (key != null) {
      spec.header(KEY, key);
    }
    return spec.bodyValue(body).exchange();
  }

  private WebTestClient.ResponseSpec get(
      final WebTestClient client, final String key, final String path) {
    final var spec = client.get().uri(path);
    if (key != null) {
      spec.header(KEY, key);
    }
    return spec.exchange();
  }

  @Test
  void listWithoutKey_showsTheWaitingRequestsOfTheSessionNewestFirst() {
    get(open, null, "/api/sessions/s1/inputs")
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(2)
        .jsonPath("$.data[0].id")
        .isEqualTo("r2")
        .jsonPath("$.data[0].state")
        .isEqualTo("ESCALATED")
        .jsonPath("$.data[0].advice")
        .isEqualTo("check the budget")
        .jsonPath("$.data[1].id")
        .isEqualTo("r1")
        .jsonPath("$.data[1].options[0].label")
        .isEqualTo("Go")
        .jsonPath("$.data[1].options[1].label")
        .isEqualTo("no")
        .jsonPath("$.data[1].context.branch")
        .isEqualTo("main")
        .jsonPath("$.data[1].tier")
        .isEqualTo("default")
        .jsonPath("$.data[1].risk")
        .isEqualTo("MEDIUM")
        .jsonPath("$.data[1].key")
        .doesNotExist()
        .jsonPath("$.data[1].answer")
        .doesNotExist();
  }

  @Test
  void listFiltersByStateWorkflowAndRun_andPages() {
    get(open, null, "/api/sessions/s1/inputs?state=answered,expired&workflowId=wf&executionId=e1")
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[*].id")
        .isEqualTo(List.of("r4", "r3"))
        .jsonPath("$.data[1].answer.decision")
        .isEqualTo("ok")
        .jsonPath("$.data[1].answer.responderId")
        .isEqualTo("key:ann");
    get(open, null, "/api/sessions/s1/inputs?workflowId=other")
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(0);
    get(open, null, "/api/sessions/s1/inputs?limit=1")
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals(Pagination.NEXT_CURSOR, "r2");
    get(open, null, "/api/sessions/s1/inputs?limit=1&cursor=r2")
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[0].id")
        .isEqualTo("r1");
  }

  @Test
  void listRefusesAnUnknownStateOrCursor() {
    get(open, null, "/api/sessions/s1/inputs?state=OPEN,DONE")
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath(MESSAGE)
        .value(message -> assertThat((String) message).contains("DONE").contains("CANCELLED"));
    get(open, null, "/api/sessions/s1/inputs?cursor=nope").expectStatus().isBadRequest();
  }

  @Test
  void getShowsOneRequestWithItsAnswer() {
    get(open, null, "/api/executions/e1/inputs/r3")
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.state")
        .isEqualTo("ANSWERED")
        .jsonPath("$.data.version")
        .isEqualTo(1)
        .jsonPath("$.data.deadline")
        .exists()
        .jsonPath("$.data.answer.text")
        .isEqualTo("fine")
        .jsonPath("$.data.answer.data.n")
        .isEqualTo(1)
        .jsonPath("$.data.answer.responderType")
        .isEqualTo("HUMAN")
        .jsonPath("$.data.answer.rationale")
        .isEqualTo("looks right");
  }

  @Test
  void unknownRequestOrRequestOfAnotherRunIsNotFound() {
    get(open, null, "/api/executions/e1/inputs/missing").expectStatus().isNotFound();
    get(open, null, "/api/executions/e1/inputs/r9").expectStatus().isNotFound();
    post(open, null, "/api/executions/e1/inputs/missing/answer", OK).expectStatus().isNotFound();
    post(open, null, "/api/executions/e1/inputs/r9/escalate", "{\"reason\":\"x\"}")
        .expectStatus()
        .isNotFound();
    verify(controlBus, never())
        .answerInput(anyString(), anyString(), any(), anyString(), any(), any(), any(), any());
  }

  @Test
  void answerWithoutKey_sendsTheCommandAndShowsTheRequest() {
    post(
            open,
            null,
            ANSWER,
            "{\"decision\":\"ok\",\"text\":\"t\",\"data\":{\"a\":null},"
                + "\"rationale\":\"r\",\"expectedVersion\":0}")
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(MESSAGE)
        .isEqualTo("Answer accepted")
        .jsonPath("$.data.id")
        .isEqualTo("r1");
    verify(controlBus)
        .answerInput("e1", "r1", 0L, "ok", "t", Collections.singletonMap("a", null), "r", null);
  }

  @Test
  void answerWithOnlyADecision_passesNoOptionalParts() {
    post(open, null, ANSWER, "{\"decision\":\"ok\"}").expectStatus().isOk();
    verify(controlBus).answerInput("e1", "r1", null, "ok", null, null, null, null);
  }

  @Test
  void escalateWithoutKey_sendsTheCommand() {
    post(open, null, ESCALATE, "{\"reason\":\"not mine\",\"advice\":\"ask finance\"}")
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(MESSAGE)
        .isEqualTo("Escalation accepted");
    post(open, null, "/api/executions/e1/inputs/r2/escalate", "{\"reason\":\"again\"}")
        .expectStatus()
        .isOk();
    verify(controlBus).escalateInput("e1", "r1", "not mine", "ask finance");
    verify(controlBus).escalateInput("e1", "r2", "again", null);
  }

  @Test
  void viewerReadsButMayNotAnswerOrEscalate() {
    get(keyed, "v-key", "/api/sessions/s1/inputs").expectStatus().isOk();
    get(keyed, "v-key", "/api/executions/e1/inputs/r1").expectStatus().isOk();
    post(keyed, "v-key", ANSWER, OK).expectStatus().isForbidden();
    post(keyed, "v-key", ESCALATE, "{\"reason\":\"x\"}").expectStatus().isForbidden();
    verify(controlBus, never())
        .answerInput(anyString(), anyString(), any(), anyString(), any(), any(), any(), any());
  }

  @Test
  void operatorAndAdminAnswer() {
    post(keyed, "o-key", ANSWER, OK).expectStatus().isOk();
    post(keyed, "a-key", ESCALATE, "{\"reason\":\"x\"}").expectStatus().isOk();
    get(keyed, "a-key", "/api/sessions/s2/inputs").expectStatus().isOk();
    verify(controlBus)
        .answerInput(eq("e1"), eq("r1"), isNull(), eq("ok"), any(), any(), any(), any());
  }

  @Test
  void missingKeyIsRefused() {
    get(keyed, null, "/api/sessions/s1/inputs").expectStatus().isUnauthorized();
  }

  @Test
  void sessionLimitedKeyReachesOnlyTheInputsOfItsSessions() {
    get(keyed, "t-key", "/api/sessions/s1/inputs").expectStatus().isOk();
    get(keyed, "t-key", "/api/sessions/s2/inputs").expectStatus().isForbidden();
    get(keyed, "t-key", "/api/executions/e1/inputs/r1").expectStatus().isOk();
    get(keyed, "t-key", "/api/executions/e2/inputs/r9").expectStatus().isForbidden();
    post(keyed, "t-key", ANSWER, OK).expectStatus().isOk();
    post(keyed, "t-key", "/api/executions/e2/inputs/r9/answer", OK).expectStatus().isForbidden();
    post(keyed, "t-key", "/api/executions/e2/inputs/r9/escalate", "{\"reason\":\"x\"}")
        .expectStatus()
        .isForbidden();
    verify(controlBus, never())
        .answerInput(eq("e2"), anyString(), any(), anyString(), any(), any(), any(), any());
  }

  @Test
  void answeredRequestIsConflictWithTheStoredDecisionAndResponder() {
    post(open, null, "/api/executions/e1/inputs/r3/answer", OK)
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.errors[0].field")
        .isEqualTo("decision")
        .jsonPath("$.errors[0].message")
        .isEqualTo("ok")
        .jsonPath("$.errors[1].field")
        .isEqualTo("responder")
        .jsonPath("$.errors[1].message")
        .isEqualTo("HUMAN:key:ann")
        .jsonPath("$.errors.length()")
        .isEqualTo(2);
    post(open, null, "/api/executions/e1/inputs/r3/escalate", "{\"reason\":\"x\"}")
        .expectStatus()
        .isEqualTo(409);
  }

  @Test
  void answeredRequestWhoseAnswerIsGoneIsConflictWithoutDetails() {
    put(request("r5", "s1", "e1", InputState.ANSWERED, 6));

    post(open, null, "/api/executions/e1/inputs/r5/answer", OK)
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.errors")
        .doesNotExist();
  }

  @Test
  void expiredRequestIsConflict() {
    post(open, null, "/api/executions/e1/inputs/r4/answer", OK)
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath(MESSAGE)
        .isEqualTo("Input request r4 is EXPIRED");
  }

  @Test
  void refusalsOfTheEngineMapToTheirStatus() {
    answerFails(new InputForbiddenException("r1", "tier", "Not your tier"))
        .isForbidden()
        .expectBody()
        .jsonPath("$.errors[0].field")
        .isEqualTo("rule")
        .jsonPath("$.errors[0].message")
        .isEqualTo("tier")
        .jsonPath(MESSAGE)
        .isEqualTo("Not your tier");
    answerFails(new InputRejectedException("r1", "Pick one of the options"))
        .isEqualTo(422)
        .expectBody()
        .jsonPath(MESSAGE)
        .isEqualTo("Pick one of the options");
    answerFails(new ConflictException("moved")).isEqualTo(409);
    answerFails(new InputLimitException("r1", "Too many")).isEqualTo(429);
    answerFails(new NotLeaderException()).isEqualTo(503);
  }

  private StatusAssertions answerFails(final RuntimeException failure) {
    when(controlBus.answerInput(
            anyString(), anyString(), any(), anyString(), any(), any(), any(), any()))
        .thenReturn(Mono.error(failure));
    return post(open, null, ANSWER, OK).expectStatus();
  }

  @Test
  void invalidBodiesAreRefused() {
    post(open, null, ANSWER, "{}").expectStatus().isBadRequest();
    post(open, null, ANSWER, "{\"decision\":\" \"}").expectStatus().isBadRequest();
    post(open, null, ANSWER, "{\"decision\":\"ok\",\"expectedVersion\":-1}")
        .expectStatus()
        .isBadRequest();
    post(open, null, ANSWER, "{\"decision\":\"" + "x".repeat(129) + "\"}")
        .expectStatus()
        .isBadRequest();
    post(open, null, ANSWER, "not json").expectStatus().isBadRequest();
    post(open, null, ESCALATE, "{\"advice\":\"a\"}").expectStatus().isBadRequest();
    verify(controlBus, never())
        .answerInput(anyString(), anyString(), any(), anyString(), any(), any(), any(), any());
  }

  @Test
  void oversizedBodyIsRefused() {
    post(open, null, ANSWER, "{\"decision\":\"ok\",\"text\":\"" + "x".repeat(9000) + "\"}")
        .expectStatus()
        .isBadRequest();
    post(open, null, ANSWER, "{\"decision\":\"ok\",\"text\":\"" + "x".repeat(300_000) + "\"}")
        .expectStatus()
        .is4xxClientError();
    verify(controlBus, never())
        .answerInput(anyString(), anyString(), any(), anyString(), any(), any(), any(), any());
  }
}
