// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.response.WorkflowStartResponse;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.store.SecretContext;
import com.infenia.yukta.plugin.store.SecretProvider;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Inbound webhooks: {@code POST /hooks/{sessionId}/{path}} starts the workflow that has a {@code
 * WEBHOOK_TRIGGER} node with that {@code path}. It is outside {@code /api/**}, so the API key does
 * not apply; the call is instead refused with 401 unless the signature header holds the hex
 * HMAC-SHA256 of the raw body under the node's {@code decrypted:} secret (compared in constant
 * time). A hook without a resolvable secret is never open. The body is capped by the server-wide
 * request size limit, and the run goes through the usual start path, so admission limits and
 * idempotency (the node's {@code idempotencyHeader}) apply.
 */
@Slf4j
@RestController
@RequestMapping("/hooks")
@RequiredArgsConstructor
@Tag(name = "Webhook API", description = "Signed inbound calls that start a workflow")
@SuppressWarnings("PMD.ExcessiveImports")
public class WebhookController {

  /** The trigger node type. */
  private static final String TYPE = "WEBHOOK_TRIGGER";

  /** Prefix that marks a secret reference. */
  private static final String SECRET_PREFIX = "decrypted:";

  /** Signature scheme. */
  private static final String HMAC = "HmacSHA256";

  /** Reads the sessions. */
  private final SessionService sessionService;

  /** Starts the run. */
  private final WorkflowService workflowService;

  /** Resolves the signing secret. */
  private final SecretProvider secrets;

  /**
   * Start the workflow behind a hook.
   *
   * @param sessionId the session
   * @param path the hook path set on the trigger node
   * @param headers the request headers
   * @param body the raw request body
   * @return the execution id
   */
  @PostMapping("/{sessionId}/{path}")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(
      summary = "Call a webhook",
      description =
          "Starts the workflow whose WEBHOOK_TRIGGER node has this path. 401 unless the body is"
              + " signed with the node's secret; 404 for an unknown hook.")
  public Mono<ApiResponse<WorkflowStartResponse>> call(
      @PathVariable final String sessionId,
      @PathVariable final String path,
      @RequestHeader final HttpHeaders headers,
      @RequestBody(required = false) final byte[] body) {
    final byte[] raw = body == null ? new byte[0] : body;
    return sessionService
        .getSessionWorkflows(sessionId)
        .flatMap(
            workflows ->
                Mono.justOrEmpty(find(workflows, path))
                    .switchIfEmpty(Mono.error(() -> unknown(sessionId, path)))
                    .flatMap(hook -> start(sessionId, hook, headers, raw)))
        .switchIfEmpty(Mono.error(() -> unknown(sessionId, path)));
  }

  private Mono<ApiResponse<WorkflowStartResponse>> start(
      final String sessionId, final Hook hook, final HttpHeaders headers, final byte[] raw) {
    return secret(sessionId, hook)
        .flatMap(secret -> Mono.fromCallable(() -> signed(secret, hook, headers, raw)))
        .filter(Boolean::booleanValue)
        .switchIfEmpty(
            Mono.error(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid signature")))
        .flatMap(
            _ ->
                workflowService.validateAndStartWorkflow(
                    sessionId,
                    hook.workflowId(),
                    ExecutionLogEvent.withInitiator(payload(hook, headers, raw), "webhook"),
                    text(hook.config(), "idempotencyHeader") == null
                        ? null
                        : headers.getFirst(text(hook.config(), "idempotencyHeader"))))
        .map(
            execution ->
                ApiResponse.success(
                    202, "Webhook accepted", new WorkflowStartResponse(execution.executionId())));
  }

  private Mono<String> secret(final String sessionId, final Hook hook) {
    final String ref = text(hook.config(), "secret");
    if (ref == null || !ref.startsWith(SECRET_PREFIX)) {
      log.atWarn()
          .addKeyValue("workflow", hook.workflowId())
          .log("Webhook refused: its secret is not a decrypted: reference");
      return Mono.empty();
    }
    return secrets
        .getSecret(
            ref.substring(SECRET_PREFIX.length()),
            new SecretContext(null, sessionId, hook.workflowId(), hook.nodeId()))
        .filter(secret -> !secret.isEmpty());
  }

  private static boolean signed(
      final String secret, final Hook hook, final HttpHeaders headers, final byte[] raw)
      throws GeneralSecurityException {
    final String configured = text(hook.config(), "signatureHeader");
    final String header = configured == null ? "X-Hub-Signature-256" : configured;
    // an empty prefix is allowed (a bare digest), so only an absent value falls back
    final String prefix =
        hook.config().get("signaturePrefix") instanceof final String set ? set : "sha256=";
    final String given = headers.getFirst(header);
    if (given == null || !given.startsWith(prefix)) {
      return false;
    }
    final Mac mac = Mac.getInstance(HMAC);
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
    final byte[] expected =
        HexFormat.of().formatHex(mac.doFinal(raw)).getBytes(StandardCharsets.US_ASCII);
    return MessageDigest.isEqual(
        expected,
        given
            .substring(prefix.length())
            .toLowerCase(java.util.Locale.ROOT)
            .getBytes(StandardCharsets.US_ASCII));
  }

  private static Map<String, Object> payload(
      final Hook hook, final HttpHeaders headers, final byte[] raw) {
    final Map<String, Object> passed = new java.util.concurrent.ConcurrentHashMap<>();
    final String names = text(hook.config(), "headers");
    if (names != null) {
      for (final String name : names.split(",")) {
        final String key = name.strip();
        final String value = key.isEmpty() ? null : headers.getFirst(key);
        if (value != null) {
          passed.put(key, value);
        }
      }
    }
    return Map.of("body", new String(raw, StandardCharsets.UTF_8), "headers", passed);
  }

  private static Hook find(final Map<String, WorkflowDefinition> workflows, final String path) {
    return workflows.values().stream()
        .flatMap(
            workflow ->
                workflow.nodes().stream()
                    .filter(node -> TYPE.equals(node.type()))
                    .filter(node -> path.equals(text(node.config(), "path")))
                    .map(node -> new Hook(workflow.workflowId(), node.nodeId(), node.config())))
        .findFirst()
        .orElse(null);
  }

  private static String text(final Map<String, Object> config, final String key) {
    final Object value = config.get(key);
    return value instanceof final String string && !string.isBlank() ? string : null;
  }

  private static ResponseStatusException unknown(final String sessionId, final String path) {
    return new ResponseStatusException(
        HttpStatus.NOT_FOUND, "No webhook " + path + " in session " + sessionId);
  }

  /** A trigger node that matched the path. */
  private record Hook(String workflowId, String nodeId, Map<String, Object> config) {}
}
