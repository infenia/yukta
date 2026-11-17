// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtensionContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Domain steps against the server. Everything it creates is deleted when the test ends, through the
 * JUnit store that {@link YuktaServer} puts it in.
 */
public final class Scenarios implements ExtensionContext.Store.CloseableResource {

  private static final Set<String> TERMINAL =
      Set.of("SUCCESS", "FAILURE", "ERROR", "STOPPED", "CANCELLED", "TIMED_OUT");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Api api;
  private final List<String> sessions = Collections.synchronizedList(new ArrayList<>());

  /* default */ Scenarios(final Api api) {
    this.api = api;
  }

  /**
   * A name that cannot clash with another test's, for sessions and workflows.
   *
   * @param test a short name of the test
   * @return {@code e2e-<test>-<8 hex>}
   */
  public static String unique(final String test) {
    return "e2e-" + test + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /**
   * Creates or replaces a session and deletes it when the test ends.
   *
   * @param json the session definition, whose {@code sessionId} should come from {@link #unique}
   * @return the session id
   */
  public String applySession(final String json) {
    final String id = JSON.readTree(json).path("sessionId").asString();
    api.data(
        api.client()
            .post()
            .uri("/api/sessions")
            .header("Content-Type", "application/json")
            .bodyValue(json)
            .exchange());
    sessions.add(id);
    return id;
  }

  /**
   * Starts a workflow.
   *
   * @param session the session id
   * @param workflow the workflow id
   * @param payload the trigger payload, or an empty map
   * @return the execution id
   */
  public String startWorkflow(
      final String session, final String workflow, final Map<String, Object> payload) {
    final JsonNode started =
        api.data(
            api.client()
                .post()
                .uri("/api/workflow/start")
                .bodyValue(Map.of("sessionId", session, "workflowId", workflow, "payload", payload))
                .exchange());
    return started.path("executionId").asString();
  }

  /**
   * Waits until an execution has a terminal status.
   *
   * @param execution the execution id
   * @param timeout how long to wait
   * @return its progress ({@code data} of the status response); {@code status} is the final status
   */
  public JsonNode awaitTerminal(final String execution, final Duration timeout) {
    final JsonNode[] last = new JsonNode[1];
    await()
        .atMost(timeout)
        .pollInterval(Duration.ofMillis(100))
        .until(() -> {
          // the execution can be unknown for a moment right after the start
          final var response = api.client().get().uri("/api/executions/{id}", execution).exchange();
          final var result = response.returnResult(JsonNode.class);
          if (result.getStatus().is2xxSuccessful()) {
            last[0] = Objects.requireNonNull(result.getResponseBody().blockFirst()).path("data");
          }
          return last[0] != null && TERMINAL.contains(last[0].path("status").asString());
        });
    return last[0];
  }

  /**
   * Deletes a session now.
   *
   * @param session the session id
   */
  public void deleteSession(final String session) {
    api.client()
        .delete()
        .uri("/api/sessions/{id}", session)
        .exchange()
        .expectStatus()
        .is2xxSuccessful();
    sessions.remove(session);
  }

  @Override
  public void close() {
    for (final String session : List.copyOf(sessions)) {
      // best effort: a test may have deleted it already
      api.client().delete().uri("/api/sessions/{id}", session).exchange().returnResult(Void.class);
    }
  }
}
