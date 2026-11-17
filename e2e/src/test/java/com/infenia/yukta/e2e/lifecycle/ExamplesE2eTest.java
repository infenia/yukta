// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Scenarios;
import com.infenia.yukta.security.ApiRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bundled examples as tests. Each {@code examples/NN-*} directory with a {@code run.sh} is
 * either run here (the session is applied and the workflows {@code run.sh} runs are started with
 * its sample payloads, and the same outcome is checked) or skipped with the reason below. A new
 * example that is neither fails this test, so it cannot be added unnoticed. The scripts stay for
 * users and for the nightly {@code examples-nightly.yml}.
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.UnitTestShouldIncludeAssert"
})
class ExamplesE2eTest {

  private static final Path EXAMPLES = Path.of("..", "examples");
  private static final Duration WAIT = Duration.ofSeconds(60);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Examples with a {@code run.sh} that this test does not run, and why. */
  private static final Map<String, String> SKIPPED =
      Map.of(
          "02-agent-verification",
              "its checks run the scripts patch-guard.sh and patch-guard-test.sh",
          "03-release-approval",
              "drives release.sh and a supervisor through several signals in run.sh",
          "08-alert-triage", "starts a local pager with python3 and needs jq-driven escalations",
          "10-mcp-agent", "needs an LLM endpoint, not available to a deterministic test",
          "12-model-promotion", "a multi-round review across sub-workflows, driven by run.sh",
          "14-security-findings", "a batch with a human decision per finding, driven by run.sh",
          "18-supervisor-agents", "needs the Python and Go agents of the example",
          "21-agent-console", "needs the fake-agent harness (AGENT_SESSION with a PTY host)");

  private final Api admin = Api.forRole(ApiRole.ADMIN);

  /* default */ static Stream<String> exampleDirs() throws IOException {
    try (Stream<Path> dirs = Files.list(EXAMPLES)) {
      return dirs
          .filter(d -> Files.exists(d.resolve("run.sh")))
          .map(d -> String.valueOf(d.getFileName()))
          .sorted()
          .toList()
          .stream();
    }
  }

  private static String session(final String example) throws IOException {
    return Files.readString(EXAMPLES.resolve(example).resolve("session.json"));
  }

  private static Map<String, Object> payload(final String example, final String file)
      throws IOException {
    return JSON.readValue(
        Files.readString(EXAMPLES.resolve(example).resolve(file)),
        new tools.jackson.core.type.TypeReference<>() {});
  }

  private String run(
      final Scenarios scenarios,
      final String session,
      final String workflow,
      final Map<String, Object> payload,
      final String expectedStatus) {
    final String execution = scenarios.startWorkflow(session, workflow, payload);
    final String logs = logsWhenDone(scenarios, session, execution);
    assertThat(scenarios.awaitTerminal(execution, WAIT).path("status").asString())
        .as(logs)
        .isEqualTo(expectedStatus);
    return logs;
  }

  private String logsWhenDone(
      final Scenarios scenarios, final String session, final String execution) {
    scenarios.awaitTerminal(execution, WAIT);
    return Api.eventsUntilEnd(
        admin.client().get().uri("/api/sessions/{s}/executions/{e}/logs", session, execution),
        WAIT);
  }

  /** Starts a run that waits for a person, answers its approval, and returns the logs. */
  private String runWithAnswer(
      final Scenarios scenarios,
      final String session,
      final String workflow,
      final Map<String, Object> payload,
      final String node,
      final String decision) {
    final String execution = scenarios.startWorkflow(session, workflow, payload);
    final JsonNode[] request = new JsonNode[1];
    await()
        .atMost(WAIT)
        .until(() -> {
          for (final JsonNode r :
              admin.data(
                  admin.client().get().uri("/api/sessions/{s}/inputs", session).exchange())) {
            if (execution.equals(r.path("executionId").asString())
                && node.equals(r.path("nodeId").asString())) {
              request[0] = r;
            }
          }
          return request[0] != null;
        });
    final Api.Reply answered =
        admin.call(
            "POST",
            "/api/executions/"
                + execution
                + "/inputs/"
                + request[0].path("id").asString()
                + "/answer",
            "{\"decision\":\""
                + decision
                + "\",\"expectedVersion\":"
                + request[0].path("version")
                + "}");
    assertThat(answered.status()).as(answered.body()).isEqualTo(200);
    return logsWhenDone(scenarios, session, execution);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("exampleDirs")
  void everyExampleWithRunScriptIsRunOrSkippedWithReason(
      final String example, final Scenarios scenarios) throws IOException {
    if (SKIPPED.containsKey(example)) {
      Assumptions.abort(SKIPPED.get(example));
    }
    switch (example) {
      case "01-hello-world" -> helloWorld(scenarios);
      case "04-pr-risk-gate" -> prRiskGate(scenarios);
      case "05-dependency-updates" -> dependencyUpdates(scenarios);
      case "20-inbox-notification" -> inboxNotification(scenarios);
      default ->
          throw new AssertionError(
              example + " has a run.sh but is neither run nor listed in SKIPPED with a reason");
    }
  }

  private void helloWorld(final Scenarios scenarios) throws IOException {
    final String session = scenarios.applySession(session("01-hello-world"));

    assertThat(run(scenarios, session, "hello", Map.of(), "SUCCESS")).contains("Hello from Yukta!");
  }

  private void inboxNotification(final Scenarios scenarios) throws IOException {
    final String session = scenarios.applySession(session("20-inbox-notification"));

    assertThat(run(scenarios, session, "report", Map.of(), "SUCCESS"))
        .contains("nightly report ready");
    await()
        .atMost(WAIT)
        .untilAsserted(() ->
            assertThat(admin.call("GET", "/api/notifications?kind=INFO", null).body())
                .contains("Nightly report ready"));
  }

  private void prRiskGate(final Scenarios scenarios) throws IOException {
    Files.createDirectories(Path.of("/tmp/yukta-examples/pr-risk-gate"));
    final String example = "04-pr-risk-gate";
    final String session = scenarios.applySession(session(example));

    assertThat(run(scenarios, session, "pr", payload(example, "low-risk.json"), "SUCCESS"))
        .contains("decision=MERGED", "decidedBy=rules", "PR PR-1001: MERGED by rules");
    assertThat(run(scenarios, session, "pr", payload(example, "high-risk.json"), "SUCCESS"))
        .contains("decision=BLOCKED", "decidedBy=rules", "PR PR-1002: BLOCKED by rules");
    final String invalid =
        run(scenarios, session, "pr", payload(example, "invalid.json"), "SUCCESS");
    assertThat(invalid).contains("decision=NEEDS_INFO", "PR PR-1004: NEEDS_INFO by validator");
    assertThat(invalid).doesNotContain("score=");
    assertThat(
            runWithAnswer(
                scenarios,
                session,
                "pr",
                payload(example, "borderline.json"),
                "reviewer",
                "approve"))
        .contains("PR PR-1003: MERGED by reviewer");
    assertThat(
            runWithAnswer(
                scenarios,
                session,
                "pr",
                payload(example, "borderline.json"),
                "reviewer",
                "decline"))
        .contains("PR PR-1003: BLOCKED by reviewer");
  }

  private void dependencyUpdates(final Scenarios scenarios) throws IOException {
    Files.createDirectories(Path.of("/tmp/yukta-examples/dependency-updates"));
    final String example = "05-dependency-updates";
    final String session = scenarios.applySession(session(example));

    assertThat(run(scenarios, session, "updates", payload(example, "invalid.json"), "SUCCESS"))
        .contains("decision=NEEDS_INFO");
    assertThat(
            runWithAnswer(
                scenarios,
                session,
                "updates",
                payload(example, "major-bump.json"),
                "review-queue",
                "approve"))
        .contains("decidedBy=maintainer", "decision=MERGE_APPROVED");
    assertThat(
            runWithAnswer(
                scenarios,
                session,
                "updates",
                payload(example, "major-bump.json"),
                "review-queue",
                "deny"))
        .contains("decision=MERGE_DENIED");
  }
}
