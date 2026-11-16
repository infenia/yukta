// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.mapper.ConversationMapper;
import com.infenia.yukta.plugin.agent.AgentCapability;
import com.infenia.yukta.plugin.agent.AgentConversation;
import com.infenia.yukta.plugin.agent.AgentState;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.agent.AgentConversationQueries;
import com.infenia.yukta.service.agent.AgentSteering;
import com.infenia.yukta.service.agent.AgentSteeringException;
import com.infenia.yukta.service.agent.AgentSteeringException.Reason;
import com.infenia.yukta.service.control.SupervisedControl;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** The three commands that steer an agent, and how their refusals look to a client. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestShouldIncludeAssert" // the expectations are on the WebTestClient
})
class ConversationSteeringTest {

  private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

  private final AgentConversationQueries queries = mock(AgentConversationQueries.class);
  private final SupervisedControl control = mock(SupervisedControl.class);
  private final AgentSteering steering = mock(AgentSteering.class);
  private final WebTestClient client =
      WebTestClient.bindToController(
              new ConversationsController(
                  queries,
                  new ConversationMapper(),
                  mock(ExecutionAccess.class),
                  control,
                  steering))
          .controllerAdvice(new GlobalExceptionHandler())
          .build();

  private static AgentConversation conversation(final Set<String> capabilities) {
    return new AgentConversation(
        "c-1",
        "e-1",
        "e-1",
        "s-1",
        "w-1",
        "n-1",
        "m-1",
        "claude-code",
        com.infenia.yukta.plugin.agent.AgentProtocol.JSONL,
        null,
        capabilities,
        AgentState.RUNNING,
        null,
        null,
        0,
        0,
        new com.infenia.yukta.plugin.input.Audience(java.util.List.of("role:OPERATOR")),
        NOW,
        NOW,
        null,
        1);
  }

  private void found(final Set<String> capabilities) {
    when(queries.require(eq("c-1"), any())).thenReturn(Mono.just(conversation(capabilities)));
  }

  @Test
  void messageIsAcceptedAndSaysWhetherItWaits() {
    found(Set.of(AgentCapability.MESSAGE));
    when(control.agentMessage("e-1", "n-1", "c-1", "hi", false)).thenReturn(Mono.empty());

    client
        .post()
        .uri("/api/conversations/c-1/messages")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("text", "hi"))
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.data.queued")
        .isEqualTo(true);
  }

  @Test
  void messageWithInterruptDoesNotWait() {
    found(Set.of(AgentCapability.MESSAGE, AgentCapability.INTERRUPT));
    when(control.agentMessage("e-1", "n-1", "c-1", "hi", true)).thenReturn(Mono.empty());

    client
        .post()
        .uri("/api/conversations/c-1/messages")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("text", "hi", "interrupt", true))
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.data.queued")
        .isEqualTo(false);
  }

  @Test
  void blankMessageIsBadRequest() {
    client
        .post()
        .uri("/api/conversations/c-1/messages")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("text", " "))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void anInterruptIsAccepted() {
    found(Set.of(AgentCapability.INTERRUPT));
    when(control.agentInterrupt("e-1", "n-1", "c-1")).thenReturn(Mono.empty());

    client.post().uri("/api/conversations/c-1/interrupt").exchange().expectStatus().isAccepted();

    verify(control).agentInterrupt("e-1", "n-1", "c-1");
  }

  @Test
  void anEndWithoutBodyEndsWithTheLastStatus() {
    found(Set.of());
    when(control.agentEnd(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(Mono.empty());

    client.post().uri("/api/conversations/c-1/end").exchange().expectStatus().isAccepted();

    verify(control).agentEnd("e-1", "n-1", "c-1", null, null);
  }

  @Test
  void anEndWithAnOutcomeAndReasonPassesThemOn() {
    found(Set.of());
    when(control.agentEnd(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(Mono.empty());

    client
        .post()
        .uri("/api/conversations/c-1/end")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("outcome", "failure", "reason", "stop"))
        .exchange()
        .expectStatus()
        .isAccepted();

    verify(control).agentEnd("e-1", "n-1", "c-1", "failure", "stop");
  }

  @Test
  void anOutcomeThatIsNotOneOfTheThreeIsBadRequest() {
    client
        .post()
        .uri("/api/conversations/c-1/end")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("outcome", "maybe"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void conversationTheCallerDoesNotReachIsNotFound() {
    when(queries.require(eq("c-1"), any()))
        .thenReturn(Mono.error(new NotFoundException("Conversation not found: c-1")));

    client.post().uri("/api/conversations/c-1/interrupt").exchange().expectStatus().isNotFound();
  }

  @Test
  void eachRefusalHasItsStatusAndCode() {
    found(Set.of(AgentCapability.MESSAGE));
    refusal(Reason.FORBIDDEN, null, 403);
    refusal(Reason.NOT_LIVE, null, 409);
    refusal(Reason.TOO_LARGE, null, 413);
    refusal(Reason.RATE_LIMITED, null, 429);
    refusal(Reason.UNSUPPORTED, "interrupt", 409);
  }

  private void refusal(final Reason reason, final String capability, final int status) {
    when(control.agentMessage(
            anyString(), anyString(), anyString(), anyString(), any(Boolean.class)))
        .thenReturn(Mono.error(new AgentSteeringException(reason, "no", capability)));

    final var body =
        client
            .post()
            .uri("/api/conversations/c-1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("text", "hi"))
            .exchange()
            .expectStatus()
            .isEqualTo(status)
            .expectBody();
    body.jsonPath("$.errors[?(@.field=='code')].message").isEqualTo(reason.code());
    if (capability != null) {
      body.jsonPath("$.errors[?(@.field=='capability')].message").isEqualTo(capability);
    }
  }

  @Test
  void typedKeysAreDecodedAndGoToTheTerminal() {
    when(steering.terminalInput(eq("c-1"), any())).thenReturn(Mono.empty());

    client
        .post()
        .uri("/api/conversations/c-1/terminal/input")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("data", "bHMN"))
        .exchange()
        .expectStatus()
        .isNoContent();

    verify(steering).terminalInput("c-1", new byte[] {'l', 's', '\r'});
  }

  @Test
  void keysThatAreNotBase64AreBadRequest() {
    client
        .post()
        .uri("/api/conversations/c-1/terminal/input")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("data", "not base64!"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void refusedInputKeepsItsStatus() {
    when(steering.terminalInput(eq("c-1"), any()))
        .thenReturn(Mono.error(new AgentSteeringException(Reason.TOO_LARGE, "too long", null)));

    client
        .post()
        .uri("/api/conversations/c-1/terminal/input")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("data", "bHMN"))
        .exchange()
        .expectStatus()
        .isEqualTo(413);
  }

  @Test
  void resizeGoesToTheTerminalAndItsSizeIsBounded() {
    when(steering.terminalResize("c-1", 100, 30)).thenReturn(Mono.empty());

    client
        .post()
        .uri("/api/conversations/c-1/terminal/resize")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("cols", 100, "rows", 30))
        .exchange()
        .expectStatus()
        .isNoContent();
    client
        .post()
        .uri("/api/conversations/c-1/terminal/resize")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("cols", 0, "rows", 30))
        .exchange()
        .expectStatus()
        .isBadRequest();

    verify(steering).terminalResize("c-1", 100, 30);
  }
}
