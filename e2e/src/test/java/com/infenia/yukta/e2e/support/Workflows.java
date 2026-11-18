// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.support;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Small sessions for the lifecycle tests: one workflow {@code w}, built from maps. */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class Workflows {

  /** The workflow id of every session built here. */
  public static final String WORKFLOW = "w";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Workflows() {}

  private static Map<String, Object> node(
      final String id, final String type, final Map<String, Object> config) {
    return Map.of("nodeId", id, "type", type, "config", config);
  }

  private static Map<String, Object> shell(final String id, final String script) {
    return node(
        id,
        "PROCESS_EXECUTOR",
        Map.of("command", List.of("sh", "-c", script), "outputFormat", "RAW"));
  }

  /** {@code trigger -> step -> terminal} in session {@code id}. */
  public static String session(
      final String id, final String description, final Map<String, Object> step) {
    final Map<String, Object> workflow =
        Map.of(
            "workflowId",
            WORKFLOW,
            "description",
            description,
            "nodes",
            List.of(
                node("t", "CONSTANT_SOURCE", Map.of()),
                step,
                node("e", "CONSOLE_TERMINAL", Map.of())),
            "edges",
            List.of(
                Map.of("source", "t", "target", step.get("nodeId")),
                Map.of("source", step.get("nodeId"), "target", "e")));
    return JSON.writeValueAsString(
        Map.of(
            "sessionId",
            id,
            "name",
            "e2e",
            "description",
            "d",
            "initiator",
            "e2e",
            "projectPath",
            "/tmp",
            "workflows",
            Map.of(WORKFLOW, workflow)));
  }

  /** A run that prints {@code text} and ends. */
  public static String echo(final String id, final String text) {
    return session(id, "echo", shell("p", "echo " + text));
  }

  /** A run that stays RUNNING for {@code seconds} (or until it is stopped). */
  public static String sleeper(final String id, final int seconds) {
    return session(id, "sleeper", shell("s", "sleep " + seconds));
  }

  /** A run whose step fails. */
  public static String failing(final String id) {
    return session(id, "failing", shell("f", "exit 3"));
  }

  /** A run that asks for an approval and waits. */
  public static String asking(final String id) {
    return session(
        id,
        "asking",
        node("a", "ASK_INPUT", Map.of("prompt", "ok?", "kind", "APPROVAL", "ttlSeconds", 600)));
  }

  /** A workflow started by a webhook at {@code path}, signed with {@code E2E_HOOK}. */
  public static String hooked(final String id, final String path) {
    final Map<String, Object> workflow =
        Map.of(
            "workflowId",
            WORKFLOW,
            "description",
            "hooked",
            "nodes",
            List.of(
                node("h", "WEBHOOK_TRIGGER", Map.of("path", path, "secret", "decrypted:E2E_HOOK")),
                node("e", "CONSOLE_TERMINAL", Map.of())),
            "edges",
            List.of(Map.of("source", "h", "target", "e")));
    return JSON.writeValueAsString(
        Map.of(
            "sessionId",
            id,
            "name",
            "e2e",
            "description",
            "d",
            "initiator",
            "e2e",
            "projectPath",
            "/tmp",
            "workflows",
            Map.of(WORKFLOW, workflow)));
  }

  /** {@code trigger -> terminal}: for a terminal plugin such as NOTIFY_TERMINAL. */
  public static String ending(
      final String id, final String description, final Map<String, Object> end) {
    final Map<String, Object> workflow =
        Map.of(
            "workflowId",
            WORKFLOW,
            "description",
            description,
            "nodes",
            List.of(node("t", "CONSTANT_SOURCE", Map.of()), end),
            "edges",
            List.of(Map.of("source", "t", "target", end.get("nodeId"))));
    return JSON.writeValueAsString(
        Map.of(
            "sessionId",
            id,
            "name",
            "e2e",
            "description",
            "d",
            "initiator",
            "e2e",
            "projectPath",
            "/tmp",
            "workflows",
            Map.of(WORKFLOW, workflow)));
  }
}
