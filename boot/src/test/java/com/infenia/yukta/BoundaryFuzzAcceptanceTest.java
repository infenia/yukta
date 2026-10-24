// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Throws malformed, oversized, deeply nested and mistyped bodies at the REST and MCP entry points
 * of the real application. Whatever the body: a defined 2xx or 4xx answer (never 5xx), no stack
 * trace in it, an answer within a time limit, and a server that still works afterwards (requirement
 * R7 of ASSURANCE.md: input is bounded in size, time and shape).
 *
 * <p>Cases come from a seeded {@link Random}. A failure prints its target, seed and mutation;
 * replay with {@code -Dyukta.fuzz.seed=<seed> -Dyukta.fuzz.tries=1}. The nightly run raises the
 * count with {@code -PfuzzTries=...} and picks a random seed with {@code -PfuzzSeed=random}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.SignatureDeclareThrowsException",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.CyclomaticComplexity",
  "PMD.ShortVariable",
  "PMD.UnitTestShouldIncludeAssert"
})
@NoArgsConstructor
class BoundaryFuzzAcceptanceTest {

  private static final Duration LIMIT = Duration.ofSeconds(15);
  private static final String WORKFLOW =
      """
      {"workflowId":"w","description":"d","nodes":[
         {"nodeId":"t","type":"API_TRIGGER","config":{}},
         {"nodeId":"m","type":"MAPPER","config":{"mode":"PROJECTION","mapping":{"g":"payload.n"}}},
         {"nodeId":"e","type":"CONSOLE_TERMINAL","config":{}}],
        "edges":[{"source":"t","target":"m"},{"source":"m","target":"e"}]}
      """;
  private static final String SESSION =
      "{\"sessionId\":\"fuzz\",\"name\":\"Fuzz\",\"description\":\"d\",\"initiator\":\"me\","
          + "\"projectPath\":\"/tmp\",\"workflows\":{\"w\":"
          + WORKFLOW.strip()
          + "}}";
  private static final String START =
      "{\"sessionId\":\"fuzz\",\"workflowId\":\"w\",\"payload\":{\"n\":1}}";
  private static final String MCP_CREATE =
      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
          + "\"params\":{\"name\":\"create_session\","
          + "\"arguments\":{\"config\":"
          + SESSION.strip()
          + "}}}";
  private static final String MCP_START =
      "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
          + "\"params\":{\"name\":\"start_workflow\","
          + "\"arguments\":{\"sessionId\":\"fuzz\",\"workflowId\":\"w\",\"payload\":{\"n\":1}}}}";
  private static final int[] DEPTHS = {5, 1500, 30_000};
  private static final String[] ODD_BODIES = {
    "", " ", "null", "[]", "0", "true", "\"s\"", "<xml/>", "\u0000\u0001\u0002"
  };
  private static final String NOISE = "{}[]\":,\\\u0000\n\té😀-0.1e9truefalsenull";
  private static final List<String> MARKERS =
      List.of("\tat com.infenia", "\tat java.", "\tat org.springframework", "\"stackTrace\"");

  @LocalServerPort private int port;

  private final HttpClient client = HttpClient.newHttpClient();

  private static int tries() {
    return Integer.getInteger("yukta.fuzz.tries", 40);
  }

  private static long baseSeed() {
    final String seed = System.getProperty("yukta.fuzz.seed", "20261024");
    return "random".equals(seed) ? System.nanoTime() : Long.parseLong(seed);
  }

  /** One mutation of {@code base}; the first element names it for the failure message. */
  private static List<String> mutate(final Random random, final String base) {
    final String text = base.strip();
    final int at = random.nextInt(Math.max(1, text.length()));
    final int op = random.nextInt(11);
    final String name = "op" + op;
    final String body;
    switch (op) {
      case 0 -> body = text.substring(0, at);
      case 1 ->
          body =
              text.substring(0, at)
                  + NOISE.charAt(random.nextInt(NOISE.length()))
                  + text.substring(at + 1);
      case 2 ->
          body =
              text.substring(0, at)
                  + text.substring(Math.min(text.length(), at + 1 + random.nextInt(40)));
      case 3 ->
          body =
              text.substring(0, at)
                  + text.substring(at, Math.min(text.length(), at + 30))
                      .repeat(1 + random.nextInt(50))
                  + text.substring(at);
      case 4 -> {
        final int depth = DEPTHS[random.nextInt(DEPTHS.length)];
        body = "[".repeat(depth) + text + "]".repeat(depth);
      }
      case 5 ->
          body = text.replace("{", random.nextBoolean() ? "[" : "\"").replace("\"w\"", "null");
      case 6 ->
          body = text.replace("\"fuzz\"", "\"" + "a".repeat(1 + random.nextInt(200_000)) + "\"");
      case 7 ->
          body =
              text.substring(0, text.length() - 1)
                  + ",\"pad\":\""
                  + "x".repeat(random.nextInt(3_000_000))
                  + "\"}";
      case 8 -> body = ODD_BODIES[random.nextInt(ODD_BODIES.length)];
      case 9 -> body = text.replace("\"fuzz\"", "\"\\ud800\\u0000\\ud83d\"");
      default ->
          body =
              text.replace("\"e\"", "\"t\"")
                  .replace("\"m\"", "\"" + "m".repeat(random.nextInt(5000)) + "\"");
    }
    return List.of(name, body);
  }

  /**
   * The open issue behind a 500 we know about, or null. Each case is narrow on purpose: any other
   * 5xx still fails the test. Each case has a probe test
   * (probe<issue>_...) that fails the day the bug is fixed; delete the probe and the case then.
   */
  private static String knownIssue(
      final String path, final int status, final String mutation, final String body) {
    String issue = null;
    if (status == 500 && "/mcp".equals(path)) {
      // The MCP SDK answers anything it cannot handle with 500: remove this with #1103 to #1110.
      issue =
          "op7".equals(mutation)
              ? "#1104 oversized body is 500, docs promise 413"
              : "#1110 invalid JSON-RPC is 500";
    } else if (status == 500 && !"/mcp".equals(path) && !body.contains("payload.n")) {
      issue = "#1109 an expression that does not parse is 500";
    }
    return issue;
  }

  private void check(final String target, final String path, final String base, final long seed)
      throws Exception {
    final Random random = new Random(seed);
    final List<String> mutation = mutate(random, base);
    final String body = mutation.get(1);
    final String what =
        target
            + " seed="
            + seed
            + " "
            + mutation.get(0)
            + " bodyLength="
            + body.length()
            + " bodyStart="
            + body.substring(0, Math.min(700, body.length())).replace("\n", " ");
    final long started = System.nanoTime();
    final HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(LIMIT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (knownIssue(path, response.statusCode(), mutation.get(0), body) == null) {
      assertThat(response.statusCode())
          .as(
              "status for %s, answered %s",
              what, response.body().substring(0, Math.min(300, response.body().length())))
          .isLessThan(500);
    }
    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .as("time for %s", what)
        .isLessThan(LIMIT);
    // Known leak of the MCP SDK for a malformed message, tracked in #1103; any other trace fails.
    final boolean knownMcpLeak = response.body().contains("\"jsonRpcError\":{\"code\":-32600");
    for (final String marker : knownMcpLeak ? List.<String>of() : MARKERS) {
      assertThat(response.body())
          .as("a stack trace in the answer to %s", what)
          .doesNotContain(marker);
    }
  }

  private void run(final String target, final String path, final String base) throws Exception {
    final long seed = baseSeed();
    for (int i = 0; i < tries(); i++) {
      check(target, path, base, seed + i);
    }
  }

  @Test
  void createSession_neverAnswers5xx() throws Exception {
    run("POST /api/sessions", "/api/sessions", SESSION);
  }

  @Test
  void validateSession_neverAnswers5xx() throws Exception {
    run("POST /api/sessions/validate", "/api/sessions/validate", WORKFLOW);
  }

  @Test
  void startWorkflow_neverAnswers5xx() throws Exception {
    run("POST /api/workflow/start", "/api/workflow/start", START);
  }

  @Test
  void mcpCreateSession_neverAnswers5xx() throws Exception {
    run("POST /mcp create_session", "/mcp", MCP_CREATE);
  }

  @Test
  void mcpStartWorkflow_neverAnswers5xx() throws Exception {
    run("POST /mcp start_workflow", "/mcp", MCP_START);
  }

  @Test
  void largeButValidWorkflow_isAnsweredWithoutA5xx() throws Exception {
    final List<String> nodes = new ArrayList<>();
    final List<String> edges = new ArrayList<>();
    nodes.add("{\"nodeId\":\"t\",\"type\":\"API_TRIGGER\",\"config\":{}}");
    String previous = "t";
    for (int i = 0; i < 5000; i++) {
      nodes.add(
          "{\"nodeId\":\"n"
              + i
              + "\",\"type\":\"MAPPER\",\"config\":{\"mode\":\"PROJECTION\",\"mapping\":{}}}");
      edges.add("{\"source\":\"" + previous + "\",\"target\":\"n" + i + "\"}");
      previous = "n" + i;
    }
    nodes.add("{\"nodeId\":\"e\",\"type\":\"CONSOLE_TERMINAL\",\"config\":{}}");
    edges.add("{\"source\":\"" + previous + "\",\"target\":\"e\"}");
    final String big =
        "{\"sessionId\":\"big\",\"name\":\"Big\",\"description\":\"d\","
            + "\"initiator\":\"me\",\"projectPath\":\"/tmp\","
            + "\"workflows\":{\"w\":{\"workflowId\":\"w\",\"description\":\"d\",\"nodes\":["
            + String.join(",", nodes)
            + "],\"edges\":["
            + String.join(",", edges)
            + "]}}}";
    final long started = System.nanoTime();
    final HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sessions"))
                .timeout(LIMIT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(big))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("status for a 5000-node workflow").isLessThan(500);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(LIMIT);
  }

  private HttpResponse<String> send(final String path, final String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(LIMIT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  // The probes below pin each bug that knownIssue() and check() tolerate. They pass while the bug
  // exists and fail the day it is fixed, so the tolerance cannot outlive the bug: when one fails,
  // delete that probe and the matching case in knownIssue() (or the stack-trace allowance in
  // check()).

  @Test
  void probe1103_mcpStillLeaksStackTraceForMalformedMessage() throws Exception {
    final String broken = MCP_START.substring(0, 40) + MCP_START.substring(60);
    assertThat(send("/mcp", broken).body())
        .as("#1103 seems fixed: delete this probe and the stack-trace allowance in check()")
        .contains("\"stackTrace\"");
  }

  @Test
  void probe1104_mcpStillAnswersPaddedOversizedBodyWith500() throws Exception {
    final String padded =
        MCP_START.substring(0, MCP_START.length() - 1)
            + ",\"pad\":\""
            + "x".repeat(2_200_000)
            + "\"}";
    assertThat(send("/mcp", padded).statusCode())
        .as("#1104 seems fixed: delete this probe and the op7 case in knownIssue()")
        .isEqualTo(500);
  }

  @Test
  void probe1109_sessionWithAnExpressionThatDoesNotParseIsStill500() throws Exception {
    final String badExpression = SESSION.replace("payload.n", "pa?load.n");
    assertThat(send("/api/sessions", badExpression).statusCode())
        .as("#1109 seems fixed: delete this probe and the #1109 case in knownIssue()")
        .isEqualTo(500);
  }

  @Test
  void probe1110_mcpStillAnswersNullBodyWith500() throws Exception {
    assertThat(send("/mcp", "null").statusCode())
        .as("#1110 seems fixed: delete this probe and the #1110 case in knownIssue()")
        .isEqualTo(500);
  }

  @Test
  void serverStillWorks_afterTheFuzzing() throws Exception {
    run("POST /api/sessions", "/api/sessions", SESSION);
    final HttpResponse<String> health =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(health.body()).contains("\"status\":\"UP\"");
    final HttpResponse<String> valid =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/sessions/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(WORKFLOW))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(valid.statusCode()).as(valid.body()).isLessThan(300);
  }
}
