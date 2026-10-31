// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowExecution;
import com.infenia.yukta.plugin.store.SecretProvider;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.WorkflowService;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Tests for {@link WebhookController}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyStaticImports"})
class WebhookControllerTest {

  private static final String BODY = "{\"ref\":\"main\"}";
  private static final String URI = "/hooks/s1/push";

  private final SessionService sessions = mock(SessionService.class);
  private final WorkflowService workflows = mock(WorkflowService.class);
  private final SecretProvider secrets = mock(SecretProvider.class);
  private WebTestClient client;

  private static String sign(final String key, final String body) {
    try {
      final Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      final byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
      return "sha256=" + HexFormat.of().formatHex(digest);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private void hook(final Map<String, Object> extra) {
    final Map<String, Object> config = new java.util.concurrent.ConcurrentHashMap<>(extra);
    config.put("path", "push");
    final var node = new WorkflowDefinition.Node("t", "WEBHOOK_TRIGGER", config);
    final var other = new WorkflowDefinition.Node("x", "API_TRIGGER", Map.of("path", "push"));
    when(sessions.getSessionWorkflows("s1"))
        .thenReturn(
            Mono.just(
                Map.of("wf", new WorkflowDefinition("wf", "d", List.of(other, node), List.of()))));
  }

  @BeforeEach
  void setUp() {
    client =
        WebTestClient.bindToController(new WebhookController(sessions, workflows, secrets))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
    when(secrets.getSecret(eq("gh"), any())).thenReturn(Mono.just("s3cret"));
    when(workflows.validateAndStartWorkflow(eq("s1"), eq("wf"), anyMap(), any()))
        .thenReturn(Mono.just(new WorkflowExecution("e1", Mono.empty())));
  }

  @Test
  void validSignature_startsTheRunWithBodyAndChosenHeaders() {
    hook(Map.of("secret", "decrypted:gh", "headers", "X-GitHub-Event, X-Missing"));

    client
        .post()
        .uri(URI)
        .header("X-Hub-Signature-256", sign("s3cret", BODY))
        .header("X-GitHub-Event", "push")
        .bodyValue(BODY)
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.data.executionId")
        .isEqualTo("e1");

    verify(workflows)
        .validateAndStartWorkflow(
            "s1",
            "wf",
            Map.of(
                "body",
                BODY,
                "headers",
                Map.of("X-GitHub-Event", "push"),
                ExecutionLogEvent.INITIATOR_KEY,
                "webhook"),
            null);
  }

  @Test
  void missingOrWrongSignature_isRefused() {
    hook(Map.of("secret", "decrypted:gh"));

    client.post().uri(URI).bodyValue(BODY).exchange().expectStatus().isUnauthorized();
    client
        .post()
        .uri(URI)
        .header("X-Hub-Signature-256", sign("other", BODY))
        .bodyValue(BODY)
        .exchange()
        .expectStatus()
        .isUnauthorized();
    client
        .post()
        .uri(URI)
        .header("X-Hub-Signature-256", sign("s3cret", "tampered"))
        .bodyValue(BODY)
        .exchange()
        .expectStatus()
        .isUnauthorized();
    client
        .post()
        .uri(URI)
        .header("X-Hub-Signature-256", sign("s3cret", BODY).substring(7))
        .bodyValue(BODY)
        .exchange()
        .expectStatus()
        .isUnauthorized();
    verify(workflows, never()).validateAndStartWorkflow(any(), any(), anyMap(), any());
  }

  @Test
  void hookWithoutUsableSecret_isNeverOpen() {
    hook(Map.of("secret", "plain-text"));
    client
        .post()
        .uri(URI)
        .header("X-Hub-Signature-256", sign("plain-text", BODY))
        .bodyValue(BODY)
        .exchange()
        .expectStatus()
        .isUnauthorized();

    hook(Map.of());
    client.post().uri(URI).bodyValue(BODY).exchange().expectStatus().isUnauthorized();

    hook(Map.of("secret", "decrypted:unset"));
    when(secrets.getSecret(eq("unset"), any())).thenReturn(Mono.empty());
    client
        .post()
        .uri(URI)
        .header("X-Hub-Signature-256", "sha256=00")
        .bodyValue(BODY)
        .exchange()
        .expectStatus()
        .isUnauthorized();

    hook(Map.of("secret", "decrypted:blank"));
    when(secrets.getSecret(eq("blank"), any())).thenReturn(Mono.just(""));
    client
        .post()
        .uri(URI)
        .header("X-Hub-Signature-256", "sha256=00")
        .bodyValue(BODY)
        .exchange()
        .expectStatus()
        .isUnauthorized();
    verify(workflows, never()).validateAndStartWorkflow(any(), any(), anyMap(), any());
  }

  @Test
  void blankOptionalSettings_fallBackToDefaults() {
    hook(
        Map.of(
            "secret",
            "decrypted:gh",
            "signatureHeader",
            " ",
            "idempotencyHeader",
            " ",
            "headers",
            "X-GitHub-Event, "));

    client
        .post()
        .uri(URI)
        .header("X-Hub-Signature-256", sign("s3cret", BODY))
        .header("X-GitHub-Event", "push")
        .bodyValue(BODY)
        .exchange()
        .expectStatus()
        .isAccepted();

    verify(workflows)
        .validateAndStartWorkflow(
            "s1",
            "wf",
            Map.of(
                "body",
                BODY,
                "headers",
                Map.of("X-GitHub-Event", "push"),
                ExecutionLogEvent.INITIATOR_KEY,
                "webhook"),
            null);
  }

  @Test
  void deliveryHeader_isPassedAsIdempotencyKey_andCustomSignatureHeaderIsUsed() {
    hook(
        Map.of(
            "secret",
            "decrypted:gh",
            "idempotencyHeader",
            "X-GitHub-Delivery",
            "signatureHeader",
            "X-Sig",
            "signaturePrefix",
            ""));

    client
        .post()
        .uri(URI)
        .header("X-Sig", sign("s3cret", BODY).substring(7).toUpperCase(java.util.Locale.ROOT))
        .header("X-GitHub-Delivery", "d-1")
        .bodyValue(BODY)
        .exchange()
        .expectStatus()
        .isAccepted();

    verify(workflows).validateAndStartWorkflow(eq("s1"), eq("wf"), anyMap(), eq("d-1"));
  }

  @Test
  void emptyBody_isSignedAndAccepted() {
    hook(Map.of("secret", "decrypted:gh"));

    client
        .post()
        .uri(URI)
        .header("X-Hub-Signature-256", sign("s3cret", ""))
        .exchange()
        .expectStatus()
        .isAccepted();

    verify(workflows)
        .validateAndStartWorkflow(
            eq("s1"),
            eq("wf"),
            eq(Map.of("body", "", "headers", Map.of(), ExecutionLogEvent.INITIATOR_KEY, "webhook")),
            isNull());
  }

  @Test
  void unknownPathOrSession_is404() {
    hook(Map.of("secret", "decrypted:gh"));
    client.post().uri("/hooks/s1/nope").bodyValue(BODY).exchange().expectStatus().isNotFound();

    when(sessions.getSessionWorkflows("gone")).thenReturn(Mono.empty());
    client.post().uri("/hooks/gone/push").bodyValue(BODY).exchange().expectStatus().isNotFound();
    verify(workflows, never()).validateAndStartWorkflow(any(), any(), anyMap(), any());
  }
}
